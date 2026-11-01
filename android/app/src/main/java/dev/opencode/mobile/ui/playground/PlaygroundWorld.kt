package dev.opencode.mobile.ui.playground

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionActivity
import dev.opencode.mobile.domain.session.SessionTodo
import dev.opencode.mobile.domain.session.VcsFileStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the Session Chat Playground pretends a server holds, as the same domain
 * types the server adapters produce. The playground repositories read it; scenario
 * scripts and fake server actions change it.
 */
data class PlaygroundState(
    val rootSessionId: String,
    val sessions: List<Session>,
    val messages: Map<String, List<Message>> = emptyMap(),
    /** Sessions at work; every other session is idle. */
    val activity: Map<String, SessionActivity> = emptyMap(),
    val permissions: List<PermissionRequest> = emptyList(),
    val questions: List<QuestionRequest> = emptyList(),
    /** Todos of the root session. */
    val todos: List<SessionTodo> = emptyList(),
    /** Current branch; null hides the branch pill. */
    val branch: String? = null,
    val defaultBranch: String? = "main",
    val changedFiles: List<VcsFileStatus> = emptyList(),
    val turnDiff: List<FileDiff> = emptyList(),
    /** What a `/btw` side question answers. */
    val sideAnswer: String = PlaygroundContent.SIDE_ANSWER,
) {
    fun messages(sessionId: String): List<Message> = messages[sessionId].orEmpty()

    fun session(id: String): Session? = sessions.firstOrNull { it.id == id }

    fun activity(sessionId: String): SessionActivity = activity[sessionId] ?: SessionActivity.Idle

    fun isBusy(sessionId: String): Boolean = activity(sessionId) != SessionActivity.Idle

    fun withActivity(
        sessionId: String,
        activity: SessionActivity,
    ): PlaygroundState =
        copy(
            activity =
                if (activity == SessionActivity.Idle) {
                    this.activity - sessionId
                } else {
                    this.activity + (sessionId to activity)
                },
        )

    /** Adds [message], or replaces the one with its id. */
    fun upsert(
        sessionId: String,
        message: Message,
    ): PlaygroundState {
        val current = messages(sessionId)
        val index = current.indexOfFirst { it.id == message.id }
        val next = if (index >= 0) current.toMutableList().also { it[index] = message } else current + message
        return copy(messages = messages + (sessionId to next))
    }

    fun mapMessages(
        sessionId: String,
        transform: (Message) -> Message,
    ): PlaygroundState = copy(messages = messages + (sessionId to messages(sessionId).map(transform)))

    fun removeMessage(
        sessionId: String,
        messageId: String,
    ): PlaygroundState = copy(messages = messages + (sessionId to messages(sessionId).filterNot { it.id == messageId }))

    /** Stops [sessionId] the way an abort does: streaming ends, running tools end interrupted. */
    fun aborted(sessionId: String): PlaygroundState =
        withActivity(sessionId, SessionActivity.Idle).mapMessages(sessionId) { message ->
            if (message.role != MessageRole.Assistant) return@mapMessages message
            message.copy(
                parts =
                    message.parts.map { part ->
                        when {
                            part is MessagePart.Text && part.isStreaming -> part.copy(isStreaming = false)
                            part is MessagePart.Reasoning && part.isStreaming -> part.copy(isStreaming = false)
                            part is MessagePart.Tool &&
                                (part.status == ToolStatus.Running || part.status == ToolStatus.Pending) ->
                                part.copy(status = ToolStatus.Error, error = "Tool execution aborted", isInterrupted = true)
                            else -> part
                        }
                    },
            )
        }
}

/**
 * The playground's in-memory "server": one [PlaygroundState] plus a channel for actions
 * it cannot act on, which the screen shows as a short "Playground" snackbar.
 */
class PlaygroundWorld(
    initial: PlaygroundState,
    private val scope: CoroutineScope,
    val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<PlaygroundState> = _state.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    fun update(transform: (PlaygroundState) -> PlaygroundState) {
        _state.update(transform)
    }

    /** An action the playground does not act on. */
    fun notice(action: String) {
        _notices.tryEmit("Playground: $action is not available")
    }

    /** Runs [block] after [delayMs] unless the playground goes away first. */
    fun later(
        delayMs: Long,
        block: () -> Unit,
    ) {
        scope.launch {
            delay(delayMs)
            block()
        }
    }

    /** A prompt sent from the composer: it shows, the session works briefly, then a canned reply lands. */
    fun acceptPrompt(
        sessionId: String,
        text: String,
        messageId: String?,
    ) {
        val sentAt = nowMs()
        val userId = messageId ?: "pg-user-$sentAt"
        val replyId = "pg-reply-$sentAt"
        update { state ->
            state
                .upsert(sessionId, PlaygroundContent.userMessage(userId, text, sentAt))
                .withActivity(sessionId, SessionActivity.Running)
        }
        later(REPLY_DELAY_MS) {
            update { state ->
                if (!state.isBusy(sessionId)) return@update state
                state
                    .upsert(sessionId, PlaygroundContent.cannedReply(replyId, nowMs()))
                    .withActivity(sessionId, SessionActivity.Idle)
            }
        }
    }

    companion object {
        const val REPLY_DELAY_MS = 1_500L
    }
}
