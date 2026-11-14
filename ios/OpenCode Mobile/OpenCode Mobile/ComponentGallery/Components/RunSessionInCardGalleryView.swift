import SwiftUI

struct RunSessionInCardGalleryView: View {
    @State private var compact = false

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Toggle(isOn: $compact) {
                Text(verbatim: "Keyboard up (compact)")
            }
            RunSessionInCard(
                caption: "Run session in",
                entries: [
                    .init(id: "project", row: RunSessionInRow(
                        tile: .letter("O", .violet), label: "Project", value: .verbatim("opencode-mobile"),
                        accessibilityIdentifier: "galleryRunSessionInProject"
                    ), action: {}),
                    .init(id: "worktree", row: RunSessionInRow(
                        tile: .icon(.add), label: "Worktree", value: "New worktree", highlighted: true,
                        accessibilityIdentifier: "galleryRunSessionInWorktree"
                    ), action: {}),
                    .init(id: "base", row: RunSessionInRow(
                        tile: .icon(.gitBranch), label: "Starts from", value: .verbatim("main"), monospacedValue: true,
                        accessibilityIdentifier: "galleryRunSessionInBase"
                    ), action: {}),
                ],
                footnote: "Created when you send",
                compact: compact
            )
            RunSessionInCard(
                caption: "Run session in",
                entries: [
                    .init(id: "project", row: RunSessionInRow(
                        tile: .letter("N", .teal), label: "Project", value: .verbatim("notes"),
                        accessibilityIdentifier: "galleryRunSessionInNotGitProject"
                    ), action: {}),
                    .init(id: "worktree", row: RunSessionInRow(
                        tile: .icon(.folder), label: "Worktree", value: "None · not a Git repo", enabled: false,
                        accessibilityIdentifier: "galleryRunSessionInNotGit"
                    ), action: {}),
                ],
                footnote: .verbatim("/Users/example/notes"),
                compact: compact
            )
        }
    }
}

#Preview {
    RunSessionInCardGalleryView().padding().openCodeTheme()
}
