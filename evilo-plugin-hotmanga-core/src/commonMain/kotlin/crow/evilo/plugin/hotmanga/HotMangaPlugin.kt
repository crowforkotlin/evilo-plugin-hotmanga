package crow.evilo.plugin.hotmanga

import crow.evilo.model.MediaId
import crow.evilo.model.MediaItem
import crow.evilo.model.MediaKind
import crow.evilo.plugin.api.EviloPluginService
import crow.evilo.plugin.api.PluginDescriptor
import crow.evilo.plugin.api.PluginJson
import crow.evilo.plugin.api.PluginOperation
import crow.evilo.plugin.api.PluginOperationKind
import crow.evilo.plugin.api.PluginResult
import crow.evilo.plugin.api.PluginStep
import crow.evilo.plugin.api.SourceResponse
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/** All operation state travels in PluginStep.context, including partially filled search pages. */
@Serializable
internal data class HotMangaContinuation(
    val operation: PluginOperationKind,
    val query: String,
    val page: Int,
    val mangaId: String?,
    val chapterId: String?,
    val origin: String,
    val attemptedOrigins: List<String>,
    val offset: Long = 0,
    val items: List<MediaItem> = emptyList(),
    val chapters: HotMangaChapterContext? = null,
    val exchanges: Int = 0,
    val chapterCount: Int = 0,
)

/**
 * Generic evilo service with no HTTP client, host lookup, persistent storage, or mutable
 * last-request state. Service start/resume calls may be interleaved between manga.
 */
