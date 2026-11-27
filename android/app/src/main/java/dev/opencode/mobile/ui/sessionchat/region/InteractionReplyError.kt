package dev.opencode.mobile.ui.sessionchat.region

import androidx.annotation.StringRes
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.session.ServerErrorMessage
import dev.opencode.mobile.domain.text.UiText

/**
 * Short, human copy for a failed permission or question reply. Transport detail
 * goes to the diagnostic log; the user sees what failed plus the server's own
 * reason when it is meaningful.
 */
enum class InteractionReplyError(
    @StringRes private val retry: Int,
    @StringRes private val withReason: Int,
) {
    PermissionReply(R.string.session_chat_reply_error_permission, R.string.session_chat_reply_error_permission_reason),
    QuestionReply(R.string.session_chat_reply_error_question, R.string.session_chat_reply_error_question_reason),
    QuestionDismiss(R.string.session_chat_reply_error_dismiss, R.string.session_chat_reply_error_dismiss_reason),
    ;

    fun message(error: Throwable): UiText = message(ServerErrorMessage.text(error))

    /** "Couldn't send your reply. <reason>." with [reason], else "… Try again." */
    fun message(reason: UiText?): UiText = reason?.let { UiText.resource(withReason, it) } ?: UiText.resource(retry)
}
