package dev.opencode.mobile.ui.playground

import dev.opencode.mobile.domain.agent.AgentRepository
import dev.opencode.mobile.domain.agent.MentionableAgent
import dev.opencode.mobile.domain.agent.SelectableAgent
import dev.opencode.mobile.domain.command.CommandRepository
import dev.opencode.mobile.domain.command.CommandRunOptions
import dev.opencode.mobile.domain.command.ServerCommand
import dev.opencode.mobile.domain.composer.ComposerDraft
import dev.opencode.mobile.domain.composer.ComposerDraftStore
import dev.opencode.mobile.domain.connection.ConnectionProbing
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.SecureCredentialStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.connection.V2ServerFeatures
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.feedback.FeedbackIdentityStore
import dev.opencode.mobile.domain.live.PermissionLiveStore
import dev.opencode.mobile.domain.live.ProjectionCaches
import dev.opencode.mobile.domain.live.QuestionLiveStore
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.domain.live.SessionTodoLiveStore
import dev.opencode.mobile.domain.message.DisplayMessage
import dev.opencode.mobile.domain.message.DisplayMessageListUpdate
import dev.opencode.mobile.domain.message.DisplayMessagePipeline
import dev.opencode.mobile.domain.message.DisplayMessagePreparing
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageListUpdate
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.PromptSendOptions
import dev.opencode.mobile.domain.message.ShellCommandOptions
import dev.opencode.mobile.domain.model.ModelRepository
import dev.opencode.mobile.domain.model.ModelSelectionCatalog
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.model.SessionModelSelection
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.PermissionDecision
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionActivity
import dev.opencode.mobile.domain.session.SessionActivityEntry
import dev.opencode.mobile.domain.session.SessionActivitySnapshot
import dev.opencode.mobile.domain.session.SessionActivitySource
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
import dev.opencode.mobile.domain.session.SessionTodo
import dev.opencode.mobile.domain.session.VcsDiffMode
import dev.opencode.mobile.domain.session.VcsFileStatus
import dev.opencode.mobile.domain.session.VcsInfo
import dev.opencode.mobile.network.ServerConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/*
 * The playground's data sources: the same repository and live-store interfaces the server
 * adapters implement, answered from a [PlaygroundWorld]. No network, no persistence.
 */

private const val NOT_IN_PLAYGROUND = "Not available in the Playground"

private fun unavailable(): Nothing = throw SessionError.Http(501, NOT_IN_PLAYGROUND)

/** The fake connection the playground binds; `example.com` never resolves to anything it calls. */
internal val playgroundProfile =
    ConnectionProfile(
        baseUrl = "https://playground.example.com/",
        username = "opencode",
        password = "",
        serverProtocol = ServerProtocol.V2,
        id = "playground",
        name = "Playground",
    )

private const val HISTORY_SETTLE_MS = 600L

class PlaygroundMessageRepository(
    private val world: PlaygroundWorld,
    private val preparing: DisplayMessagePreparing,
) : MessageRepository {
    private val pipeline =
        DisplayMessagePipeline(
            snapshotSource = { sessionId, _ -> observeMessages(sessionId) },
            contentPreparer = preparing,
        )

    override fun observeMessages(
        sessionId: String,
        directory: String?,
    ): Flow<MessageListUpdate> =
        flow {
            // History first, as a settled frame: the display pipeline renders markdown for what it
            // saw finished, and a session opened mid-stream would otherwise show raw history.
            val history = world.state.value.messages(sessionId)
            val settled = history.map(::settled)
            emit(settled)
            // A streaming frame cancels the history pass that renders markdown; let it finish first.
            if (settled != history) delay(HISTORY_SETTLE_MS)
            emitAll(world.state.map { it.messages(sessionId) })
        }.distinctUntilChanged()
            .map { MessageListUpdate.Messages(it) }

    private fun settled(message: Message): Message =
        message.copy(
            parts =
                message.parts.map { part ->
                    when (part) {
                        is MessagePart.Text -> part.copy(isStreaming = false)
                        is MessagePart.Reasoning -> part.copy(isStreaming = false)
                        else -> part
                    }
                },
        )

    override fun observeDisplayMessages(
        sessionId: String,
        directory: String?,
    ): Flow<DisplayMessageListUpdate> = pipeline.observeDisplayMessages(sessionId, directory)

    override suspend fun refreshMessages(
        sessionId: String,
        directory: String?,
    ): List<Message> = world.state.value.messages(sessionId)

    override suspend fun refreshDisplayMessages(
        sessionId: String,
        directory: String?,
    ): List<DisplayMessage> = preparing.prepareMessages(world.state.value.messages(sessionId))

    override suspend fun prepareOptimisticUserMessage(
        id: String,
        text: String,
    ): DisplayMessage = preparing.prepareMessage(PlaygroundContent.userMessage(id, text, world.nowMs()))

    override fun clearMessageCache() = Unit

    override suspend fun listMessages(
        sessionId: String,
        directory: String?,
    ): List<Message> = world.state.value.messages(sessionId)

    override suspend fun sendPromptAsync(
        sessionId: String,
        options: PromptSendOptions,
        directory: String?,
    ) {
        world.acceptPrompt(sessionId, options.text, options.messageId)
    }

    override suspend fun runShellCommand(
        sessionId: String,
        options: ShellCommandOptions,
        directory: String?,
    ) {
        world.notice("Shell commands")
    }
}

