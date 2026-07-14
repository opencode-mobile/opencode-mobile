package dev.opencode.mobile.ui.sessionchat.region

import android.util.Log
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionChatReviewChangesRegionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SessionChatReviewChanges(
    private val sessionRepository: SessionRepository,
    private val scope: CoroutineScope,
) {
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _state = MutableStateFlow(SessionChatReviewChangesRegionState())
    val state: StateFlow<SessionChatReviewChangesRegionState> = _state.asStateFlow()

    private var boundSessionId: String? = null
    private var boundWorktree: String? = null
    private var requestJob: Job? = null
    private var candidateMessageId: String? = null

    fun bind(sessionId: String, worktree: String) {
        if (boundSessionId == sessionId && boundWorktree == worktree) return
        requestJob?.cancel()
        boundSessionId = sessionId
        boundWorktree = worktree
        candidateMessageId = null
        _state.value = SessionChatReviewChangesRegionState()
    }

    fun updateCandidate(
        messages: List<SessionChatMessageItem>,
        hasResolvedSessionStatus: Boolean,
        isTurnActive: Boolean,
        isSending: Boolean,
    ) {
        val messageId =
            messages.lastOrNull { it.role == MessageRole.User && !it.isPending && !it.isStreaming }?.id
                .takeIf { hasResolvedSessionStatus && !isTurnActive && !isSending }
        if (messageId == candidateMessageId) return

        requestJob?.cancel()
        candidateMessageId = messageId
        _state.value = SessionChatReviewChangesRegionState()
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        if (messageId == null) return

        requestJob =
            scope.launch {
                val startedAt = System.currentTimeMillis()
                try {
                    val files = withContext(ioDispatcher) {
                        sessionRepository.getSessionDiff(sessionId, messageId, worktree)
                    }
                    if (candidateMessageId != messageId) return@launch
                    _state.value =
                        SessionChatReviewChangesRegionState(
                            messageId = messageId,
                            fileCount = files.size.takeIf { it > 0 },
                        )
                    logRequest(sessionId, worktree, files.size, "success", startedAt)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (candidateMessageId != messageId) return@launch
                    _state.value = SessionChatReviewChangesRegionState()
                    logRequest(sessionId, worktree, 0, "failure:${failureCategory(error)}", startedAt)
                }
            }
    }

    private fun failureCategory(error: Throwable): String =
        when (error) {
            is SessionError.Transport -> "transport"
            is SessionError.Http -> "http-${error.statusCode}"
            is SessionError.Decoding -> "decoding"
            else -> "unexpected"
        }

    private fun logRequest(
        sessionId: String,
        worktree: String,
        fileCount: Int,
        result: String,
        startedAt: Long,
    ) {
        runCatching {
            Log.i(
                TAG,
                "pill-count source=last-turn session=$sessionId directory=$worktree " +
                    "files=$fileCount result=$result durationMs=${System.currentTimeMillis() - startedAt}",
            )
        }
    }

    companion object {
        private const val TAG = "SessionChat"
    }
}
