package crow.evilo.plugin.hotmanga

/** Provider rejection remains distinct from a transient HTTP/missing-payload failure. */
class HotMangaApiException(
    val httpStatusCode: Int,
    val apiCode: String?,
    message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
