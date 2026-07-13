import Factory
import SwiftUI

struct ChangesView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: ChangesViewModel

    init(sessionID: String, worktree: String, messageID: String?, viewModel: ChangesViewModel? = nil) {
        _viewModel = State(initialValue: viewModel ?? ChangesViewModel(
            sessionID: sessionID,
            worktree: worktree,
            messageID: messageID,
            repository: Container.shared.sessionRepository()
        ))
    }

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(title: "Changes", leading: .back(action: { dismiss() }), showDivider: true)
            content.frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(OpenCodeColors.backgroundBase)
        .toolbar(.hidden, for: .navigationBar)
        .task { await viewModel.load() }
    }

    @ViewBuilder private var content: some View {
        switch viewModel.state {
        case .loading:
            ProgressView("Loading changes…")
        case .noTurn:
            message("No completed turn", detail: "Send a prompt and wait for it to complete, then try again.")
        case .noChanges:
            message("No changes", detail: "The latest completed turn did not change any files.")
        case let .unavailablePatch(file):
            message(file.path, detail: "A text preview is unavailable for this file.")
        case let .content(file):
            ScrollView {
                OpenCodeUnifiedDiff(file: file).padding(16)
            }
        case let .error(error):
            VStack(spacing: 12) {
                message("Unable to load changes", detail: error)
                OpenCodeButton(variant: .compactText(title: "Retry"), contrast: .low) { Task { await viewModel.retry() } }
            }
        }
    }

    private func message(_ title: String, detail: String) -> some View {
        VStack(spacing: 8) {
            Text(title).font(OpenCodeTypography.heading)
            Text(detail).font(OpenCodeTypography.bodySecondary).foregroundStyle(OpenCodeColors.textBase).multilineTextAlignment(.center)
        }.padding(24)
    }
}
