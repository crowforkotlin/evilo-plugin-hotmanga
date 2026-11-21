package crow.evilo.plugin.hotmanga

import crow.evilo.model.MediaChapter
import crow.evilo.model.MediaId
import crow.evilo.model.MediaItem
import crow.evilo.model.MediaKind
import crow.evilo.plugin.api.PluginDescriptor
import crow.evilo.plugin.api.PluginJson
import crow.evilo.plugin.api.PluginOperation
import crow.evilo.plugin.api.PluginOperationKind
import crow.evilo.plugin.api.PluginStep
import crow.evilo.plugin.api.SourceResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** Local protocol fixtures only; no network clients, signing identities, or live endpoints. */
class HotMangaPluginTest {
    private val descriptor = PluginDescriptor(HotMangaConfig.PLUGIN_ID, "HotManga", "1.0.0", 1,
        setOf(MediaKind.MANGA), listOf("zh"))
    private val clock = MutableClock(1_800_000_000L)
    private val plugin = HotMangaPlugin(descriptor, clock = clock)
    private val item = MediaItem(MediaId(descriptor.id, MediaKind.MANGA, "example"), "Example",
        "https://untrusted.invalid/ignored")
    private val chapter = MediaChapter(item.id, "chapter-1", "First", "https://untrusted.invalid/ignored")

    @Test
    fun descriptorAndOperationsRequireCanonicalPluginIdentity() {
        assertEquals(descriptor, PluginJson.decodeFromString<PluginDescriptor>(plugin.describe()))
        assertFailsWith<IllegalArgumentException> { HotMangaPlugin(descriptor.copy(id = "crow.evilo.source.copymanga")) }
        assertFailsWith<IllegalArgumentException> {
            start(PluginOperation(PluginOperationKind.DETAILS, MediaKind.MANGA,
                item = item.copy(id = item.id.copy(pluginId = "other"))))
        }
        assertFailsWith<IllegalArgumentException> {
            start(PluginOperation(PluginOperationKind.CONTENT, MediaKind.MANGA,
                item = item.copy(id = item.id.copy(value = "other")), chapter = chapter))
        }
    }

    @Test
    fun catalogSearchAndChapterPathsFollowKiraMangaApiNotCartoonOrCopyListApi() {
        val catalog = start(PluginOperation(PluginOperationKind.SEARCH, MediaKind.MANGA))
        assertEquals(HotMangaConfig.PRIMARY_CONTENT_URL +
            "/api/v3/comics?free_type=1&ordering=-datetime_updated&limit=21&offset=0", catalog.request?.url)
        val search = start(PluginOperation(PluginOperationKind.SEARCH, MediaKind.MANGA, "漫画 &?#", 2))
        assertEquals(HotMangaConfig.PRIMARY_CONTENT_URL +
            "/api/v3/search/comic?platform=3&q=%E6%BC%AB%E7%94%BB%20%26%3F%23&limit=20&offset=20&free_type=1&_update=true",
            search.request?.url)
        val chapters = PluginOperation(PluginOperationKind.CHAPTERS, MediaKind.MANGA, item = item)
        val discovery = start(chapters)
        assertTrue(assertNotNull(discovery.request).url.endsWith("/comic2/example?platform=3"))
        val list = resume(chapters, discovery, detailsJson())
        assertTrue(assertNotNull(list.request).url.endsWith("/comic/example/group/default/chapters?limit=100&offset=0"))
        val content = start(PluginOperation(PluginOperationKind.CONTENT, MediaKind.MANGA, chapter = chapter))
        assertTrue(assertNotNull(content.request).url.endsWith("/comic/example/chapter/chapter-1?platform=3"))
        for (step in listOf(catalog, search, discovery, list, content)) {
            val request = assertNotNull(step.request)
            assertEquals("GET", request.method)
            assertNull(request.body)
            assertEquals(HotMangaRequests.API_HEADERS, request.headers)
            assertEquals("2024.04.28", request.headers["version"])
            assertTrue(request.headers.keys.none { it.lowercase() in setOf(
                "source", "x-auth-signature", "tapstring", "host", "origin", "cookie", "authorization", "connection") })
            assertFalse(request.url.contains("cartoon"))
        }
        assertFailsWith<IllegalArgumentException> { HotMangaConfig(listBaseUrl = "https://api.copy202601.com") }
        assertFailsWith<IllegalArgumentException> { HotMangaConfig(contentBaseUrl = "https://untrusted.invalid") }
    }

    @Test
    fun shortPagesAdvanceByActualCountAndKeepPluginQualifiedIds() {
        val operation = PluginOperation(PluginOperationKind.SEARCH, MediaKind.MANGA, "example")
        val first = resume(operation, start(operation), catalogJson(0, 1..10, 21))
        assertTrue(assertNotNull(first.request).url.contains("limit=10&offset=10"))
        val result = assertNotNull(resume(operation, first, catalogJson(10, 11..20, 21)).result)
        assertEquals((1..20).map { "comic-$it" }, result.items.map { it.id.value })
        assertTrue(result.items.all { it.id.pluginId == descriptor.id })
        assertTrue(result.hasNextPage)
        val last = operation.copy(page = 2)
        assertFalse(assertNotNull(resume(last, start(last), catalogJson(20, 21..21, 21)).result).hasNextPage)
    }

