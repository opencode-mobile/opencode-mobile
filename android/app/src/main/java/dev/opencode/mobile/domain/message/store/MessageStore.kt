package dev.opencode.mobile.domain.message

interface MessageStore {
    fun replaceAll(
        sessionId: String,
        messages: List<Message>,
    )

    fun apply(
        sessionId: String,
        patch: MessagePatch,
    ): MessageStoreApplyResult

    fun messages(sessionId: String): List<Message>

    /** True when a REST-seeded message contains a part without a stable server identity. */
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

    /**
     * Reconnect REST overlay: patch existing parts in place. Does not rematerialize
     * unchanged tools. No-op when [expectedRevision] is stale.
     */
    fun mergeAuthoritativeIfRevision(
        sessionId: String,
        messages: List<Message>,
        expectedRevision: Long,
    ): Boolean

    fun applyTailIfRevision(
        sessionId: String,
        tail: List<Message>,
        expectedRevision: Long,
    ): Boolean

    /** Reconnect tail overlay that preserves existing part state while updating the window. */
    fun mergeTailAuthoritativeIfRevision(
        sessionId: String,
        tail: List<Message>,
        expectedRevision: Long,
    ): Boolean

    fun prependOlderIfRevision(
        sessionId: String,
        older: List<Message>,
        expectedRevision: Long,
    ): Boolean

    /**
     * Copies media the session cache does not keep (tool images, file data URLs) from a fetched
     * page into the parts already stored, matched by message and part id. Nothing else changes,
     * and a full data URL is never replaced by an empty one. True when any part changed.
     */
    fun hydrateMedia(
        sessionId: String,
        messages: List<Message>,
    ): Boolean

    /** Ends cached streaming state after an idle reload failure so polling can recover. */
    fun finishStreamingText(sessionId: String)

    fun clear()
}
