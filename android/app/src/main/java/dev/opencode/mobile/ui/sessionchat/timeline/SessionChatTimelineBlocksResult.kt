package dev.opencode.mobile.ui.sessionchat

sealed class SessionChatTimelineBlocksResult {
    data class Ok(
        val blocks: List<SessionChatTimelineBlock>,
    ) : SessionChatTimelineBlocksResult()

    /** Displayable parts were missing stable server part IDs. */
    data object MissingPartIds : SessionChatTimelineBlocksResult()
}
