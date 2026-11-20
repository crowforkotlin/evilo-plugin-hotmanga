package crow.evilo.plugin.hotmanga

/** Explicit HTTPS origins from Kira's ApiTransport.routes, not CopyManga's list API. */
data class HotMangaConfig(
    val listBaseUrl: String = PRIMARY_CONTENT_URL,
    val contentBaseUrl: String = PRIMARY_CONTENT_URL,
) {
    init {
        require(listBaseUrl in API_BASE_URLS && contentBaseUrl in API_BASE_URLS) {
            "HotManga requires a reviewed HTTPS mapi origin."
        }
    }

    companion object {
        const val PLUGIN_ID = "crow.evilo.source.hotmanga"
        const val PRIMARY_CONTENT_URL = "https://mapi.hotmangasg.com"
        const val SECONDARY_CONTENT_URL = "https://mapi.hotmangasd.com"
        val API_BASE_URLS: Set<String> = setOf(
            PRIMARY_CONTENT_URL,
            SECONDARY_CONTENT_URL,
            "https://mapi.hotmangasf.com",
            "https://mapi.elfgjfghkk.club",
            "https://mapi.fgjfghkkcenter.club",
            "https://mapi.fgjfghkk.club",
        )
        val LIST_BASE_URLS: Set<String> = API_BASE_URLS
        val CONTENT_BASE_URLS: Set<String> = API_BASE_URLS

        /** Retain the exact CDN path and subdomain; no wildcard or URL rewriting. */
        val MEDIA_BASE_URLS: Set<String> = buildSet {
            add("https://s3.mangafunb.fun")
            add("https://s0.mangafunb.fun")
            for (letter in 'a'..'z') add("https://s$letter.mangafunb.fun")
        }
    }
}
