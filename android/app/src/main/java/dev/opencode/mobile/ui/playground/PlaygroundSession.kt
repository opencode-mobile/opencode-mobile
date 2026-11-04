package dev.opencode.mobile.ui.playground

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.SilentWorldSignals
import dev.opencode.mobile.domain.json.serverPayloadJson
import dev.opencode.mobile.domain.message.DisplayMessagePreparing
import dev.opencode.mobile.domain.model.ModelCatalogStore
import dev.opencode.mobile.domain.model.NewSessionModelSelectionResolver
import dev.opencode.mobile.domain.session.SideQuestionStore
import dev.opencode.mobile.domain.sessioncache.NoOpSessionCache
import dev.opencode.mobile.domain.sync.NoOpSyncCoordinator
import dev.opencode.mobile.domain.worktree.WorktreeInventory
import dev.opencode.mobile.domain.worktree.WorktreeInventoryCache
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.OpencodeSseHttpClient
import dev.opencode.mobile.ui.sessionchat.SessionChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One opened scenario: its world, the real [SessionChatViewModel] reading it through the
 * playground repositories, and its script. [close] stops all of it.
 */
class PlaygroundSession(
    val scenario: PlaygroundScenario,
    preparing: DisplayMessagePreparing,
    parentScope: CoroutineScope,
    nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    val world = PlaygroundWorld(scenario.initial, scope, nowMs)
    val player = PlaygroundPlayer(world, scenario.steps, scope)

    private val sessionRepository = PlaygroundSessionRepository(world)
    private val sideQuestionStore =
        SideQuestionStore(
            request = { sessionId, question ->
                // Questions the scenario starts with are answered before the chat watches, so they wait as ready.
                when (question) {
                    in scenario.readySideQuestions -> world.state.value.sideAnswer
                    in scenario.runningSideQuestions -> awaitCancellation()
                    else -> sessionRepository.askSideQuestion(sessionId, question)
                }
            },
            scope = scope,
        )
    private val profileStore = PlaygroundProfileStore()
    private val activeConnectionManager = ActiveConnectionManager(profileStore, PlaygroundConnectionProbing)
    private val viewModelStore = ViewModelStore()

    private val _ready = MutableStateFlow(false)

    /** True once the fake connection is bound; the chat must not bind before. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    val chatViewModel: SessionChatViewModel =
        ViewModelProvider.create(
            viewModelStore,
            viewModelFactory { initializer { createChatViewModel(preparing) } },
        )[SessionChatViewModel::class]

    init {
        (scenario.readySideQuestions + scenario.runningSideQuestions).forEach { sideQuestionStore.ask(scenario.boundSessionId, it) }
        scope.launch {
            activeConnectionManager.applyProfile(playgroundProfile)
            activeConnectionManager.probeActive()
            _ready.value = true
            if (scenario.isScripted) player.play()
        }
        if (scenario.collapsesPermission) {
            scope.launch {
                val request = chatViewModel.uiState.first { it.permissionRequest != null }.permissionRequest ?: return@launch
                chatViewModel.hidePermissionDock(request.id)
            }
        }
    }

    fun close() {
        player.pause()
        sideQuestionStore.clear()
        scope.cancel()
        viewModelStore.clear()
    }

    private fun createChatViewModel(preparing: DisplayMessagePreparing): SessionChatViewModel {
        val json = serverPayloadJson()
        val eventStream = PlaygroundEventStream()
        val modelSelectionStore = PlaygroundModelSelectionStore()
        val draftStore = PlaygroundDraftStore()
        val agentRepository = PlaygroundAgentRepository()
        val modelCatalogStore =
            ModelCatalogStore(
                modelRepository = PlaygroundModelRepository(),
                agentRepository = agentRepository,
                currentProfile = activeConnectionManager::currentProfile,
                scope = scope,
            )
        // Built only because the screen model asks it for the current profile; the playground never connects.
        val connectionSessionCoordinator =
            ConnectionSessionCoordinator(
                activeConnectionManager = activeConnectionManager,
                connectionProfileStore = profileStore,
                secureCredentialStore = PlaygroundCredentialStore,
                sessionModelSelectionStore = modelSelectionStore,
                composerDraftStore = draftStore,
                sessionCache = NoOpSessionCache,
                feedbackIdentityStore = PlaygroundFeedbackIdentityStore,
                httpClient = OpencodeHttpClient(json, activeConnectionManager),
                sseHttpClient = OpencodeSseHttpClient(json, activeConnectionManager),
                eventStream = eventStream,
                syncCoordinator = NoOpSyncCoordinator,
                worldSignals = SilentWorldSignals,
                projectionCaches = PlaygroundProjectionCaches,
            )
        return SessionChatViewModel(
            messageRepository = PlaygroundMessageRepository(world, preparing),
            modelCatalogStore = modelCatalogStore,
            commandRepository = PlaygroundCommandRepository(world),
            agentRepository = agentRepository,
            sessionRepository = sessionRepository,
            permissionLiveStore = PlaygroundPermissionStore(world),
            questionLiveStore = PlaygroundQuestionStore(world),
            sessionTodoLiveStore = PlaygroundTodoStore(world),
            sessionStatusLiveStore = PlaygroundSessionStatusStore(world),
            foregroundEventStream = eventStream,
            eventDomainAdapter = EventDomainAdapter(json),
            connectionSessionCoordinator = connectionSessionCoordinator,
            sessionModelSelectionStore = modelSelectionStore,
            composerDraftStore = draftStore,
            projectFilesRepository = dev.opencode.mobile.ui.sessionchat.EmptyProjectFilesRepository,
            newSessionModelSelectionResolver = NewSessionModelSelectionResolver(modelSelectionStore, modelCatalogStore),
            connectionProfileStore = profileStore,
            activeConnectionManager = activeConnectionManager,
            sideQuestionStore = sideQuestionStore,
            worktreeInventoryCache = playgroundWorktreeInventory(),
        )
    }

    /** The fake project lists one worktree checkout, so a session bound there names it with the worktree icon. */
    private fun playgroundWorktreeInventory() =
        WorktreeInventoryCache().apply {
            val server = playgroundProfile.baseUrl
            setAddedPaths(listOf(PlaygroundContent.WORKTREE), server)
            record(
                WorktreeInventory.loaded(listOf(PlaygroundContent.WORKTREE, PlaygroundContent.WORKTREE_CHECKOUT)),
                PlaygroundContent.WORKTREE,
                server,
            )
        }
}

