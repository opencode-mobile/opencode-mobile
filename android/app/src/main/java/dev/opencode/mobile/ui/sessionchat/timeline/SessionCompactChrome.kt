package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.R
import dev.opencode.mobile.ui.text.UiText

/**
 * Status-chrome copy for Session Compact (OPE-116, prototype B).
 * Running label and success snackbar with optional context-usage delta.
 */
object SessionCompactChrome {
    val statusLabel: UiText = UiText.resource(R.string.session_chat_compact_status)

    /**
     * The same label for the working indicator, whose session status labels are still plain
     * strings end to end (shared with the session status chrome). Moves to [statusLabel] when
     * that pipeline carries UiText.
     */
    const val STATUS_LABEL = "Compacting…"

    /** Timeline marker for a server compaction item; the summary opens from it. */
    val markerTitle: UiText = UiText.resource(R.string.timeline_compaction_title)
    val markerAutoSubtitle: UiText = UiText.resource(R.string.timeline_compaction_automatic)
    const val MARKER_TOOL_NAME = "compaction"
    val fallbackSuccess: UiText = UiText.resource(R.string.session_chat_compact_success)
    val failure: UiText = UiText.resource(R.string.session_chat_compact_failure)

    fun successMessage(
        beforeFraction: Float?,
        afterFraction: Float?,
    ): UiText {
        // 0% means usage is unknown (no token counts yet), not an empty context.
        if (beforeFraction == null || afterFraction == null || beforeFraction <= 0f || afterFraction <= 0f) {
            return fallbackSuccess
        }
        val before = (beforeFraction * 100f).toInt()
        val after = (afterFraction * 100f).toInt()
        return UiText.resource(R.string.session_chat_compact_success_usage, before, after)
    }
}
