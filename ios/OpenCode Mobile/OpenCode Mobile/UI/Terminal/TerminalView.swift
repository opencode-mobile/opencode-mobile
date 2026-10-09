import Factory
import SwiftUI

struct TerminalRoute: Hashable {
    let worktree: String
    let projectTitle: String
    var ptyId: String? = nil
}

struct TerminalView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.colorScheme) private var colorScheme
    @State private var viewModel: TerminalViewModel
    private let worktree: String
    private let projectTitle: String

    init(worktree: String, projectTitle: String, ptyId: String? = nil, viewModel: TerminalViewModel? = nil) {
        self.worktree = worktree
        self.projectTitle = projectTitle
        _viewModel = State(
            initialValue: viewModel ?? Container.makeTerminalViewModel(worktree: worktree, ptyId: ptyId)
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "Terminal",
                subtitle: projectTitle,
                subtitleLeading: .letter(
                    letterCharacter(for: projectTitle),
                    letterAccent(for: worktree)
                ),
                leading: .back(action: { dismiss() }),
                showDivider: true
            )
            content
                .openCodeReadableColumn()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(OpenCodeColors.backgroundBase)
        .toolbar(.hidden, for: .navigationBar)
        .task {
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.terminal,
                presentation: ProductAnalytics.Presentation.route
            )
            await viewModel.appear()
        }
        .onDisappear {
            viewModel.disappear()
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active:
                Task { await viewModel.appear() }
            case .inactive, .background:
                viewModel.disappear()
            @unknown default:
                break
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch viewModel.state {
        case .connecting:
            OpenCodeStatusStack(title: "Connecting", subtitle: "Starting shell…")
        case .live, .reconnecting:
            livePane
        case .connectFailed:
            errorContent(
                title: "Connect failed",
                message: "Could not start a shell for this project."
            )
        case .exited:
            errorContent(title: "Shell ended", message: "Shell ended")
        }
    }

    private var livePane: some View {
        let tokens = ThemeManager.shared.tokens(for: colorScheme)
        return VStack(spacing: 0) {
            TerminalEmulatorView(
                viewModel: viewModel,
                backgroundHex: tokens["background-base"],
                foregroundHex: tokens["text-strong"],
                onData: emit,
                onResize: { cols, rows in
                    viewModel.resize(cols: cols, rows: rows)
                }
            )
            TerminalControlStrip(onSend: emit)
        }
    }

    private func emit(_ text: String) {
        viewModel.sendInput(text)
    }

    private func errorContent(title: String, message: String) -> some View {
        OpenCodeEmptyState(
            title: title,
            message: message,
            primaryAction: OpenCodeEmptyStateAction(title: "Retry") {
                Task { await viewModel.retry() }
            }
        )
    }
}
