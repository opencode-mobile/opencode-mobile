package dev.opencode.mobile.ui.sessionchat

/**
 * Status-chrome copy for Session Compact (OPE-116, prototype B).
 * Running label and success snackbar with optional context-usage delta.
 */
object SessionCompactChrome {
    const val STATUS_LABEL = "Compacting…"
    const val FALLBACK_SUCCESS = "Session compacted."
    const val FAILURE = "Couldn't compact session context."

    fun successMessage(
        beforeFraction: Float?,
        afterFraction: Float?,
    ): String {
        if (beforeFraction == null || afterFraction == null) return FALLBACK_SUCCESS
        val before = (beforeFraction * 100f).toInt()
        val after = (afterFraction * 100f).toInt()
        return "Context compacted · $before% → $after%"
    }
}
