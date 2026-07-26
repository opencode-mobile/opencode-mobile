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

    /** True when the message exists only in the REST snapshot and has no SSE info/part identity cache. */
    fun isBaseOnlyMessage(
        sessionId: String,
        messageId: String,
    ): Boolean

    fun hasStreamingText(sessionId: String): Boolean

    fun clear()
}
