import SwiftUI

struct ReviewChangesPillGalleryView: View {
    @State private var enabled = true
    @State private var summary = ReviewChangesPillGallerySummary.mixed

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Interactive")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Review mixed, additions-only, deletions-only, zero-line, singular, plural, and disabled states.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)

            HStack(spacing: 8) {
                OpenCodeChoiceBadge(label: "Mixed", selected: summary == .mixed) {
                    summary = .mixed
                }
                OpenCodeChoiceBadge(label: "+ only", selected: summary == .additionsOnly) {
                    summary = .additionsOnly
                }
                OpenCodeChoiceBadge(label: "− only", selected: summary == .deletionsOnly) {
                    summary = .deletionsOnly
                }
            }

            HStack(spacing: 8) {
                OpenCodeChoiceBadge(label: "Zero lines", selected: summary == .zeroLine) {
                    summary = .zeroLine
                }
                OpenCodeChoiceBadge(label: "1 file", selected: summary == .singular) {
                    summary = .singular
                }
                OpenCodeChoiceBadge(label: "Enabled", selected: enabled) { enabled.toggle() }
            }

            HStack {
                Spacer()
                OpenCodeReviewChangesPill(
                    variant: .latestTurn,
                    changedFileCount: summary.changedFileCount,
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

private enum ReviewChangesPillGallerySummary {
    case mixed
    case additionsOnly
    case deletionsOnly
    case zeroLine
    case singular

    private var summary: (files: Int, additions: Int, deletions: Int) {
        switch self {
        case .mixed: (5, 12, 3)
        case .additionsOnly: (5, 12, 0)
        case .deletionsOnly: (5, 0, 3)
        case .zeroLine: (5, 0, 0)
        case .singular: (1, 1, 1)
        }
    }

    var changedFileCount: Int { summary.files }
    var additions: Int { summary.additions }
    var deletions: Int { summary.deletions }
}

#Preview {
    ReviewChangesPillGalleryView()
        .padding()
        .openCodeTheme()
}
