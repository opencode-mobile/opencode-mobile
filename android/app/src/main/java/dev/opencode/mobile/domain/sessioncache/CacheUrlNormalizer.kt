package dev.opencode.mobile.domain.sessioncache

object CacheUrlNormalizer {
    fun normalize(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    /** Matches MessageSync / PreparedHistoryCacheKey profile ids. */
    fun connectionId(
        baseUrl: String,
        username: String,
    ): String = "${baseUrl.trim().lowercase()}|${username.trim().lowercase()}"

    /** Prefix for purge matching any username on this server URL. */
    fun connectionIdPrefix(baseUrl: String): String =
        baseUrl.trim().lowercase().let { url ->
            if (url.endsWith("|")) url else url.trimEnd('/') // strip trailing slash so both forms match
        }
}
