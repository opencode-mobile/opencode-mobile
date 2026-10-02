package dev.opencode.mobile.ui.sessionchat

/**
 * Status-chrome copy for Session Compact (OPE-116, prototype B).
 * Running label and success snackbar with optional context-usage delta.
 */
object SessionCompactChrome {
    const val STATUS_LABEL = "Compacting…"

    /** Timeline marker for a server compaction item; the summary opens from it. */
    const val MARKER_TITLE = "Context compacted"
    const val MARKER_AUTO_SUBTITLE = "Automatic"
    const val MARKER_TOOL_NAME = "compaction"
    const val FALLBACK_SUCCESS = "Session compacted."
    const val FAILURE = "Couldn't compact session context."

    fun successMessage(
        beforeFraction: Float?,
        afterFraction: Float?,
    ): String {
        // 0% means usage is unknown (no token counts yet), not an empty context.
        if (beforeFraction == null || afterFraction == null || beforeFraction <= 0f || afterFraction <= 0f) {
            return FALLBACK_SUCCESS
        }
        val before = (beforeFraction * 100f).toInt()
        val after = (afterFraction * 100f).toInt()
        return "Context compacted · $before% → $after%"
    }
}