class HotMangaPlugin(
    private val descriptor: PluginDescriptor,
    private val config: HotMangaConfig = HotMangaConfig(),
    clock: Clock = Clock.System,
) : EviloPluginService {
    private val parser = HotMangaParser(descriptor.id, config, clock)
    private val requests = HotMangaRequests()
    private val description = PluginJson.encodeToString(descriptor)

    init {
        require(descriptor.id == HotMangaConfig.PLUGIN_ID && descriptor.version.isNotBlank() && descriptor.kinds == setOf(MediaKind.MANGA)) {
            "HotManga requires the generated manga release descriptor."
        }
    }

    override fun describe(): String = description

    override fun start(operationJson: String): String {
        val operation = operation(operationJson)
        val origin = when (operation.operation) {
            PluginOperationKind.SEARCH -> config.listBaseUrl
            PluginOperationKind.CONTENT -> reviewedOrigin(operation.chapter?.extensions?.get(HOTMANGA_ORIGIN))
            else -> reviewedOrigin(operation.item?.extensions?.get(HOTMANGA_ORIGIN))
        }
        val initial = HotMangaContinuation(
            operation.operation, operation.query, operation.page, operation.item?.id?.value ?: operation.chapter?.itemId?.value,
            operation.chapter?.id, origin, listOf(origin),
            offset = if (operation.operation == PluginOperationKind.SEARCH) searchOffset(operation) else 0,
        )
        if (operation.operation == PluginOperationKind.CHAPTERS) {
            val snapshot = parser.cachedGroups(requireNotNull(operation.item))
            if (snapshot != null) {
                if (snapshot.groups.isEmpty()) return encode(PluginStep(result = PluginResult()))
                return requestStep(initial.copy(
                    origin = snapshot.origin,
                    attemptedOrigins = listOf(snapshot.origin),
                    chapters = HotMangaChapterContext(snapshot.mangaId, snapshot.groups),
                ))
            }
        }
        return requestStep(initial)
    }

    override fun resume(operationJson: String, context: String, responseJson: String): String {
        val operation = operation(operationJson)
        require(context.length <= 128 * 1024) { "HotManga continuation exceeds 128 KiB." }
        val continuation = HotMangaJson.decodeFromString<HotMangaContinuation>(context)
        validateContext(operation, continuation)
        val response = PluginJson.decodeFromString<SourceResponse>(responseJson)
        require(response.url == requestUrl(continuation)) { "HotManga response does not match its continuation request." }
        return try {
            when (operation.operation) {
                PluginOperationKind.SEARCH -> search(operation, continuation, response)
                PluginOperationKind.DETAILS -> encode(PluginStep(result = PluginResult(
                    details = parser.details(requireNotNull(operation.item), response),
                )))
                PluginOperationKind.CHAPTERS -> chapters(operation, continuation, response)
                PluginOperationKind.CONTENT -> encode(PluginStep(result = PluginResult(
                    content = parser.content(requireNotNull(operation.chapter), response),
                )))
            }
        } catch (failure: HotMangaApiException) {
            val nextOrigin = origins(continuation).firstOrNull { it !in continuation.attemptedOrigins }
            if (!failure.retryable || nextOrigin == null) throw failure
            requestStep(continuation.copy(origin = nextOrigin, attemptedOrigins = continuation.attemptedOrigins + nextOrigin))
        }
    }

    private fun search(operation: PluginOperation, context: HotMangaContinuation, response: SourceResponse): String {
        val limit = searchLimit(operation)
        val parsed = parser.catalog(response, context.offset, limit - context.items.size)
        val items = context.items + parsed.items
        check(items.map { it.id }.toSet().size == items.size) { "HotManga repeated comics while filling a search page." }
        if (parsed.hasNextPage && items.size < limit) {
            // API caps may reduce a response. Advance by its actual size, never by the requested limit.
            return requestStep(context.copy(offset = parsed.nextOffset, items = items, attemptedOrigins = listOf(context.origin)))
        }
        val extensions = mutableMapOf(
            "hotmanga.offset" to searchOffset(operation).toString(),
            "hotmanga.next_offset" to parsed.nextOffset.toString(),
            "hotmanga.limit" to limit.toString(),
        )
        parsed.total?.let { extensions["hotmanga.total"] = it.toString() }
        return encode(PluginStep(result = PluginResult(items = items, hasNextPage = parsed.hasNextPage, extensions = extensions)))
    }

    private fun chapters(operation: PluginOperation, context: HotMangaContinuation, response: SourceResponse): String {
        val item = requireNotNull(operation.item)
        val chapterContext = context.chapters
        if (chapterContext == null) {
            val groups = parser.discover(item.id.value, response)
            if (groups.isEmpty()) return encode(PluginStep(result = PluginResult()))
            return requestStep(context.copy(
                chapters = HotMangaChapterContext(item.id.value, groups),
                attemptedOrigins = listOf(context.origin),
            ))
        }
        val parsed = parser.chapters(item.id, chapterContext, response)
        val count = context.chapterCount + parsed.chapters.size
        require(count <= 20_000) { "HotManga operation exceeds the 20000-chapter limit." }
        val result = PluginResult(chapters = parsed.chapters)
        return if (parsed.nextContext == null) encode(PluginStep(result = result)) else requestStep(
            context.copy(chapters = parsed.nextContext, attemptedOrigins = listOf(context.origin), chapterCount = count), batch = result,
        )
    }

    private fun requestStep(context: HotMangaContinuation, batch: PluginResult? = null): String {
        require(context.exchanges < 512) { "HotManga operation exceeds evilo's 512-exchange limit." }
        val next = context.copy(exchanges = context.exchanges + 1)
        val encodedContext = HotMangaJson.encodeToString(next)
        require(encodedContext.encodeToByteArray().size <= 128 * 1024) { "HotManga continuation exceeds 128 KiB." }
        return encode(PluginStep(request = requests.get(requestUrl(next)), context = encodedContext, batch = batch))
    }

    private fun requestUrl(context: HotMangaContinuation): String {
        val path = when (context.operation) {
            PluginOperationKind.SEARCH -> hotMangaSearchPath(context.query, context.offset,
                (if (context.query.isBlank()) HOTMANGA_CATALOG_LIMIT else HOTMANGA_SEARCH_LIMIT) - context.items.size)
            PluginOperationKind.DETAILS -> hotMangaDetailsPath(requireNotNull(context.mangaId))
            PluginOperationKind.CHAPTERS -> context.chapters?.let(::hotMangaChaptersPath)
                ?: hotMangaDetailsPath(requireNotNull(context.mangaId))
            PluginOperationKind.CONTENT -> hotMangaPagesPath(requireNotNull(context.mangaId), requireNotNull(context.chapterId))
        }
        return context.origin + path
    }

    private fun operation(encoded: String): PluginOperation {
        require(encoded.encodeToByteArray().size <= 256 * 1024) { "HotManga operation exceeds 256 KiB." }
        return PluginJson.decodeFromString<PluginOperation>(encoded).also {
        require(it.kind == MediaKind.MANGA && it.page > 0 && it.query.length <= 4096) { "Invalid HotManga operation." }
        it.item?.let { item -> requireId(item.id) }
        it.chapter?.let { chapter -> requireId(chapter.itemId); encodeHotMangaPath(chapter.id) }
        when (it.operation) {
            PluginOperationKind.DETAILS, PluginOperationKind.CHAPTERS -> {
                requireNotNull(it.item) { "A comic is required." }
                require(it.chapter == null) { "A comic operation must not include another chapter." }
            }
            PluginOperationKind.CONTENT -> {
                val chapter = requireNotNull(it.chapter) { "A chapter is required." }
                val itemId = it.item?.id
                require(itemId == null || itemId == chapter.itemId) { "The chapter belongs to another comic." }
            }
            PluginOperationKind.SEARCH -> require(it.item == null && it.chapter == null) { "Search does not take a comic or chapter." }
        }
    }

    }

    private fun validateContext(operation: PluginOperation, context: HotMangaContinuation) {
        require(context.operation == operation.operation && context.query == operation.query && context.page == operation.page &&
            context.mangaId == (operation.item?.id?.value ?: operation.chapter?.itemId?.value) && context.chapterId == operation.chapter?.id
        ) { "HotManga continuation belongs to another operation." }
        val origins = origins(context)
        require(context.origin in origins && context.attemptedOrigins.isNotEmpty() && context.attemptedOrigins.last() == context.origin &&
            context.attemptedOrigins.all { it in origins } && context.attemptedOrigins.distinct().size == context.attemptedOrigins.size &&
            context.exchanges in 1..512 && context.chapterCount in 0..20_000 &&
            (operation.operation == PluginOperationKind.CHAPTERS || context.chapterCount == 0)
        ) { "Invalid HotManga mirror continuation." }
        if (operation.operation == PluginOperationKind.SEARCH) {
            require(context.chapters == null && context.items.size < searchLimit(operation) &&
                context.offset == searchOffset(operation) + context.items.size
            ) { "Invalid HotManga search continuation." }
            context.items.forEach { requireId(it.id) }
        } else require(context.items.isEmpty()) { "Only search continuations may contain comic items." }
        context.chapters?.let {
            require(operation.operation == PluginOperationKind.CHAPTERS && it.mangaId == context.mangaId &&
                it.groups.size in 1..MAX_CHAPTER_GROUPS && it.groupIndex in it.groups.indices && it.offset in 0..20_000
            ) { "Invalid HotManga chapter continuation." }
            require(it.groups.distinctBy { group -> group.pathWord }.size == it.groups.size) { "Repeated HotManga chapter groups." }
            it.groups.forEach { group ->
                encodeHotMangaPath(group.pathWord)
                require(group.name.isNotBlank() && group.name.length <= 512 && (group.count ?: 0) >= 0) { "Invalid HotManga chapter group." }
            }
        }
    }

    private fun origins(context: HotMangaContinuation): Set<String> =
        if (context.operation == PluginOperationKind.SEARCH) HotMangaConfig.LIST_BASE_URLS else HotMangaConfig.CONTENT_BASE_URLS

    private fun reviewedOrigin(value: String?): String = value?.takeIf { it in HotMangaConfig.CONTENT_BASE_URLS } ?: config.contentBaseUrl

    private fun requireId(id: MediaId) {
        require(id.pluginId == descriptor.id && id.kind == MediaKind.MANGA) { "Comic belongs to another plugin or media kind." }
        encodeHotMangaPath(id.value)
    }

    private fun searchLimit(operation: PluginOperation): Int =
        if (operation.query.isBlank()) HOTMANGA_CATALOG_LIMIT else HOTMANGA_SEARCH_LIMIT

    private fun searchOffset(operation: PluginOperation): Long = (operation.page.toLong() - 1) * searchLimit(operation)

    private fun encode(step: PluginStep): String = PluginJson.encodeToString(step).also {
        require(it.encodeToByteArray().size <= 4 * 1024 * 1024) { "HotManga result exceeds 4 MiB." }
    }
}
