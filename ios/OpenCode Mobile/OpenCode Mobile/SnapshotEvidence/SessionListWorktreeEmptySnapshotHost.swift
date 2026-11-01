import SwiftUI

/// Offline Home empty state for a worktree with no sessions, for on-demand snapshot evidence.
/// No Screen Model, no network. Renders the same `OpenCodeEmptyState` Session List shows.
struct SessionListWorktreeEmptySnapshotHost: View {
    let message: String

    init(message: String = SnapshotEvidenceMocks.worktreeEmptyMessage) {
        self.message = message
    }

    var body: some View {
        OpenCodeEmptyState(
            title: .verbatim(SessionListWorktreeEmptyCopy.title),
            message: .verbatim(message),
            icon: .callSplit,
            primaryAction: OpenCodeEmptyStateAction(title: .verbatim(SessionListWorktreeEmptyCopy.action)) {}
        )
        .frame(maxWidth: .infinity, minHeight: 420)
        .background(OpenCodeColors.backgroundBase)
    }
}
