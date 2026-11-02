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
    private let sideQuestionStore: SideQuestionStore
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
        self.sideQuestionStore = sideQuestionStore
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
            worktree: scenario.boundSession.directory ?? PlaygroundContent.worktree,
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
            sideQuestionStore: sideQuestionStore,
            // Playground images are data URLs, decoded on the device; there is no server to read files from.
            attachmentImageLoader: AttachmentImageLoader { _, _ in throw URLError(.notConnectedToInternet) },
            worktreeInventoryCache: Self.worktreeInventory()
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
        // Cancels the side questions still asking, such as Busy corner's one that never answers.
        sideQuestionStore.clearCache()
        world.close()
    }

    /// The fake project lists one worktree checkout, so a session bound there names it with the worktree icon.
    private static func worktreeInventory() -> WorktreeInventoryCache {
        let cache = WorktreeInventoryCache()
        let server = playgroundProfile.baseURL
        cache.setAddedPaths([PlaygroundContent.worktree], server: server)
        cache.record(
            .loaded([PlaygroundContent.worktree, PlaygroundContent.worktreeCheckout]),
            addedPath: PlaygroundContent.worktree,
            server: server
        )
        return cache
    }
}

/// Plays a scenario's steps in order, frame by frame. Pausing keeps the next frame and the time
/// still to wait for it, so Play resumes where the script stopped. `next()` finishes the step in
/// progress, or runs the next one at once. Fast play waits a quarter of each delay.
@Observable
final class PlaygroundPlayer {
    struct Progress: Equatable {
        var isPlaying = false
        /// Steps started so far: the finished ones plus the one streaming.
        var shownSteps = 0
        var stepCount = 0
        /// The last step started; nil before the first.
        var label: String?
        var isFinished = false
        var isFast = false

        /// How far the script is, 0 to 1.
        var fraction: Double { stepCount == 0 ? 0 : Double(shownSteps) / Double(stepCount) }
    }

    static let fastFactor: Int64 = 4

    private(set) var progress: Progress

    @ObservationIgnored private let world: PlaygroundWorld
    @ObservationIgnored private let steps: [PlaygroundStep]
    @ObservationIgnored private var task: Task<Void, Never>?
    /// The next frame to apply: `steps[stepIndex].frames[frameIndex]`.
    @ObservationIgnored private var stepIndex = 0
    @ObservationIgnored private var frameIndex = 0
    /// Still to wait for the next frame after a pause; nil means its full delay.
    @ObservationIgnored private var remainingMs: Int64?
    /// When the current wait started and how long it is; nil while not waiting.
    @ObservationIgnored private var waitStartedAtMs: Int64?
    @ObservationIgnored private var waitMs: Int64 = 0

    private var isFinished: Bool { stepIndex >= steps.count }

    /// What Play would wait before the next frame.
    var nextWaitMs: Int64? {
        guard !isFinished else { return nil }
        return remainingMs ?? scaled(steps[stepIndex].frames[frameIndex].afterMs)
    }

    init(world: PlaygroundWorld, steps: [PlaygroundStep]) {
        self.world = world
        self.steps = steps
        progress = Progress(stepCount: steps.count, isFinished: steps.isEmpty)
    }

    func play() {
        guard task == nil, !isFinished else { return }
        publish(isPlaying: true)
        task = Task { @MainActor [weak self] in
            while let self, !self.isFinished {
                let frame = self.steps[self.stepIndex].frames[self.frameIndex]
                self.waitMs = self.remainingMs ?? self.scaled(frame.afterMs)
                self.remainingMs = nil
                self.waitStartedAtMs = self.world.now()
                try? await Task.sleep(for: .milliseconds(self.waitMs))
                if Task.isCancelled { return }
                self.waitStartedAtMs = nil
                self.applyFrame()
            }
            self?.task = nil
            self?.publish(isPlaying: false)
        }
    }

    func pause() {
        if let startedAt = waitStartedAtMs {
            remainingMs = max(0, waitMs - (world.now() - startedAt))
        }
        waitStartedAtMs = nil
        task?.cancel()
        task = nil
        publish(isPlaying: false)
    }

    /// Finishes the step in progress, or runs the next step now; playback carries on as it was.
    func next() {
        guard !isFinished else { return }
        let wasPlaying = progress.isPlaying
        pause()
        remainingMs = nil
        repeat { applyFrame() } while frameIndex != 0
        if wasPlaying { play() }
    }

    /// Switches between 1× and 4×; a wait already running shrinks or grows to match.
    func setFast(_ fast: Bool) {
        guard fast != progress.isFast else { return }
        let wasPlaying = progress.isPlaying
        pause()
        remainingMs = remainingMs.map { fast ? $0 / Self.fastFactor : $0 * Self.fastFactor }
        progress.isFast = fast
        if wasPlaying { play() }
    }

    private func scaled(_ ms: Int64) -> Int64 { progress.isFast ? ms / Self.fastFactor : ms }

    private func applyFrame() {
        let step = steps[stepIndex]
        let frame = step.frames[frameIndex]
        world.update { frame.apply($0, world.now()) }
        frameIndex += 1
        if frameIndex >= step.frames.count {
            stepIndex += 1
            frameIndex = 0
        }
        publish(isPlaying: progress.isPlaying)
    }

    private func publish(isPlaying: Bool) {
        let shown = frameIndex > 0 ? stepIndex + 1 : stepIndex
        var next = progress
        next.isPlaying = isPlaying && !isFinished
        next.shownSteps = shown
        next.label = shown > 0 ? steps[shown - 1].label : nil
        next.isFinished = isFinished
        if next != progress { progress = next }
    }
}
