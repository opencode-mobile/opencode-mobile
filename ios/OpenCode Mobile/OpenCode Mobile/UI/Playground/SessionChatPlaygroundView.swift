import SwiftUI

/// Developer Tools → Session Chat Playground: the real Session Chat screen over a
/// `PlaygroundWorld`, with a bar to pick a scenario and play, pause or restart its script.
struct SessionChatPlaygroundView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var opened: OpenedPlayground?
    @State private var isReady = false
    @State private var menuExpanded = false
    @State private var snackbar: OpenCodeSnackbarMessage?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let opened {
                    topBar(opened.session)
                    ZStack {
                        if isReady {
                            chat(opened.session).id(opened.generation)
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
                OpenCodeSnackbarHost(message: snackbar, onDismissed: { snackbar = nil })
            }
        }
        .task(id: opened?.generation) {
            if opened == nil {
                open(.everything)
                return
            }
            guard let session = opened?.session else { return }
            isReady = false
            await session.prepare()
            if !Task.isCancelled { isReady = true }
        }
        .onDisappear { opened?.session.close() }
    }

    private func topBar(_ session: PlaygroundSession) -> some View {
        let scenario = session.scenario
        let player = session.player
        return OpenCodeTopBarOverflowHost(isPresented: $menuExpanded, entries: menuEntries(current: scenario.id)) {
            OpenCodeTopBar(
                title: "Playground",
                subtitle: scenario.id.title + (scenario.isScripted ? " · \(playbackLabel(player))" : ""),
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
                action: restart
            ),
        ] + PlaygroundScenarioID.allCases.map { id in
            OpenCodeOverflowMenuEntry(
                id: id.rawValue,
                title: id.title,
                subtitle: id.summary,
                icon: id == current ? .check : nil,
                accessibilityIdentifier: "playgroundScenario.\(id.rawValue)",
                action: { open(id) }
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
        if player.isFinished { return [.label(title: "Restart", action: restart)] }
        if player.isPlaying { return [.label(title: "Pause", action: { player.pause() })] }
        return [.label(title: "Play", action: { player.play() })]
    }

    private func restart() {
        if let current = opened?.session.scenario.id { open(current) }
    }

    /// A fresh world and screen model for `id`; the previous one stops.
    private func open(_ id: PlaygroundScenarioID) {
        menuExpanded = false
        opened?.session.close()
        let session = PlaygroundSession(scenario: PlaygroundScenarios.build(id, now: PlaygroundWorld.systemNow()))
        session.world.onNotice = { text in snackbar = OpenCodeSnackbarMessage(text: text) }
        opened = OpenedPlayground(generation: (opened?.generation ?? 0) + 1, session: session)
    }
}

private struct OpenedPlayground {
    let generation: Int
    let session: PlaygroundSession
}
