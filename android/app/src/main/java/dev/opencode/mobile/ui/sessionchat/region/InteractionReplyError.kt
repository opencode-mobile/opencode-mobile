package dev.opencode.mobile.ui.sessionchat.region

import dev.opencode.mobile.domain.session.ServerErrorMessage

/**
 * Short, human copy for a failed permission or question reply. Transport detail
 * goes to the diagnostic log; the user sees what failed plus the server's own
 * reason when it is meaningful.
 */
enum class InteractionReplyError(
    private val lead: String,
) {
    PermissionReply("Couldn't send your reply."),
    QuestionReply("Couldn't send your answer."),
    QuestionDismiss("Couldn't dismiss the question."),
    ;

    fun message(error: Throwable): String = message(ServerErrorMessage.from(error))

    fun message(serverMessage: String?): String = serverMessage?.let { "$lead $it." } ?: "$lead Try again."
}
