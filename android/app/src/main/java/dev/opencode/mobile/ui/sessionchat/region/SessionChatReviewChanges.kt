package dev.opencode.mobile.ui.sessionchat.region

import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.ui.sessionchat.LastTurnReviewChangesStore
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionChatReviewChangesRegionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SessionChatReviewChanges(
    private val scope: CoroutineScope,
    private val supportsSessionMessageDiff: () -> Boolean = { true },
    private val lastTurnReviewChangesStore: LastTurnReviewChangesStore = LastTurnReviewChangesStore(),
) {
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _state = MutableStateFlow(SessionChatReviewChangesRegionState())
    val state: StateFlow<SessionChatReviewChangesRegionState> = _state.asStateFlow()

    private var boundSessionId: String? = null
    private var boundWorktree: String? = null
    private var candidateMessageId: String? = null
    private var observeJob: Job? = null

    fun bind(
        sessionId: String,
        worktree: String,
    ) {
        if (boundSessionId == sessionId && boundWorktree == worktree) return
        boundSessionId = sessionId
        boundWorktree = worktree
        candidateMessageId = null
        _state.value = SessionChatReviewChangesRegionState()
        observeJob?.cancel()
        observeJob =
            scope.launch {
                lastTurnReviewChangesStore.observe(sessionId).collect { snapshot ->
                    applyPublishedSnapshot(snapshot)
                }
            }
    }

    fun updateCandidate(
        messages: List<SessionChatMessageItem>,
        hasResolvedSessionStatus: Boolean,
        isTurnActive: Boolean,
        isSending: Boolean,
    ) {
        val messageId =
            messages
                .lastOrNull { it.role == MessageRole.User && !it.isPending && !it.isStreaming }
                ?.id
                .takeIf { supportsSessionMessageDiff() && hasResolvedSessionStatus && !isTurnActive && !isSending }
        if (messageId != candidateMessageId) {
            candidateMessageId = messageId
            // Bind must not fetch last-turn diff. The Changes screen owns GET …/diff.
            // Without a loaded summary the Review Changes pill stays hidden.
            _state.value = SessionChatReviewChangesRegionState(messageId = messageId)
        }
        applyPublishedSnapshot()
    }

    private fun applyPublishedSnapshot(
        snapshot: LastTurnReviewChangesStore.Snapshot? =
            boundSessionId?.let { lastTurnReviewChangesStore.snapshot(it) },
    ) {
        val sessionId = boundSessionId ?: return
        val published = snapshot ?: return
        if (published.sessionId != sessionId) return
        if (published.messageId != candidateMessageId) return
        _state.value =
            SessionChatReviewChangesRegionState(
                messageId = published.messageId,
                summary = published.summary,
            )
    }
}
