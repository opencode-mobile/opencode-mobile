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

    @ObservationIgnored private var isolation: PlaygroundIsolation?
    @ObservationIgnored private var prepareTask: Task<Void, Never>?
    @ObservationIgnored private let now: () -> Int64

    init(now: @escaping () -> Int64 = PlaygroundWorld.systemNow, preferences: SessionChatDisplayPreferences = .shared) {
        self.now = now
        isolation = PlaygroundIsolation(preferences: preferences)
        open(.everything)
    }

    var isClosed: Bool { isolation == nil }

    /// A fresh world and screen model for `id`; the previous one stops.
    func open(_ id: PlaygroundScenarioID) {
        guard !isClosed else { return }
        prepareTask?.cancel()
        session?.close()
        isReady = false
        let next = PlaygroundSession(scenario: PlaygroundScenarios.build(id, now: now()), now: now)
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
        if let current = session?.scenario.id { open(current) }
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
/// `PlaygroundWorld`, with a bar to pick a scenario and play, pause or restart its script.
struct SessionChatPlaygroundView: View {
    let model: SessionChatPlaygroundModel
    @Environment(\.dismiss) private var dismiss
    @State private var menuExpanded = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let session = model.session {
                    topBar(session)
                    ZStack {
                        if model.isReady {
                            chat(session).id(model.generation)
                        } else {
                            OpenCodeStatusStack(spinner: "Loading scenario")
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .background(OpenCodeColors.backgroundBase)
            .toolbar(.hidden, for: .navigationBar)
            .overlay {
                OpenCodeSnackbarHost(message: model.snackbar, onDismissed: { model.snackbar = nil })
            }
        }
    }

    private func topBar(_ session: PlaygroundSession) -> some View {
        let scenario = session.scenario
        let player = session.player
        return OpenCodeTopBarOverflowHost(isPresented: $menuExpanded, entries: menuEntries(current: scenario.id)) {
            OpenCodeTopBar(
                title: "Playground",
                subtitle: .verbatim(scenario.id.title + (scenario.isScripted ? " · \(playbackLabel(player))" : "")),
                leading: .back(action: { dismiss() }),
                actions: playbackActions(player, scripted: scenario.isScripted) + [
                    .icon(.moreVert, accessibilityLabel: "Scenarios", action: { menuExpanded = true }),
                ],
                showDivider: true
            )
        }
    }

    private func chat(_ session: PlaygroundSession) -> some View {
        let bound = session.scenario.boundSession
        return SessionChatView(
            session: SessionListItem(
                id: bound.id,
                title: bound.title,
                worktree: PlaygroundContent.worktree,
                parentID: bound.parentID,
                directory: PlaygroundContent.worktree
            ),
            viewModel: session.chatViewModel,
            onUnavailableRoute: { session.world.notice($0) }
        )
    }

    private func menuEntries(current: PlaygroundScenarioID) -> [OpenCodeOverflowMenuEntry] {
        [
            OpenCodeOverflowMenuEntry(
                id: "restart",
                title: "Restart scenario",
                icon: .redo,
                accessibilityIdentifier: "playgroundRestart",
                action: {
                    menuExpanded = false
                    model.restart()
                }
            ),
        ] + PlaygroundScenarioID.allCases.map { id in
            OpenCodeOverflowMenuEntry(
                id: id.rawValue,
                title: .verbatim(id.title),
                subtitle: .verbatim(id.summary),
                icon: id == current ? .check : nil,
                accessibilityIdentifier: "playgroundScenario.\(id.rawValue)",
                action: {
                    menuExpanded = false
                    model.open(id)
                }
            )
        }
    }

    private func playbackLabel(_ player: PlaygroundPlayer) -> String {
        if player.isFinished { return "finished" }
        if player.isPlaying { return "step \(player.nextStep + 1) of \(player.stepCount)" }
        return "paused"
    }

    private func playbackActions(_ player: PlaygroundPlayer, scripted: Bool) -> [OpenCodeTopBarAction] {
        guard scripted else { return [] }
        if player.isFinished { return [.label(title: "Restart", action: { model.restart() })] }
        if player.isPlaying { return [.label(title: "Pause", action: { player.pause() })] }
        return [.label(title: "Play", action: { player.play() })]
    }
}