/**
 * Plays a scenario's steps in order, frame by frame. Pausing keeps the next frame and the time
 * still to wait for it, so Play resumes where the script stopped. [next] finishes the step in
 * progress, or runs the next one at once. Fast play waits a quarter of each delay.
 */
class PlaygroundPlayer(
    private val world: PlaygroundWorld,
    private val steps: List<PlaygroundStep>,
    private val scope: CoroutineScope,
) {
    data class Progress(
        val isPlaying: Boolean = false,
        /** Steps started so far: the finished ones plus the one streaming. */
        val shownSteps: Int = 0,
        val stepCount: Int = 0,
        /** The last step started; null before the first. */
        val label: String? = null,
        val isFinished: Boolean = false,
        val isFast: Boolean = false,
    ) {
        /** How far the script is, 0 to 1. */
        val fraction: Float get() = if (stepCount == 0) 0f else shownSteps.toFloat() / stepCount
    }

    private val _progress = MutableStateFlow(Progress(stepCount = steps.size, isFinished = steps.isEmpty()))
    val progress: StateFlow<Progress> = _progress.asStateFlow()
    private var job: Job? = null

    /** The next frame to apply: `steps[stepIndex].frames[frameIndex]`. */
    private var stepIndex = 0
    private var frameIndex = 0

    /** Still to wait for the next frame after a pause; null means its full delay. */
    private var remainingMs: Long? = null

    /** When the current wait started and how long it is; null while not waiting. */
    private var waitStartedAtMs: Long? = null
    private var waitMs = 0L

    private val isFinished: Boolean get() = stepIndex >= steps.size

    fun play() {
        if (job?.isActive == true || isFinished) return
        publish(isPlaying = true)
        job =
            scope.launch {
                while (!isFinished) {
                    val frame = steps[stepIndex].frames[frameIndex]
                    waitMs = remainingMs ?: scaled(frame.afterMs)
                    remainingMs = null
                    waitStartedAtMs = world.nowMs()
                    delay(waitMs)
                    waitStartedAtMs = null
                    applyFrame()
                }
                publish(isPlaying = false)
            }
    }

    fun pause() {
        waitStartedAtMs?.let { startedAt ->
            remainingMs = (waitMs - (world.nowMs() - startedAt)).coerceAtLeast(0)
        }
        waitStartedAtMs = null
        job?.cancel()
        job = null
        publish(isPlaying = false)
    }

    /** Finishes the step in progress, or runs the next step now; playback carries on as it was. */
    fun next() {
        if (isFinished) return
        val wasPlaying = _progress.value.isPlaying
        pause()
        remainingMs = null
        do applyFrame() while (frameIndex != 0)
        if (wasPlaying) play()
    }

    /** Switches between 1× and 4×; a wait already running shrinks or grows to match. */
    fun setFast(fast: Boolean) {
        if (fast == _progress.value.isFast) return
        val wasPlaying = _progress.value.isPlaying
        pause()
        remainingMs = remainingMs?.let { if (fast) it / FAST_FACTOR else it * FAST_FACTOR }
        _progress.update { it.copy(isFast = fast) }
        if (wasPlaying) play()
    }

    private fun scaled(ms: Long): Long = if (_progress.value.isFast) ms / FAST_FACTOR else ms

    private fun applyFrame() {
        val step = steps[stepIndex]
        val frame = step.frames[frameIndex]
        world.update { frame.apply(it, world.nowMs()) }
        frameIndex += 1
        if (frameIndex >= step.frames.size) {
            stepIndex += 1
            frameIndex = 0
        }
        publish(isPlaying = _progress.value.isPlaying)
    }

    private fun publish(isPlaying: Boolean) {
        val shown = if (frameIndex > 0) stepIndex + 1 else stepIndex
        _progress.update {
            it.copy(
                isPlaying = isPlaying && !isFinished,
                shownSteps = shown,
                label = steps.getOrNull(shown - 1)?.label,
                isFinished = isFinished,
            )
        }
    }

    companion object {
        const val FAST_FACTOR = 4
    }
}
