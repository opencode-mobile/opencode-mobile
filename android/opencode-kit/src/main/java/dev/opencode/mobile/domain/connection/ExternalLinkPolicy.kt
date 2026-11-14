package dev.opencode.mobile.domain.connection

import java.net.URI

/**
 * Links the server hands the app (SSE `status.action.link`, failure sheets) open only as
 * web pages. Any other scheme (`tel:`, `file:`, `intent:`, custom app schemes) is dropped,
 * matching what markdown links already do.
 */
object ExternalLinkPolicy {
    fun isOpenable(raw: String?): Boolean {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val scheme = runCatching { URI(trimmed).scheme }.getOrNull()?.lowercase() ?: return false
        return scheme == "http" || scheme == "https"
    }
}
