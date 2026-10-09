import SwiftUI

enum OpenCodeReviewChangesPillVariant {
    case latestTurn
}

enum ReviewChangesPillPreviewCase: CaseIterable {
    case mixedLight
    case mixedDark
    case additionsOnlyLight
    case deletionsOnlyDark
    case zeroLineLight
    case singularDark
    case disabledLight
    case disabledDark

    private var configuration: (files: Int, additions: Int, deletions: Int, enabled: Bool, colorScheme: ColorScheme) {
        switch self {
        case .mixedLight: (4, 12, 3, true, .light)
        case .mixedDark: (4, 12, 3, true, .dark)
        case .additionsOnlyLight: (4, 12, 0, true, .light)
        case .deletionsOnlyDark: (4, 0, 3, true, .dark)
        case .zeroLineLight: (4, 0, 0, true, .light)
        case .singularDark: (1, 1, 1, true, .dark)
        case .disabledLight: (4, 12, 3, false, .light)
        case .disabledDark: (4, 0, 0, false, .dark)
        }
    }

    var changedFileCount: Int { configuration.files }
    var additions: Int { configuration.additions }
    var deletions: Int { configuration.deletions }
    var enabled: Bool { configuration.enabled }
    var colorScheme: ColorScheme { configuration.colorScheme }
}

struct OpenCodeReviewChangesPill: View {
    let variant: OpenCodeReviewChangesPillVariant
    let changedFileCount: Int
    let additions: Int
    let deletions: Int
    let enabled: Bool
    let action: () -> Void

    init(
        variant: OpenCodeReviewChangesPillVariant,
        changedFileCount: Int,
        additions: Int,
        deletions: Int,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) {
        precondition(changedFileCount > 0, "Review Changes Pill requires a positive file count")
        precondition(additions >= 0, "Review Changes Pill requires nonnegative additions")
        precondition(deletions >= 0, "Review Changes Pill requires nonnegative deletions")
        self.variant = variant
        self.changedFileCount = changedFileCount
        self.additions = additions
        self.deletions = deletions
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        let summary = switch variant {
        case .latestTurn:
            Self.makeSummary(
                changedFileCount: changedFileCount,
                additions: additions,
                deletions: deletions
            )
        }
        Button(action: action) {
            summaryText(summary)
                .font(OpenCodeTypography.button)
                .padding(.horizontal, Self.paddingHorizontal)
                .padding(.vertical, Self.paddingVertical)
                .frame(minHeight: Self.minHeight)
                .contentShape(Capsule())
        }
        .buttonStyle(OpenCodeFloatingPillPressStyle())
        .openCodeFloatingPillChrome(enabled: enabled)
        .disabled(!enabled)
        .accessibilityLabel(summary.accessibilityLabel)
    }

    static func fileLabel(changedFileCount: Int) -> String {
        "\(changedFileCount) \(changedFileCount == 1 ? "file" : "files")"
    }

    static func visibleSummary(changedFileCount: Int, additions: Int, deletions: Int) -> String {
        makeSummary(changedFileCount: changedFileCount, additions: additions, deletions: deletions).visibleLabel
    }

    static func accessibilityLabel(changedFileCount: Int, additions: Int, deletions: Int) -> String {
        makeSummary(changedFileCount: changedFileCount, additions: additions, deletions: deletions).accessibilityLabel
    }

    private static func makeSummary(changedFileCount: Int, additions: Int, deletions: Int) -> ReviewChangesPillSummary {
        ReviewChangesPillSummary(
            fileLabel: fileLabel(changedFileCount: changedFileCount),
            additionLabel: additions > 0 ? "+\(additions)" : nil,
            deletionLabel: deletions > 0 ? "−\(deletions)" : nil,
            additionAccessibilityLabel: additions > 0 ? "\(additions) \(additions == 1 ? "addition" : "additions")" : nil,
            deletionAccessibilityLabel: deletions > 0 ? "\(deletions) \(deletions == 1 ? "deletion" : "deletions")" : nil
        )
    }

    private func summaryText(_ summary: ReviewChangesPillSummary) -> Text {
        let disabledColor = OpenCodeColors.textBase
        var text = Text(summary.fileLabel)
            .foregroundColor(enabled ? OpenCodeColors.textStrong : disabledColor)
        let counts = OpenCodeDiffStat.text(
            additions: additions > 0 ? additions : nil,
            deletions: deletions > 0 ? deletions : nil,
            enabled: enabled,
            separator: .wide
        )
        if summary.additionLabel != nil || summary.deletionLabel != nil {
            text = text + Text("  ") + counts
        }
        return text
    }

    private static let minHeight: CGFloat = 36
    private static let paddingHorizontal: CGFloat = 14
    private static let paddingVertical: CGFloat = 8
}

private struct ReviewChangesPillSummary {
    let fileLabel: String
    let additionLabel: String?
    let deletionLabel: String?
    let additionAccessibilityLabel: String?
    let deletionAccessibilityLabel: String?

    var visibleLabel: String {
        [fileLabel, additionLabel, deletionLabel].compactMap { $0 }.joined(separator: "  ")
    }

    var accessibilityLabel: String {
        ["Review changes", fileLabel, additionAccessibilityLabel, deletionAccessibilityLabel]
            .compactMap { $0 }
            .joined(separator: ", ")
    }
}

private struct ReviewChangesPillPreview: View {
    let previewCase: ReviewChangesPillPreviewCase

    var body: some View {
        OpenCodeReviewChangesPill(
            variant: .latestTurn,
            changedFileCount: previewCase.changedFileCount,
            additions: previewCase.additions,
            deletions: previewCase.deletions,
            enabled: previewCase.enabled,
            action: {}
        )
        .padding()
        .openCodeTheme()
        .preferredColorScheme(previewCase.colorScheme)
    }
}

#Preview("Mixed Light") {
    ReviewChangesPillPreview(previewCase: .mixedLight)
}

#Preview("Mixed Dark") {
    ReviewChangesPillPreview(previewCase: .mixedDark)
}

#Preview("Additions Only Light") {
    ReviewChangesPillPreview(previewCase: .additionsOnlyLight)
}

#Preview("Deletions Only Dark") {
    ReviewChangesPillPreview(previewCase: .deletionsOnlyDark)
}

#Preview("Zero Line Light") {
    ReviewChangesPillPreview(previewCase: .zeroLineLight)
}

#Preview("Singular Dark") {
    ReviewChangesPillPreview(previewCase: .singularDark)
}

#Preview("Disabled Light") {
    ReviewChangesPillPreview(previewCase: .disabledLight)
}

#Preview("Disabled Dark") {
    ReviewChangesPillPreview(previewCase: .disabledDark)
}
