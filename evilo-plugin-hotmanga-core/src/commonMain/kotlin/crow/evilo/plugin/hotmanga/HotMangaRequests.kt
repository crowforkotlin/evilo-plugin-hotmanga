package crow.evilo.plugin.hotmanga

import crow.evilo.plugin.api.SourceRequest

internal const val HOTMANGA_SEARCH_LIMIT = 20
internal const val HOTMANGA_CATALOG_LIMIT = 21
internal const val HOTMANGA_CHAPTER_LIMIT = 100

/** Kira's anonymous manga API identity. No CopyManga app markers, HMAC, or credentials. */
internal class HotMangaRequests {
    fun get(url: String): SourceRequest {
        require(url.length <= 4096 && HotMangaConfig.API_BASE_URLS.any { url.startsWith("$it/api/v3/") }) {
            "HotManga request requires a reviewed API origin and path within 4096 characters."
        }
        return SourceRequest(url, headers = API_HEADERS)
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15; 23113RKC6C Build/AQ3A.240812.002; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.200 Mobile Safari/537.36"
        val API_HEADERS: Map<String, String> = mapOf(
            "Accept" to "application/json",
            "platform" to "3",
            "User-Agent" to USER_AGENT,
            "webp" to "1",
            "version" to "2024.04.28",
            "X-Requested-With" to "com.manga2020.app",
        )
        val IMAGE_HEADERS: Map<String, String> = mapOf("User-Agent" to USER_AGENT)
    }
}

internal fun hotMangaSearchPath(query: String, offset: Long, limit: Int): String {
    require(offset >= 0 && limit in 1..(if (query.isBlank()) HOTMANGA_CATALOG_LIMIT else HOTMANGA_SEARCH_LIMIT)) {
        "Invalid HotManga list pagination."
    }
    return if (query.isBlank()) {
        "/api/v3/comics?free_type=1&ordering=-datetime_updated&limit=$limit&offset=$offset"
    } else {
        "/api/v3/search/comic?platform=3&q=${encodeHotMangaComponent(query)}&limit=$limit&offset=$offset&free_type=1&_update=true"
    }
}

internal fun hotMangaDetailsPath(mangaId: String): String =
    "/api/v3/comic2/${encodeHotMangaPath(mangaId)}?platform=3"

internal fun hotMangaChaptersPath(context: HotMangaChapterContext): String =
    "/api/v3/comic/${encodeHotMangaPath(context.mangaId)}/group/" +
        "${encodeHotMangaPath(context.groups[context.groupIndex].pathWord)}/chapters" +
        "?limit=$HOTMANGA_CHAPTER_LIMIT&offset=${context.offset}"

internal fun hotMangaPagesPath(mangaId: String, chapterId: String): String =
    "/api/v3/comic/${encodeHotMangaPath(mangaId)}/chapter/${encodeHotMangaPath(chapterId)}?platform=3"

internal fun hotMangaDetailsUrl(origin: String, mangaId: String): String = origin + hotMangaDetailsPath(mangaId)
internal fun hotMangaPagesUrl(origin: String, mangaId: String, chapterId: String): String = origin + hotMangaPagesPath(mangaId, chapterId)

internal fun hotMangaImageUrl(value: String): String {
    val trimmed = value.trim()
    val url = if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
    check(url.length <= 4096 && url.startsWith("https://") &&
        url.none { it <= ' ' || it == '\u007f' || it == '\\' || it == '#' }
    ) { "HotManga images require a valid HTTPS URL."
    }
    val origin = "https://" + url.removePrefix("https://").substringBefore('/').substringBefore('?')
    check(origin in HotMangaConfig.MEDIA_BASE_URLS) { "HotManga returned an unapproved image origin: $origin." }
    check(url.startsWith("$origin/") && url.length > origin.length + 1) { "HotManga returned an image without a path." }
    return url
}

internal fun encodeHotMangaPath(value: String): String {
    require(value.isNotBlank() && value.length <= 512 && value != "." && value != ".." &&
        value.none { it < ' ' || it == '\u007f' }
    ) { "HotManga path segments must be non-empty IDs without control characters or dot segments." }
    return encodeHotMangaComponent(value)
}

internal fun encodeHotMangaComponent(value: String): String = buildString {
    val hex = "0123456789ABCDEF"
    for (byte in value.encodeToByteArray()) {
        val code = byte.toInt() and 0xff
        if (code in 'a'.code..'z'.code || code in 'A'.code..'Z'.code || code in '0'.code..'9'.code ||
            code == '-'.code || code == '_'.code || code == '.'.code || code == '~'.code
        ) append(code.toChar()) else {
            append('%')
            append(hex[code ushr 4])
            append(hex[code and 0x0f])
        }
    }
}
