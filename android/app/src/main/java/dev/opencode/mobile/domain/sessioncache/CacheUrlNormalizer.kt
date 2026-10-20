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

    /**
     * Server part of [connectionId] for purges that match any username on this server URL. Pair it
     * with [matchesConnectionIdPrefix], which requires the `|` delimiter next, so `:4096` never
     * matches `:40960`.
     */
    fun connectionIdPrefix(baseUrl: String): String =
        baseUrl
            .trim()
            .lowercase()
            .removeSuffix("|")
            .trimEnd('/') // strip trailing slash so both forms match

    /** True when [connectionId] is `<prefix>|user` or `<prefix>/|user`. */
    fun matchesConnectionIdPrefix(
        connectionId: String,
        prefix: String,
    ): Boolean {
        val id = connectionId.lowercase()
        return id.startsWith("$prefix|") || id.startsWith("$prefix/|")
    }
}
