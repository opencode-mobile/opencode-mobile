package dev.opencode.mobile.ui.sessionchat.region

import android.util.Log
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.model.ModelSelection
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionInfoRegionState
import dev.opencode.mobile.ui.sessionchat.toSessionConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    fun bind(
        sessionId: String,
        sessionTitle: String,
        worktree: String,
    ) {
        if (boundSessionId == sessionId &&
            boundSessionTitle == sessionTitle &&
            boundWorktree == worktree
        ) {
            return
        }
        stopObserving()
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
                            _state.update {
                                it.copy(connectionStatus = connectionState.toSessionConnectionStatus())
                            }
                        }
                    }
                    launch {
                        foregroundEventStream.events.collect { event ->
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
                        }
                    }
                }
            }
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

    private fun logI(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun logD(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    companion object {
        private const val TAG = "SessionInfo"
    }
}
