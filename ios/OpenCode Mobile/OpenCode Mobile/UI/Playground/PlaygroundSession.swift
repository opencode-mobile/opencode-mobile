import Foundation
import Observation

/// One opened scenario: its world, the real `SessionChatViewModel` reading it through the
/// playground repositories, and its script. `close()` stops all of it.
final class PlaygroundSession {
    let scenario: PlaygroundScenario
    let world: PlaygroundWorld
    let player: PlaygroundPlayer
    let chatViewModel: SessionChatViewModel
    private let activeConnectionManager: ActiveConnectionManager
    private var tasks: [Task<Void, Never>] = []

    init(scenario: PlaygroundScenario, now: @escaping () -> Int64 = PlaygroundWorld.systemNow) {
        self.scenario = scenario
        let world = PlaygroundWorld(scenario.initial, now: now)
        self.world = world
        player = PlaygroundPlayer(world: world, steps: scenario.steps)

        let manager = ActiveConnectionManager(connectionProbing: PlaygroundConnectionProbing())
        manager.apply(playgroundProfile, serverProtocol: .v2)
        activeConnectionManager = manager

        let sessionRepository = PlaygroundSessionRepository(world: world)
        let ready = scenario.readySideQuestions
        let running = scenario.runningSideQuestions
        let readyAnswer = scenario.initial.sideAnswer
        // Questions the scenario starts with: ready ones answer at once, running ones never do.
        let sideQuestionStore = SideQuestionStore { sessionID, question in
            if ready.contains(question) { return readyAnswer }
            if running.contains(question) {
                while true { try await Task.sleep(for: .seconds(3_600)) }
            }
            return try await sessionRepository.askSideQuestion(sessionID: sessionID, question: question)
        }
        let agentRepository = PlaygroundAgentRepository()
        let selectionStore = PlaygroundModelSelectionStore()
        let catalogStore = ModelCatalogStore(
            modelRepository: PlaygroundModelRepository(),
            agentRepository: agentRepository,
            activeConnectionManager: manager
        )
        chatViewModel = SessionChatViewModel(
            sessionID: scenario.boundSessionID,
            sessionTitle: scenario.boundSession.title,
            worktree: PlaygroundContent.worktree,
            messageRepository: PlaygroundMessageRepository(world: world),
            modelCatalogStore: catalogStore,
            commandRepository: PlaygroundCommandRepository(world: world),
            agentRepository: agentRepository,
            sessionRepository: sessionRepository,
            permissionLiveStore: PlaygroundPermissionStore(world: world),
            questionLiveStore: PlaygroundQuestionStore(world: world),
            sessionTodoLiveStore: PlaygroundTodoStore(world: world),
            sessionReadyStore: EmptySessionReadyStore(),
            sessionStatusLiveStore: PlaygroundSessionStatusStore(world: world),
            foregroundEventStream: PlaygroundEventStream(),
            sessionModelSelectionStore: selectionStore,
            composerDraftStore: PlaygroundDraftStore(),
            activeConnectionManager: manager,
            connectionProfileStore: PlaygroundProfileStore(),
            newSessionModelSelectionResolver: NewSessionModelSelectionResolver(store: selectionStore, modelCatalogStore: catalogStore),
            sideQuestionStore: sideQuestionStore
        )
        for question in ready + running {
            _ = sideQuestionStore.ask(sessionID: scenario.boundSessionID, question: question)
        }
    }

    /// Reads the fake server's features, then starts the script. The chat must not bind before.
    func prepare() async {
        _ = try? await activeConnectionManager.probeActive()
        guard !Task.isCancelled else { return }
        if scenario.collapsesPermission {
            tasks.append(Task { @MainActor [chatViewModel] in
                for _ in 0..<100 {
                    if let request = chatViewModel.uiState.permissionRequest {
                        chatViewModel.hidePermissionDock(requestID: request.id)
                        return
                    }
                    try? await Task.sleep(for: .milliseconds(100))
                    if Task.isCancelled { return }
                }
            })
        }
        if scenario.isScripted { player.play() }
    }

    func close() {
        tasks.forEach { $0.cancel() }
        player.pause()
        world.close()
    }
}

/// Plays a scenario's steps in order; pausing keeps the next step, so play resumes there.
@Observable
final class PlaygroundPlayer {
    private(set) var isPlaying = false
    private(set) var nextStep = 0
    let stepCount: Int

    @ObservationIgnored private let world: PlaygroundWorld
    @ObservationIgnored private let steps: [PlaygroundStep]
    @ObservationIgnored private var task: Task<Void, Never>?

    var isFinished: Bool { nextStep >= stepCount }

    init(world: PlaygroundWorld, steps: [PlaygroundStep]) {
        self.world = world
        self.steps = steps
        stepCount = steps.count
    }

    func play() {
        guard task == nil, !isFinished else { return }
        isPlaying = true
        task = Task { @MainActor [weak self] in
            while let self, self.nextStep < self.steps.count {
                let step = self.steps[self.nextStep]
                try? await Task.sleep(for: .milliseconds(step.afterMs))
                if Task.isCancelled { return }
                self.world.update { step.apply($0, self.world.now()) }
                self.nextStep += 1
            }
            self?.isPlaying = false
            self?.task = nil
        }
    }

    func pause() {
        task?.cancel()
        task = nil
        isPlaying = false
    }
}
