package dev.opencode.mobile.domain.message

interface MessageStore {
    fun replaceAll(
        sessionId: String,
        messages: List<Message>,
    )

    fun apply(
        sessionId: String,
        patch: MessagePatch,
    )

    fun messages(sessionId: String): List<Message>

    fun isBaseOnlyMessage(
        sessionId: String,
        messageId: String,
    ): Boolean

    fun clear()
}