class PlaygroundSessionRepository(
    private val world: PlaygroundWorld,
) : SessionRepository {
    private val state get() = world.state.value

    override suspend fun listPendingPermissions(directory: String?): List<PermissionRequest> = state.permissions

    override suspend fun listPendingQuestions(directory: String?): List<QuestionRequest> = state.questions

    override suspend fun listSessions(directory: String?): List<Session> = state.sessions

    override suspend fun createSession(
        parentId: String?,
        title: String?,
        directory: String?,
    ): Session {
        world.notice("New sessions")
        unavailable()
    }

    override suspend fun getAllSessionStatuses(directory: String?): List<SessionStatus> =
        state.activity.map { (id, activity) ->
            SessionStatus(id, mapOf("type" to if (activity is SessionActivity.Retrying) "retry" else "busy"))
        }

    override suspend fun getSession(
        id: String,
        directory: String?,
    ): Session = state.session(id) ?: unavailable()

    override suspend fun updateSession(
        id: String,
        title: String?,
        directory: String?,
    ): Session {
        if (title != null) {
            world.update { current ->
                current.copy(sessions = current.sessions.map { if (it.id == id) it.copy(title = title) else it })
            }
        }
        return getSession(id)
    }

    override suspend fun archiveSession(
        id: String,
        directory: String?,
    ): Session {
        world.notice("Archive")
        unavailable()
    }

    override suspend fun deleteSession(
        id: String,
        directory: String?,
    ): Boolean {
        world.notice("Delete")
        return false
    }

    override suspend fun listChildSessions(
        id: String,
        directory: String?,
    ): List<Session> = state.sessions.filter { it.parentId == id }

    override suspend fun listSessionTodos(
        id: String,
        directory: String?,
    ): List<SessionTodo> = if (id == state.rootSessionId) state.todos else emptyList()

    override suspend fun initializeSessionAgents(
        id: String,
        messageId: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ): Boolean {
        world.notice("/init")
        return false
    }

    override suspend fun forkSession(
        id: String,
        atMessageId: String?,
        directory: String?,
    ): Session {
        world.notice("Fork")
        unavailable()
    }

    override suspend fun askSideQuestion(
        sessionId: String,
        question: String,
    ): String {
        delay(SIDE_ANSWER_DELAY_MS)
        return state.sideAnswer
    }

    override suspend fun abortSession(
        id: String,
        directory: String?,
    ): Boolean {
        world.update { it.aborted(id) }
        return true
    }

    override suspend fun shareSession(
        id: String,
        directory: String?,
    ): Session {
        world.notice("Share")
        unavailable()
    }

    override suspend fun unshareSession(
        id: String,
        directory: String?,
    ): Session {
        world.notice("Share")
        unavailable()
    }

    override suspend fun getSessionDiff(
        id: String,
        messageId: String?,
        directory: String?,
    ): List<FileDiff> = state.turnDiff

    override suspend fun getVcsInfo(directory: String?): VcsInfo =
        VcsInfo(currentBranch = state.branch, defaultBranch = state.defaultBranch)

    override suspend fun getVcsDiff(
        mode: VcsDiffMode,
        directory: String?,
        context: Int,
    ): List<FileDiff> = state.turnDiff

    override suspend fun getVcsStatus(directory: String?): List<VcsFileStatus> = state.changedFiles

    override suspend fun summarizeSession(
        id: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ): Boolean {
        world.notice("Compact")
        return false
    }

    override suspend fun revertSession(
        id: String,
        messageId: String,
        partId: String?,
        directory: String?,
    ): Boolean {
        world.notice("Undo")
        return false
    }

    override suspend fun unrevertSession(
        id: String,
        directory: String?,
    ): Boolean {
        world.notice("Redo")
        return false
    }

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        decision: PermissionDecision,
        directory: String?,
    ): Boolean {
        world.update { current -> current.copy(permissions = current.permissions.filterNot { it.id == permissionId }) }
        return true
    }

    override suspend fun replyToQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Boolean = dropQuestion(requestId)

    override suspend fun rejectQuestion(
        requestId: String,
        directory: String?,
    ): Boolean = dropQuestion(requestId)

    override suspend fun cancelPendingPrompt(
        sessionId: String,
        inboxId: String,
        directory: String?,
    ): Boolean {
        world.update { it.removeMessage(sessionId, inboxId) }
        return true
    }

    override suspend fun steerPendingPrompt(
        sessionId: String,
        inboxId: String,
        directory: String?,
    ): Boolean = setDelivery(sessionId, inboxId, dev.opencode.mobile.domain.message.FollowUpDelivery.Steer)

    override suspend fun queuePendingPrompt(
        sessionId: String,
        inboxId: String,
        directory: String?,
    ): Boolean = setDelivery(sessionId, inboxId, dev.opencode.mobile.domain.message.FollowUpDelivery.Queue)

    private fun dropQuestion(requestId: String): Boolean {
        world.update { current -> current.copy(questions = current.questions.filterNot { it.id == requestId }) }
        return true
    }

    private fun setDelivery(
        sessionId: String,
        inboxId: String,
        delivery: dev.opencode.mobile.domain.message.FollowUpDelivery,
    ): Boolean {
        world.update { current ->
            current.mapMessages(sessionId) { if (it.id == inboxId) it.copy(followUpDelivery = delivery) else it }
        }
        return true
    }

    companion object {
        const val SIDE_ANSWER_DELAY_MS = 1_200L
    }
}

