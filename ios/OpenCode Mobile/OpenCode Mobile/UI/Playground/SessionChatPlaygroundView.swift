import Observation
import SwiftUI

/// What keeps the Session Chat Playground from reaching the real app while it is open: no
/// product analytics, and Session Chat display preferences kept in memory and restored after.
final class PlaygroundIsolation {
    private var analytics: ProductAnalytics.Suppression?
    private var preferences: SessionChatDisplayPreferences.Sandbox?

    init(preferences: SessionChatDisplayPreferences = .shared) {
        analytics = ProductAnalytics.suppress()
        self.preferences = preferences.beginSandbox()
    }

    /// Ends both; later calls do nothing.
    func end() {
        preferences?.end()
        preferences = nil
        analytics?.end()
        analytics = nil
    }
}

/// The playground while it is presented: the open scenario, its readiness and the snackbar.
/// Settings creates it when the playground opens and closes it when the cover is dismissed, so
/// a cover the chat presents over it (the image viewer) never tears the playground down.
@Observable
final class SessionChatPlaygroundModel: Identifiable {
    private(set) var session: PlaygroundSession?
    private(set) var generation = 0
    private(set) var isReady = false
    var snackbar: OpenCodeSnackbarMessage?
    /// How many steps each scenario's script has; zero for a still scenario.
    let stepCounts: [PlaygroundScenarioID: Int]

    @ObservationIgnored private var isolation: PlaygroundIsolation?
    @ObservationIgnored private var prepareTask: Task<Void, Never>?
    @ObservationIgnored private let now: () -> Int64

    init(now: @escaping () -> Int64 = PlaygroundWorld.systemNow, preferences: SessionChatDisplayPreferences = .shared) {
        self.now = now
        let builtAt = now()
        stepCounts = Dictionary(uniqueKeysWithValues: PlaygroundScenarioID.allCases.map { id in
            (id, PlaygroundScenarios.build(id, now: builtAt).steps.count)
        })
        isolation = PlaygroundIsolation(preferences: preferences)
        select(PlaygroundScenarioID.allCases[0])
    }

    var isClosed: Bool { isolation == nil }

    /// Opens `id` fresh, a new world and screen model; the previous one stops. A scripted
    /// scenario plays at once, at the speed the last one had.
    func select(_ id: PlaygroundScenarioID) {
        guard !isClosed else { return }
        prepareTask?.cancel()
        let fast = session?.player.progress.isFast ?? false
        session?.close()
        isReady = false
        let next = PlaygroundSession(scenario: PlaygroundScenarios.build(id, now: now()), now: now)
        next.player.setFast(fast)
        next.world.onNotice = { [weak self] text in self?.snackbar = OpenCodeSnackbarMessage(text: text) }
        session = next
        generation += 1
        prepareTask = Task { [weak self] in
            await next.prepare()
            guard !Task.isCancelled, let self, self.session === next else { return }
            self.isReady = true
        }
    }

    func restart() {
        if let current = session?.scenario.id { select(current) }
    }

    func selectPrevious() { select(neighbour(-1)) }

    func selectNext() { select(neighbour(1)) }

    private func neighbour(_ offset: Int) -> PlaygroundScenarioID {
        let all = PlaygroundScenarioID.allCases
        let index = session.flatMap { all.firstIndex(of: $0.scenario.id) } ?? 0
        return all[(index + offset + all.count) % all.count]
    }

    /// Stops the scenario and ends the isolation; later calls do nothing.
    func close() {
        prepareTask?.cancel()
        prepareTask = nil
        session?.close()
        isolation?.end()
        isolation = nil
    }
}

/// Developer Tools → Session Chat Playground: the real Session Chat screen over a
/// `PlaygroundWorld`. The chat title picks the scenario; a floating capsule plays its script.
struct SessionChatPlaygroundView: View {
    let model: SessionChatPlaygroundModel
    @State private var sheetVisible = false
    /// Kept across scenarios, so the capsule stays where it was put.
    @State private var capsule = PlaygroundCapsulePlacement()

    var body: some View {
        NavigationStack {
            ZStack {
                if let session = model.session {
                    if model.isReady {
                        chat(session).id(model.generation)
                    } else {
                        OpenCodeStatusStack(spinner: "Loading scenario")
                    }
                    if session.scenario.isScripted {
                        PlaygroundPlaybackCapsule(
                            code: session.scenario.id.code,
                            player: session.player,
                            idleLabel: session.scenario.hint ?? "Ready",
                            placement: $capsule,
                            onRestart: { model.restart() }
                        )
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(OpenCodeColors.backgroundBase)
            .toolbar(.hidden, for: .navigationBar)
            .overlay {
                OpenCodeSnackbarHost(message: model.snackbar, onDismissed: { model.snackbar = nil })
            }
            .playgroundScenarioSheet(
                isPresented: $sheetVisible,
                current: model.session?.scenario.id ?? PlaygroundScenarioID.allCases[0],
                stepCounts: model.stepCounts,
                onSelect: { id in
                    sheetVisible = false
                    model.select(id)
                },
                onPrevious: { model.selectPrevious() },
                onNext: { model.selectNext() }
            )
        }
    }

    private func chat(_ session: PlaygroundSession) -> some View {
        let bound = session.scenario.boundSession
        let directory = bound.directory ?? PlaygroundContent.worktree
        return SessionChatView(
            session: SessionListItem(
                id: bound.id,
                title: bound.title,
                worktree: directory,
                parentID: bound.parentID,
                directory: directory
            ),
            viewModel: session.chatViewModel,
            onUnavailableRoute: { session.world.notice($0) },
            titleMenu: SessionChatTitleMenu(title: session.scenario.id.title) { sheetVisible = true }
        )
    }
}
