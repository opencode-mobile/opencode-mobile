import SwiftUI

struct SessionBranchPillGalleryView: View {
    @State private var enabled = true
    @State private var summary = SessionBranchPillGallerySummary.mixedDirty

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Interactive")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Review mixed dirty, additions-only, deletions-only, clean, long-branch, and disabled states.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)

            HStack(spacing: 8) {
                OpenCodeChoiceBadge(label: "Mixed", selected: summary == .mixedDirty) {
                    summary = .mixedDirty
                }
                OpenCodeChoiceBadge(label: "+ only", selected: summary == .additionsOnly) {
                    summary = .additionsOnly
                }
                OpenCodeChoiceBadge(label: "− only", selected: summary == .deletionsOnly) {
                    summary = .deletionsOnly
                }
            }

            HStack(spacing: 8) {
                OpenCodeChoiceBadge(label: "Clean", selected: summary == .clean) {
                    summary = .clean
                }
                OpenCodeChoiceBadge(label: "Long", selected: summary == .longBranch) {
                    summary = .longBranch
                }
                OpenCodeChoiceBadge(label: "Enabled", selected: enabled) { enabled.toggle() }
            }

            HStack {
                Spacer()
                OpenCodeSessionBranchPill(
                    branch: summary.branch,
                    additions: summary.additions,
                    deletions: summary.deletions,
                    enabled: enabled,
                    action: {}
                )
                Spacer()
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private enum SessionBranchPillGallerySummary {
    case mixedDirty
    case additionsOnly
    case deletionsOnly
    case clean
    case longBranch

    private var summary: (branch: String, additions: Int, deletions: Int) {
        switch self {
        case .mixedDirty: ("fix/probe", 12, 3)
        case .additionsOnly: ("fix/probe", 12, 0)
        case .deletionsOnly: ("fix/probe", 0, 3)
        case .clean: ("main", 0, 0)
        case .longBranch: (SessionBranchPillPreviewCase.longBranch, 4, 1)
        }
    }

    var branch: String { summary.branch }
    var additions: Int { summary.additions }
    var deletions: Int { summary.deletions }
}

#Preview {
    SessionBranchPillGalleryView()
        .padding()
        .openCodeTheme()
}