class PlaygroundSessionStatusStore(
    private val world: PlaygroundWorld,
) : SessionStatusLiveStore {
    override fun observeActivity(directory: String): Flow<SessionActivitySnapshot> =
        world.state
            .map { state -> state.sessions.map { it.id } to state.activity }
            .distinctUntilChanged()
            .map { (ids, activity) ->
                val now = world.nowMs()
                SessionActivitySnapshot(
                    entries =
                        ids.associateWith { id ->
                            SessionActivityEntry(activity[id] ?: SessionActivity.Idle, SessionActivitySource.Server, now)
                        },
                    seeded = true,
                )
            }

    override fun clearCache() = Unit
}

class PlaygroundPermissionStore(
    private val world: PlaygroundWorld,
) : PermissionLiveStore {
    override fun observePermissions(directory: String): Flow<List<PermissionRequest>> =
        world.state.map { it.permissions }.distinctUntilChanged()

    override fun clearCache() = Unit
}

class PlaygroundQuestionStore(
    private val world: PlaygroundWorld,
) : QuestionLiveStore {
    override fun observeQuestions(directory: String): Flow<List<QuestionRequest>> = world.state.map { it.questions }.distinctUntilChanged()

    override fun clearCache() = Unit
}

class PlaygroundTodoStore(
    private val world: PlaygroundWorld,
) : SessionTodoLiveStore {
    override fun observeTodos(
        sessionId: String,
        directory: String?,
    ): Flow<List<SessionTodo>> =
        world.state
            .map { if (sessionId == it.rootSessionId) it.todos else emptyList() }
            .distinctUntilChanged()

    override fun setLive(
        isLive: Boolean,
        sessionId: String,
        directory: String?,
    ) = Unit

    override fun clearCache() = Unit
}

/** Always connected; carries no events, since the world feeds the stores directly. */
class PlaygroundEventStream : ForegroundEventStream {
    override val connectionState: StateFlow<EventStreamConnectionState> = MutableStateFlow(EventStreamConnectionState.Connected)
    override val events: SharedFlow<OpencodeEvent> = MutableSharedFlow()

    override fun start() = Unit

    override suspend fun stop() = Unit
}

class PlaygroundModelRepository : ModelRepository {
    override suspend fun listModelOptions(directory: String?): ModelSelectionCatalog {
        val coder =
            ModelSelectionOption(
                providerId = PlaygroundContent.PROVIDER_ID,
                providerName = "Acme",
                modelId = PlaygroundContent.MODEL_ID,
                modelName = "Acme Coder 1",
                variants = listOf("low", "high"),
                contextLimitTokens = 200_000,
            )
        val mini =
            ModelSelectionOption(
                providerId = PlaygroundContent.PROVIDER_ID,
                providerName = "Acme",
                modelId = "acme-mini",
                modelName = "Acme Mini",
                contextLimitTokens = 64_000,
            )
        return ModelSelectionCatalog(options = listOf(coder, mini), serverDefault = coder)
    }
}

