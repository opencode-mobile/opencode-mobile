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

    /** True when the message came from REST and therefore has no stable SSE part identities. */
    fun isRestSeededMessage(
        sessionId: String,
        messageId: String,
    ): Boolean

    fun hasStreamingText(sessionId: String): Boolean

    /** Monotonic per-session revision for guarding suspending REST requests. */
    fun revision(sessionId: String): Long

    /** Atomically replaces the session only when no patch landed after [expectedRevision]. */
    fun replaceAllIfRevision(
        sessionId: String,
        messages: List<Message>,
        expectedRevision: Long,
    ): Boolean

    /** Ends cached streaming state after an idle reload failure so polling can recover. */
    fun finishStreamingText(sessionId: String)

    fun clear()
}
