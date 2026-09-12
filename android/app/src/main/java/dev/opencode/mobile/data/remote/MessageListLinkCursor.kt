package dev.opencode.mobile.data.remote

internal object MessageListLinkCursor {
    fun fromHeaders(
        nextCursor: String?,
        link: String?,
    ): String? {
        nextCursor?.takeIf { it.isNotBlank() }?.let { return it }
        return parseLinkRelNext(link)
    }

    fun parseLinkRelNext(link: String?): String? {
        if (link.isNullOrBlank()) return null
        for (part in link.split(',')) {
            val relNext =
                part.contains("rel=\"next\"", ignoreCase = true) ||
                    part.contains("rel=next", ignoreCase = true)
            if (!relNext) continue
            val url = Regex("<([^>]+)>").find(part)?.groupValues?.get(1) ?: continue
            return cursorFromUrl(url)
        }
        return null
    }

    fun cursorFromUrl(url: String): String? {
        val query = url.substringAfter('?', missingDelimiterValue = "")
        if (query.isNotEmpty()) {
            queryParameter(query, "before")?.let { return it }
            queryParameter(query, "cursor")?.let { return it }
        }
        val last = url.substringBefore('?').substringAfterLast('/')
        return last.takeIf { it.isNotBlank() }
    }

    private fun queryParameter(
        query: String,
        name: String,
    ): String? {
        val raw =
            query
                .split('&')
                .firstOrNull { it.startsWith("$name=") }
                ?.substringAfter('=')
                ?.takeIf { it.isNotBlank() }
                ?: return null
        return runCatching { java.net.URLDecoder.decode(raw, Charsets.UTF_8) }.getOrDefault(raw)
    }
}