class PlaygroundAgentRepository : AgentRepository {
    override suspend fun listSelectableAgents(directory: String?): List<SelectableAgent> = SelectableAgent.Fallback

    override suspend fun listMentionableAgents(directory: String?): List<MentionableAgent> =
        listOf(
            MentionableAgent(name = "explore", description = "Read-only explorer"),
            MentionableAgent(name = "general", description = "General helper"),
        )
}

class PlaygroundCommandRepository(
    private val world: PlaygroundWorld,
) : CommandRepository {
    override suspend fun listCommands(directory: String?): List<ServerCommand> =
        listOf(
            ServerCommand(name = "review", description = "Review the current changes"),
            ServerCommand(name = "test", description = "Run the test suite"),
        )

    override suspend fun runCommand(
        sessionId: String,
        options: CommandRunOptions,
        directory: String?,
    ) {
        world.acceptPrompt(sessionId, "/${options.command} ${options.arguments}".trim(), null)
    }
}

/** Reports v2 with side questions, so every pill the playground shows has a server that could make it. */
object PlaygroundConnectionProbing : ConnectionProbing {
    override suspend fun probe(connection: ServerConnection): ServerProtocol = ServerProtocol.V2

    override suspend fun probeV2Features(connection: ServerConnection): V2ServerFeatures =
        V2ServerFeatures(sessionArchive = true, sessionTurnDiff = true, sessionGenerate = true)
}

class PlaygroundProfileStore : ConnectionProfileStore {
    private var profile: ConnectionProfile? = null

    override suspend fun activeProfile(): ConnectionProfile? = profile

    override suspend fun saveProfile(profile: ConnectionProfile) {
        this.profile = profile
    }

    override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = emptyList()

    override suspend fun addWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) = Unit

    override suspend fun removeWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) = Unit

    override suspend fun setAddedWorktrees(
        serverBaseUrl: String,
        worktrees: List<String>,
    ) = Unit

    override suspend fun clearAll() {
        profile = null
    }
}

object PlaygroundCredentialStore : SecureCredentialStore {
    override fun readPassword(serverBaseUrl: String): String? = null

    override fun writePassword(
        serverBaseUrl: String,
        password: String,
    ) = Unit

    override fun deletePassword(serverBaseUrl: String) = Unit

    override fun clearAll() = Unit
}

object PlaygroundFeedbackIdentityStore : FeedbackIdentityStore {
    override fun readRefreshToken(): String? = null

    override fun writeRefreshToken(token: String?) = Unit
}

object PlaygroundProjectionCaches : ProjectionCaches {
    override fun clearAll() = Unit

    override fun clearLiveProjections() = Unit

    override fun bindProfile(profileId: String?) = Unit
}

class PlaygroundDraftStore : ComposerDraftStore {
    private val drafts = mutableMapOf<String, ComposerDraft>()

    override suspend fun load(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
    ): ComposerDraft? = drafts["$worktree|$sessionId"]

    override suspend fun save(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
        draft: ComposerDraft,
    ) {
        drafts["$worktree|$sessionId"] = draft
    }

    override suspend fun delete(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
    ) {
        drafts.remove("$worktree|$sessionId")
    }

    override suspend fun deleteSessionEntries(
        connectionIdentity: ModelSelectionConnectionIdentity,
        sessionId: String,
    ) {
        drafts.keys.removeAll { it.endsWith("|$sessionId") }
    }

    override suspend fun clearAll() = drafts.clear()
}

class PlaygroundModelSelectionStore : SessionModelSelectionStore {
    private val selections = mutableMapOf<String, SessionModelSelection>()

    override suspend fun loadSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
    ): SessionModelSelection? = selections["$worktree|$sessionId"]

    override suspend fun saveSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
        selection: SessionModelSelection,
    ) {
        selections["$worktree|$sessionId"] = selection
    }

    override suspend fun deleteSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
    ) {
        selections.remove("$worktree|$sessionId")
    }

    override suspend fun deleteSessionEntries(
        connectionIdentity: ModelSelectionConnectionIdentity,
        sessionId: String,
    ) {
        selections.keys.removeAll { it.endsWith("|$sessionId") }
    }

    override suspend fun loadNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
    ): SessionModelSelection? = selections[worktree]

    override suspend fun saveNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        selection: SessionModelSelection,
    ) {
        selections[worktree] = selection
    }

    override suspend fun clearAll() = selections.clear()
}
