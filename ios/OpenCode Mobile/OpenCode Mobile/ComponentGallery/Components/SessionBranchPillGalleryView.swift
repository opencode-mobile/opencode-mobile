import SwiftUI

struct SessionBranchPillGalleryView: View {
    @State private var sample = SessionBranchPillPreviewCase.changes
    @State private var copiedCount = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Interactive")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Tap the pill to open its popover. With changes it shows the line counts; without, the icon only. Worktree sessions use the worktree icon.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)

            HStack(spacing: 8) {
                ForEach(SessionBranchPillPreviewCase.allCases, id: \.self) { option in
                    OpenCodeChoiceBadge(label: .verbatim(option.galleryLabel), selected: sample == option) {
                        sample = option
                    }
                }
            }

            // As in the Session Chat top-left corner: the branch first, then todos.
            HStack(spacing: OpenCodeAnchoredCardLayout.gap) {
                OpenCodeSessionBranchPill(
                    summary: sample.summary,
                    onViewAllChanges: {},
                    onBranchNameCopied: { copiedCount += 1 }
                )
                OpenCodeFloatingPill(
                    layout: .iconLabel(icon: .list, label: "2/5"),
                    state: .ready,
                    accessibilityLabel: "Todos, 2 of 5 done",
                    action: {}
                )
                Spacer()
            }
            if copiedCount > 0 {
                Text("Branch name copied (\(copiedCount))")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeak)
            }

            Text("All pills")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
                .padding(.top, 8)
            HStack(spacing: 8) {
                ForEach(SessionBranchPillPreviewCase.allCases, id: \.self) { option in
                    OpenCodeSessionBranchPill(summary: option.summary, onViewAllChanges: {})
                }
            }

            Text("Popover open")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
                .padding(.top, 8)
            OpenCodeSessionBranchPopoverContent(summary: sample.summary, onViewAllChanges: {}, onCopyBranchName: {})
                .frame(maxWidth: OpenCodeSessionBranchPill.popoverWidth)
                .background(
                    OpenCodeColors.surfaceRaisedBase,
                    in: RoundedRectangle(cornerRadius: OpenCodeAnchoredCardLayout.cornerRadius, style: .continuous)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: OpenCodeAnchoredCardLayout.cornerRadius, style: .continuous)
                        .strokeBorder(OpenCodeColors.borderWeakBase)
                )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private extension SessionBranchPillPreviewCase {
    var galleryLabel: String {
        switch self {
        case .changes: "Changes"
        case .noChanges: "None"
        case .worktree: "Worktree"
        case .manyFiles: "Many"
        }
    }
}

#Preview {
    SessionBranchPillGalleryView()
        .padding()
        .openCodeTheme()
}
