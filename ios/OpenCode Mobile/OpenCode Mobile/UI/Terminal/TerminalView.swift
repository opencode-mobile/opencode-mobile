import Factory
import SwiftUI

struct TerminalRoute: Hashable {
    let worktree: String
    let projectTitle: String
}

struct TerminalView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.colorScheme) private var colorScheme
    @State private var viewModel: TerminalViewModel
    @State private var ctrlSticky = false
    private let worktree: String
    private let projectTitle: String

    init(worktree: String, projectTitle: String, viewModel: TerminalViewModel? = nil) {
        self.worktree = worktree
        self.projectTitle = projectTitle
        _viewModel = State(
            initialValue: viewModel ?? Container.makeTerminalViewModel(worktree: worktree)
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
            TerminalControlStrip(
                ctrlSticky: ctrlSticky,
                onToggleCtrl: { ctrlSticky.toggle() },
                onSend: emit
            )
        }
    }

    private func emit(_ text: String) {
        let (payload, nextSticky) = applyTerminalCtrl(text, ctrlSticky: ctrlSticky)
        ctrlSticky = nextSticky
        viewModel.sendInput(payload)
    }

    private func errorContent(title: String, message: String) -> some View {
        VStack(spacing: 16) {
            OpenCodeEmptyState(title: title, message: message)
                .fixedSize(horizontal: false, vertical: true)
            OpenCodeButton(variant: .fullWidthText(title: "Retry")) {
                Task { await viewModel.retry() }
            }
            .padding(.horizontal, 24)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
