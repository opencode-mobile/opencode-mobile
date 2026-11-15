package dev.opencode.mobile.ui.sessionchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.SessionToolGroup
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageListUpdate
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.ui.sessionchat.view.SessionChatToolDetailTarget
import dev.opencode.mobile.ui.sessionchat.view.SessionChatToolDetails
import dev.opencode.mobile.ui.sessionchat.view.SessionToolGroupMembers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the tool group route shows: its members while they resolve, or that the group is gone. */
sealed interface SessionToolGroupMembersState {
    /** The transcript has not been read yet. */
    data object Loading : SessionToolGroupMembersState

    data class Ready(
        val members: List<SessionChatTimelineBlock.ToolCard>,
    ) : SessionToolGroupMembersState

    /** The live transcript no longer holds the group; the route goes back. */
    data object Gone : SessionToolGroupMembersState
}

/**
 * Screen model of the tool group route (#1033). The route key names the members by block id;
 * this reads them from the session's transcript in the message store (the same one Session Chat
 * observes, so it adds no load), and builds a member's detail from its tool part when it opens.
 */
@HiltViewModel
class SessionToolGroupViewModel
    @Inject
    constructor(
        private val messageRepository: MessageRepository,
    ) : ViewModel() {
        private val _members = MutableStateFlow<SessionToolGroupMembersState>(SessionToolGroupMembersState.Loading)
        val members: StateFlow<SessionToolGroupMembersState> = _members.asStateFlow()

        private var route: SessionToolGroup? = null
        private var messages: List<Message> = emptyList()
        private var observation: Job? = null

        fun bind(route: SessionToolGroup) {
            if (this.route == route) return
            this.route = route
            observation?.cancel()
            observation =
                viewModelScope.launch {
                    messageRepository.observeMessages(route.sessionId, route.transcriptWorktree).collect { update ->
                        if (update !is MessageListUpdate.Messages) return@collect
                        messages = update.messages
                        val members = SessionToolGroupMembers.resolve(route, update.messages)
                        _members.value =
                            when {
                                members != null -> SessionToolGroupMembersState.Ready(members)
                                // A cached first paint can predate the group; wait for the live load.
                                update.isFromCache -> _members.value
                                else -> SessionToolGroupMembersState.Gone
                            }
                    }
                }
        }

        /** The detail sheet [card] opens, built now from its tool part. Null once the part is gone. */
        internal fun toolDetail(card: SessionChatTimelineBlock.ToolCard): SessionChatToolDetailTarget? =
            SessionChatToolDetails.target(card, messages)
    }