    @Test
    fun freeTypeMetadataPreservesScalarAndStructuredDetailValues() {
        for ((value, expected) in listOf("1" to "1", "\"1\"" to "1", "{\"name\":\"Free\",\"value\":1}" to "{\"name\":\"Free\",\"value\":1}")) {
            val operation = PluginOperation(PluginOperationKind.DETAILS, MediaKind.MANGA, item = item)
            val detail = assertNotNull(resume(operation, start(operation), """{"code":200,"results":{
                "comic":{"path_word":"example","name":"Example","free_type":$value},
                "groups":{"default":{"count":1}}}}""").result?.details)
            assertEquals(expected, detail.extensions["hotmanga.free_type"])
            val chapters = PluginOperation(PluginOperationKind.CHAPTERS, MediaKind.MANGA, item = detail)
            assertTrue(assertNotNull(start(chapters).request).url.contains("/group/default/chapters"))
        }
    }

    @Test
    fun detailsMapMetadataAndCacheGroupsForFiveMinutes() {
        val operation = PluginOperation(PluginOperationKind.DETAILS, MediaKind.MANGA, item = item)
        val detail = assertNotNull(resume(operation, start(operation), """{"code":"200","results":{
            "comic":{"path_word":"example","name":"New","brief":"Description","author":[{"name":" Author "}],
                "theme":[{"name":"Action"}],"cover":"//sm.mangafunb.fun/m/cover.jpg"},
            "groups":{"default":{"name":"Main","count":"2"}}}}""").result?.details)
        assertEquals(item.id, detail.id)
        assertEquals(listOf("Author"), detail.authors)
        assertEquals(listOf("Action"), detail.tags)
        assertEquals("https://sm.mangafunb.fun/m/cover.jpg", detail.coverUrl)
        val chapters = PluginOperation(PluginOperationKind.CHAPTERS, MediaKind.MANGA, item = detail)
        assertTrue(assertNotNull(start(chapters).request).url.contains("/group/default/chapters"))
        clock.seconds += 300
        assertTrue(assertNotNull(start(chapters).request).url.contains("/comic2/example"))
    }

    @Test
    fun nestedComicGroupsFollowKirasDetailFallback() {
        val operation = PluginOperation(PluginOperationKind.CHAPTERS, MediaKind.MANGA, item = item)
        val step = resume(operation, start(operation), """{"code":200,"results":{"comic":{
            "path_word":"example","groups":{"special":{"name":"Special","count":1}}},"groups":{}}}""")
        assertTrue(assertNotNull(step.request).url.contains("/group/special/chapters"))
    }

    @Test
    fun chapterGroupsAndPagesCanInterleaveWithNoMutableLastRequest() {
        val operation = PluginOperation(PluginOperationKind.CHAPTERS, MediaKind.MANGA, item = item)
        val discovered = resume(operation, start(operation), """{"code":200,"results":{"comic":{},"groups":{
            "volume":{"name":"Volume","count":1},"default":{"name":"Main","count":2}}}}""")
        val first = resume(operation, discovered, """{"code":200,"results":{"total":"2","offset":0,"limit":1,
            "list":[{"uuid":"first","name":"First","index":"1.5","comic_path_word":"example","group_path_word":"default"}]}}""")
        assertEquals("Main: First", first.batch?.chapters?.single()?.title)
        assertEquals(1.5, first.batch?.chapters?.single()?.number)
        assertTrue(assertNotNull(first.request).url.endsWith("limit=100&offset=1"))
        val other = operation.copy(item = item.copy(id = item.id.copy(value = "other")))
        val otherDiscovery = resume(other, start(other), """{"code":200,"results":{"comic":{},"groups":{"special":{}}}}""")
        assertTrue(assertNotNull(otherDiscovery.request).url.contains("/comic/other/group/special/"))
        val second = resume(operation, first, """{"code":200,"results":{"total":2,"offset":1,"list":[{"uuid":"second"}]}}""")
        assertTrue(assertNotNull(second.request).url.contains("/group/volume/"))
        val completed = assertNotNull(resume(operation, second, """{"code":200,"results":{"total":1,"list":[{"uuid":"volume"}]}}""").result)
        assertEquals("Volume: volume", completed.chapters.single().title)
    }

    @Test
    fun missingWordsKeepCompleteServerOrderAndLegacyWordsReorderWithoutDroppingImages() {
        val operation = PluginOperation(PluginOperationKind.CONTENT, MediaKind.MANGA, chapter = chapter)
        val contents = """[{"url":"https://sm.mangafunb.fun/m/two.webp"},{"url":"https://sm.mangafunb.fun/m/one.webp"}]"""
        val server = assertNotNull(resume(operation, start(operation), """{"code":200,"results":{"chapter":{
            "uuid":"chapter-1","size":"2","contents":$contents}}}""").result?.content).images
        assertEquals(listOf("two.webp", "one.webp"), server.map { it.imageUrl.substringAfterLast('/') })
        assertEquals(listOf(0, 1), server.map { it.index })
        assertTrue(server.all { it.headers == HotMangaRequests.IMAGE_HEADERS })
        val ordered = assertNotNull(resume(operation, start(operation), """{"code":200,"results":{"chapter":{
            "words":["2","1"],"contents":$contents}}}""").result?.content).images
        assertEquals(listOf("one.webp", "two.webp"), ordered.map { it.imageUrl.substringAfterLast('/') })
    }

