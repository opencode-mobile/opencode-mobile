package dev.opencode.mobile.ui.playground

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.SilentWorldSignals
import dev.opencode.mobile.domain.message.DisplayMessagePreparing
import dev.opencode.mobile.domain.model.ModelCatalogStore
import dev.opencode.mobile.domain.model.NewSessionModelSelectionResolver
import dev.opencode.mobile.domain.session.SideQuestionStore
import dev.opencode.mobile.domain.sessioncache.NoOpSessionCache
import dev.opencode.mobile.domain.sync.NoOpSyncCoordinator
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
import kotlinx.serialization.json.Json

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
        scope.cancel()
        viewModelStore.clear()
    }

    private fun createChatViewModel(preparing: DisplayMessagePreparing): SessionChatViewModel {
        val json = Json { ignoreUnknownKeys = true }
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
        )
    }
}

/** Plays a scenario's steps in order; pausing keeps the next step, so play resumes there. */
class PlaygroundPlayer(
    private val world: PlaygroundWorld,
    private val steps: List<PlaygroundStep>,
    private val scope: CoroutineScope,
) {
    data class Progress(
        val isPlaying: Boolean = false,
        val nextStep: Int = 0,
        val stepCount: Int = 0,
    ) {
        val isFinished: Boolean get() = nextStep >= stepCount
    }

    private val _progress = MutableStateFlow(Progress(stepCount = steps.size))
    val progress: StateFlow<Progress> = _progress.asStateFlow()
    private var job: Job? = null

    fun play() {
        if (job?.isActive == true || _progress.value.isFinished) return
        _progress.update { it.copy(isPlaying = true) }
        job =
            scope.launch {
                while (true) {
                    val index = _progress.value.nextStep
                    val step = steps.getOrNull(index) ?: break
                    delay(step.afterMs)
                    world.update { step.apply(it, world.nowMs()) }
                    _progress.update { it.copy(nextStep = index + 1) }
                }
                _progress.update { it.copy(isPlaying = false) }
            }
    }

    fun pause() {
        job?.cancel()
        job = null
        _progress.update { it.copy(isPlaying = false) }
    }
}
