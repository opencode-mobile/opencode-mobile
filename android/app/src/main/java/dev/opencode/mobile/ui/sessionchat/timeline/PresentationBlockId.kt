package dev.opencode.mobile.ui.sessionchat

/**
 * Deterministic presentation [blockID] encoding for Phase 5.
 * Missing server part IDs are not invented here — callers must use recovery
 * or [messageFallback] only when structured build is incomplete.
 */
object PresentationBlockId {
    private const val SEP = "\u001f"

    fun direct(partId: String): String = "p:$partId"

    fun mergedText(orderedPartIds: List<String>): String {
        require(orderedPartIds.isNotEmpty()) { "merged text requires at least one part id" }
        return if (orderedPartIds.size == 1) {
            direct(orderedPartIds.first())
        } else {
            "t:" + orderedPartIds.joinToString(SEP)
        }
    }

    fun toolGroup(orderedMemberIds: List<String>): String {
        require(orderedMemberIds.size >= 2) { "tool group requires at least two member ids" }
        return "g:" + orderedMemberIds.joinToString(SEP)
    }

    /** Unstructured fallback when displayable parts lack stable IDs. */
    fun messageFallback(messageId: String): String = "m:$messageId"
}

sealed class SessionChatTimelineBlocksResult {
    data class Ok(
        val blocks: List<SessionChatTimelineBlock>,
    ) : SessionChatTimelineBlocksResult()

    /** Displayable parts were missing stable server part IDs. */
    data object MissingPartIds : SessionChatTimelineBlocksResult()
}