    @Test
    fun mirrorsRetryOnlyHttpEnvelopeAndMissingPayloadFailuresAndThenStop() {
        val operation = PluginOperation(PluginOperationKind.CONTENT, MediaKind.MANGA, chapter = chapter)
        var step = resume(operation, start(operation), "error", 503)
        assertTrue(assertNotNull(step.request).url.startsWith(HotMangaConfig.SECONDARY_CONTENT_URL))
        step = resume(operation, step, """{"code":200,"results":{"chapter":{"contents":[]}}}""")
        assertTrue(assertNotNull(step.request).url.startsWith("https://mapi.hotmangasf.com/"))
        repeat(HotMangaConfig.API_BASE_URLS.size - 3) {
            step = resume(operation, step, """{"code":200,"results":null}""")
        }
        assertFailsWith<HotMangaApiException> { resume(operation, step, """{"code":200,"results":null}""") }
        val denied = assertFailsWith<HotMangaApiException> {
            resume(operation, start(operation), """{"code":210,"message":"Login required"}""")
        }
        assertFalse(denied.retryable)
        assertTrue(denied.message.orEmpty().contains("Login required"))
        assertFailsWith<IllegalStateException> {
            resume(operation, start(operation), """{"code":200,"results":{"is_lock":true}}""")
        }
    }

    @Test
    fun requestBindingRejectsDifferentQueryOriginAndContinuationIdentity() {
        val operation = PluginOperation(PluginOperationKind.CONTENT, MediaKind.MANGA, chapter = chapter)
        val step = start(operation)
        for (url in listOf(assertNotNull(step.request).url + "&other=1", "https://untrusted.invalid/api/v3/comic/example/chapter/chapter-1?platform=3")) {
            assertFailsWith<IllegalArgumentException> {
                plugin.resume(PluginJson.encodeToString(operation), step.context,
                    PluginJson.encodeToString(SourceResponse(url, 200, emptyMap(), "{}")))
            }
        }
        assertFailsWith<IllegalArgumentException> { resume(operation.copy(chapter = chapter.copy(id = "other")), step, "{}") }
    }

    @Test
    fun securityAndAllocationLimitsRejectUnknownCdnMalformedOrderAndOversizeResponse() {
        val operation = PluginOperation(PluginOperationKind.CONTENT, MediaKind.MANGA, chapter = chapter)
        for (url in listOf("http://sm.mangafunb.fun/m/image.jpg", "https://sm.mangafunb.fun@evil.invalid/m/image.jpg",
            "https://sm.mangafunb.fun.evil.invalid/m/image.jpg", "https://untrusted.invalid/image.jpg")) {
            assertFailsWith<IllegalStateException> {
                resume(operation, start(operation), """{"code":200,"results":{"chapter":{"contents":[{"url":"$url"}]}}}""")
            }
        }
        for (words in listOf("[0]", "[0,0]", "[-1,0]")) {
            assertFailsWith<IllegalStateException> {
                resume(operation, start(operation), """{"code":200,"results":{"chapter":{"words":$words,
                    "contents":[{"url":"https://sm.mangafunb.fun/m/one"},{"url":"https://sm.mangafunb.fun/m/two"}]}}}""")
            }
        }
        assertFailsWith<IllegalArgumentException> { resume(operation, start(operation), " ".repeat(2 * 1024 * 1024 + 1)) }
        assertFailsWith<IllegalArgumentException> {
            start(operation.copy(chapter = chapter.copy(id = "..")))
        }
    }

    private fun start(operation: PluginOperation): PluginStep = PluginJson.decodeFromString(plugin.start(PluginJson.encodeToString(operation)))
    private fun resume(operation: PluginOperation, step: PluginStep, body: String, status: Int = 200): PluginStep =
        PluginJson.decodeFromString(plugin.resume(PluginJson.encodeToString(operation), step.context,
            PluginJson.encodeToString(SourceResponse(assertNotNull(step.request).url, status, emptyMap(), body))))
    private fun detailsJson() = """{"code":200,"results":{"comic":{"path_word":"example"},"groups":{"default":{"count":1}}}}"""
    private fun catalogJson(offset: Int, indexes: IntRange, total: Int): String {
        val list = indexes.joinToString(",") { """{"path_word":"comic-$it","name":"Comic $it"}""" }
        return """{"code":200,"results":{"offset":$offset,"limit":10,"total":$total,"list":[$list]}}"""
    }
    private class MutableClock(var seconds: Long) : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(seconds)
    }
}
