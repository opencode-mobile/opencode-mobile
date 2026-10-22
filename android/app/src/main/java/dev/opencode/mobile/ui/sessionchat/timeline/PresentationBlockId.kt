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

    /** Chip timeline paragraph: narration text part ids followed by tool part ids. */
    fun stepParagraph(orderedPartIds: List<String>): String {
        require(orderedPartIds.isNotEmpty()) { "step paragraph requires at least one part id" }
        return "s:" + orderedPartIds.joinToString(SEP)
    }

    /**
     * Step rail timeline: keyed on the first member part only, never on all
     * members, so the rail keeps its id while later steps append.
     */
    fun stepRail(firstPartId: String): String = "sr:$firstPartId"

    /** Activity card timeline: keyed on the first member part only, like [stepRail]. */
    fun activityCard(firstPartId: String): String = "ac:$firstPartId"

    /**
     * Chip timeline: a run of settled thoughts rendered as one row. The id is the
     * previous row id followed by the new part id, so a growing run keeps a stable prefix.
     */
    fun mergedThought(ids: List<String>): String = "th:" + ids.joinToString("+")

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
