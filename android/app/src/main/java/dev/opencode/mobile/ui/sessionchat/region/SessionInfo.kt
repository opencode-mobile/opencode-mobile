package dev.opencode.mobile.ui.sessionchat.region

import android.util.Log
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.model.ModelSelection
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionInfoRegionState
import dev.opencode.mobile.ui.sessionchat.SessionRenameUiState
import dev.opencode.mobile.ui.sessionchat.toSessionConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class SessionInfo(
    private val sessionRepository: SessionRepository,
    private val sessionStatusLiveStore: SessionStatusLiveStore,
    private val foregroundEventStream: ForegroundEventStream,
    private val eventDomainAdapter: EventDomainAdapter,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SessionInfoRegionState())
    val state: StateFlow<SessionInfoRegionState> = _state.asStateFlow()

    private var boundSessionId: String? = null
    private var boundSessionTitle: String? = null
    private var boundWorktree: String? = null
    private var observationJob: Job? = null
    private var renameJob: Job? = null

    fun bind(
        sessionId: String,
        sessionTitle: String,
        worktree: String,
    ) {
        // Session identity is id + worktree. Ignore stale navigation titles after rename /
        // session.updated so configuration changes do not wipe the live title.
        if (boundSessionId == sessionId && boundWorktree == worktree) {
            return
        }
        stopObserving()
        renameJob?.cancel()
        renameJob = null
        boundSessionId = sessionId
        boundSessionTitle = sessionTitle
        boundWorktree = worktree
        _state.value =
            SessionInfoRegionState(
                sessionId = sessionId,
                sessionTitle = sessionTitle,
            )
        logI("bind session=$sessionId worktree=$worktree title=$sessionTitle")
        startObserving(sessionId, worktree)
        refreshStatus()
    }

    fun stopObserving() {
        observationJob?.cancel()
        observationJob = null
    }

    fun openRenameSession() {
        val title = _state.value.sessionTitle
        _state.update {
            it.copy(
                rename =
                    SessionRenameUiState(
                        isDialogVisible = true,
                        draftTitle = title,
                    ),
            )
        }
    }

    fun cancelRenameSession() {
        renameJob?.cancel()
        renameJob = null
        _state.update { it.copy(rename = SessionRenameUiState()) }
    }

    fun updateRenameDraft(text: String) {
        _state.update {
            it.copy(
                rename =
                    it.rename.copy(
                        draftTitle = text,
                        errorMessage = null,
                    ),
            )
        }
    }

    fun confirmRenameSession() {
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        if (_state.value.rename.isSubmitting) return

        val trimmed = _state.value.rename.draftTitle.trim()
        if (trimmed.isEmpty()) {
            _state.update {
                it.copy(rename = it.rename.copy(errorMessage = EMPTY_TITLE_ERROR))
            }
            return
        }
        if (trimmed == _state.value.sessionTitle) {
            cancelRenameSession()
            return
        }

        _state.update {
            it.copy(rename = it.rename.copy(isSubmitting = true, errorMessage = null))
        }
        logI("rename submit session=$sessionId")
        renameJob?.cancel()
        renameJob =
            scope.launch {
                runCatching {
                    sessionRepository.updateSession(
                        id = sessionId,
                        title = trimmed,
                        directory = worktree,
                    )
                }.onSuccess { session ->
                    if (!isCurrentBinding(sessionId, worktree)) return@onSuccess
                    boundSessionTitle = session.title
                    _state.update {
                        it.copy(
                            sessionTitle = session.title,
                            rename = SessionRenameUiState(),
                        )
                    }
                    logI("rename success session=$sessionId")
                }.onFailure { error ->
                    if (!isCurrentBinding(sessionId, worktree)) return@onFailure
                    logW("rename failed session=$sessionId message=${error.message}")
                    _state.update {
                        it.copy(
                            rename =
                                it.rename.copy(
                                    isSubmitting = false,
                                    errorMessage = renameErrorMessage(error),
                                ),
                        )
                    }
                }
            }
    }

    fun refreshStatus() {
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        scope.launch {
            runCatching { sessionRepository.getAllSessionStatuses(directory = worktree) }
                .onSuccess { statuses ->
                    if (!isCurrentBinding(sessionId, worktree)) return@onSuccess
                    val status = statuses.firstOrNull { it.sessionId == sessionId }
                    val label = status?.let(eventDomainAdapter::statusLabel)
                    _state.update {
                        it.copy(sessionStatusLabel = label, hasResolvedSessionStatus = true)
                    }
                }
        }
    }

    fun archiveSession() {
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        if (_state.value.isLifecycleActionInFlight) return
        scope.launch {
            _state.update {
                it.copy(isLifecycleActionInFlight = true, lifecycleErrorMessage = null)
            }
            logI("archive start session=$sessionId")
            runCatching { sessionRepository.archiveSession(id = sessionId, directory = worktree) }
                .onSuccess {
                    if (!isCurrentBinding(sessionId, worktree)) return@onSuccess
                    logI("archive success session=$sessionId")
                    _state.update {
                        it.copy(isLifecycleActionInFlight = false, shouldLeaveSession = true)
                    }
                }.onFailure { error ->
                    if (!isCurrentBinding(sessionId, worktree)) return@onFailure
                    logI("archive failure session=$sessionId error=${error.message}")
                    _state.update {
                        it.copy(
                            isLifecycleActionInFlight = false,
                            lifecycleErrorMessage = ARCHIVE_ERROR,
                        )
                    }
                }
        }
    }

    fun deleteSession() {
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        if (_state.value.isLifecycleActionInFlight) return
        scope.launch {
            _state.update {
                it.copy(isLifecycleActionInFlight = true, lifecycleErrorMessage = null)
            }
            logI("delete start session=$sessionId")
            runCatching { sessionRepository.deleteSession(id = sessionId, directory = worktree) }
                .onSuccess { deleted ->
                    if (!isCurrentBinding(sessionId, worktree)) return@onSuccess
                    if (deleted) {
                        logI("delete success session=$sessionId")
                        _state.update {
                            it.copy(isLifecycleActionInFlight = false, shouldLeaveSession = true)
                        }
                    } else {
                        logI("delete rejected session=$sessionId")
                        _state.update {
                            it.copy(
                                isLifecycleActionInFlight = false,
                                lifecycleErrorMessage = DELETE_ERROR,
                            )
                        }
                    }
                }.onFailure { error ->
                    if (!isCurrentBinding(sessionId, worktree)) return@onFailure
                    logI("delete failure session=$sessionId error=${error.message}")
                    _state.update {
                        it.copy(
                            isLifecycleActionInFlight = false,
                            lifecycleErrorMessage = DELETE_ERROR,
                        )
                    }
                }
        }
    }

    fun contextUsageFraction(
        messages: List<SessionChatMessageItem>,
        modelOptions: List<ModelSelectionOption>,
        selectedModel: ModelSelection?,
    ): Float? {
        val latestAssistantWithUsage =
            messages
                .asReversed()
                .firstOrNull { it.role == MessageRole.Assistant && it.contextInputTokens != null }
                ?: return null
        val inputTokens = latestAssistantWithUsage.contextInputTokens ?: return null
        val messageHasModel =
            latestAssistantWithUsage.modelProviderId != null && latestAssistantWithUsage.modelId != null
        val contextLimit: Long =
            modelLimit(modelOptions, latestAssistantWithUsage)
                ?: (if (messageHasModel) null else selectedModel?.option?.contextLimitTokens)
                ?: return null
        if (contextLimit <= 0L) return null
        return (inputTokens.toFloat() / contextLimit.toFloat()).coerceIn(0f, 1f)
    }

    private fun startObserving(
        sessionId: String,
        worktree: String,
    ) {
        observationJob =
            scope.launch {
                coroutineScope {
                    launch {
                        sessionStatusLiveStore.observeStatuses(worktree).collect { statuses ->
                            if (!isCurrentBinding(sessionId, worktree)) return@collect
                            val status = statuses[sessionId]
                            logD(
                                "live status session=$sessionId type=${status?.raw?.get("type") ?: "-"}",
                            )
                            DiagnosticLog.debug(
                                DiagnosticLogArea.Session,
                                "live status session=$sessionId type=${status?.raw?.get("type") ?: "-"}",
                            )
                            _state.update {
                                it.copy(
                                    sessionStatusLabel = status?.let(eventDomainAdapter::statusLabel),
                                    hasResolvedSessionStatus = it.hasResolvedSessionStatus || status != null,
                                )
                            }
                        }
                    }
                    launch {
                        foregroundEventStream.connectionState.collect { connectionState ->
                            if (!isCurrentBinding(sessionId, worktree)) return@collect
                            logD("live connection session=$sessionId state=$connectionState")
                            DiagnosticLog.debug(
                                DiagnosticLogArea.Session,
                                "live connection session=$sessionId state=$connectionState",
                            )
                            _state.update {
                                it.copy(connectionStatus = connectionState.toSessionConnectionStatus())
                            }
                        }
                    }
                    launch {
                        foregroundEventStream.events.collect { event ->
                            if (!isCurrentBinding(sessionId, worktree)) return@collect
                            val eventScope =
                                when (event.sessionId) {
                                    sessionId -> "bound"
                                    null -> "global"
                                    else -> "other"
                                }
                            logD(
                                "SSE event observed session=$sessionId type=${event.type} " +
                                    "eventSession=${event.sessionId ?: "-"} scope=$eventScope",
                            )
                            if (event.type == "session.error" && event.sessionId == sessionId) {
                                _state.update { it.copy(errorMessage = sessionErrorMessage(event.rawProperties)) }
                            }
                            applySessionUpdatedTitle(event, sessionId)
                        }
                    }
                }
            }
    }

    private fun applySessionUpdatedTitle(
        event: OpencodeEvent,
        sessionId: String,
    ) {
        val update = eventDomainAdapter.sessionTitleUpdate(event) ?: return
        if (update.sessionId != sessionId) return
        if (update.title == _state.value.sessionTitle) return
        boundSessionTitle = update.title
        _state.update { it.copy(sessionTitle = update.title) }
        logI("session.updated title session=$sessionId title=${update.title}")
        DiagnosticLog.info(DiagnosticLogArea.Session, "session.updated title session=$sessionId")
    }

    private fun modelLimit(
        modelOptions: List<ModelSelectionOption>,
        message: SessionChatMessageItem,
    ): Long? {
        val providerId = message.modelProviderId ?: return null
        val modelId = message.modelId ?: return null
        return modelOptions
            .firstOrNull { it.providerId == providerId && it.modelId == modelId }
            ?.contextLimitTokens
    }

    private fun isCurrentBinding(
        sessionId: String,
        worktree: String,
    ): Boolean = boundSessionId == sessionId && boundWorktree == worktree

    private fun renameErrorMessage(throwable: Throwable): String =
        when (throwable) {
            is SessionError.Http -> throwable.message ?: "Request failed"
            is SessionError.Transport -> "Unable to reach the server"
            is SessionError.Decoding -> "Unable to read server response"
            else -> throwable.message ?: "Something went wrong"
        }

    private fun sessionErrorMessage(properties: JsonObject?): String {
        val error = properties?.get("error") as? JsonObject
        val data = error?.get("data") as? JsonObject
        return sequenceOf(
            (data?.get("message") as? JsonPrimitive)?.contentOrNull,
            (error?.get("message") as? JsonPrimitive)?.contentOrNull,
            (properties?.get("message") as? JsonPrimitive)?.contentOrNull,
            (error?.get("name") as? JsonPrimitive)?.contentOrNull,
        ).firstOrNull { !it.isNullOrBlank() } ?: "Session work failed"
    }

    private fun logI(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun logD(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private fun logW(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    fun clearTransientErrors() {
        _state.update { it.copy(errorMessage = null, lifecycleErrorMessage = null) }
    }

    companion object {
        private const val TAG = "SessionInfo"
        private const val EMPTY_TITLE_ERROR = "Enter a session title"
        const val ARCHIVE_ERROR = "Couldn't archive session."
        const val DELETE_ERROR = "Couldn't delete session."
    }
}
