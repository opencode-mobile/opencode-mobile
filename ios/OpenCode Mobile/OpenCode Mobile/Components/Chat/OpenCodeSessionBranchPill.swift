import SwiftUI

enum SessionBranchPillPreviewCase: CaseIterable {
    case mixedDirtyLight
    case additionsOnlyLight
    case deletionsOnlyDark
    case cleanLight
    case longBranchDark
    case disabledLight

    private var configuration: (branch: String, additions: Int, deletions: Int, enabled: Bool, colorScheme: ColorScheme) {
        switch self {
        case .mixedDirtyLight: ("fix/probe", 12, 3, true, .light)
        case .additionsOnlyLight: ("fix/probe", 12, 0, true, .light)
        case .deletionsOnlyDark: ("fix/probe", 0, 3, true, .dark)
        case .cleanLight: ("main", 0, 0, true, .light)
        case .longBranchDark: (Self.longBranch, 4, 1, true, .dark)
        case .disabledLight: ("fix/probe", 12, 3, false, .light)
        }
    }

    var branch: String { configuration.branch }
    var additions: Int { configuration.additions }
    var deletions: Int { configuration.deletions }
    var enabled: Bool { configuration.enabled }
    var colorScheme: ColorScheme { configuration.colorScheme }

    static let longBranch = "feature/ope-48-session-branch-pill-with-a-very-long-name"
}

struct OpenCodeSessionBranchPill: View {
    let branch: String
    let additions: Int
    let deletions: Int
    let enabled: Bool
    let action: () -> Void

    init(
        branch: String,
        additions: Int,
        deletions: Int,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) {
        precondition(!branch.isEmpty, "Session Branch Pill requires a non-empty branch")
        precondition(additions >= 0, "Session Branch Pill requires nonnegative additions")
        precondition(deletions >= 0, "Session Branch Pill requires nonnegative deletions")
        self.branch = branch
        self.additions = additions
        self.deletions = deletions
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: Self.contentSpacing) {
                OpenCodeIconImage(icon: .callSplit, size: Self.iconSize)
                    .foregroundStyle(enabled ? OpenCodeColors.iconBase : OpenCodeColors.textBase)
                    .fixedSize()
                Text(branch)
                    .font(.system(size: 13, weight: .semibold, design: .monospaced))
                    .foregroundStyle(enabled ? OpenCodeColors.textStrong : OpenCodeColors.textBase)
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .frame(minWidth: 0, alignment: .leading)
                if Self.additionLabel(additions: additions) != nil {
                    OpenCodeDiffStat(additions: additions, enabled: enabled, style: .button)
                        .fixedSize(horizontal: true, vertical: false)
                }
                if Self.deletionLabel(deletions: deletions) != nil {
                    OpenCodeDiffStat(deletions: deletions, enabled: enabled, style: .button)
                        .fixedSize(horizontal: true, vertical: false)
                }
            }
            .padding(.horizontal, Self.paddingHorizontal)
            .padding(.vertical, Self.paddingVertical)
            .frame(minHeight: Self.minHeight)
            .frame(maxWidth: Self.maxWidth, alignment: .center)
            .fixedSize(horizontal: true, vertical: false)
            .contentShape(Capsule())
        }
        .buttonStyle(OpenCodeFloatingPillPressStyle())
        .openCodeFloatingPillChrome(enabled: enabled)
        .disabled(!enabled)
        .accessibilityLabel(Self.accessibilityLabel(branch: branch, additions: additions, deletions: deletions))
    }

    static func additionLabel(additions: Int) -> String? {
        additions > 0 ? "+\(additions)" : nil
    }

    static func deletionLabel(deletions: Int) -> String? {
        deletions > 0 ? "−\(deletions)" : nil
    }

    static func accessibilityLabel(branch: String, additions: Int, deletions: Int) -> String {
        var parts = ["Branch \(branch)"]
        if additions > 0 {
            parts.append("\(additions) \(additions == 1 ? "addition" : "additions")")
        }
        if deletions > 0 {
            parts.append("\(deletions) \(deletions == 1 ? "deletion" : "deletions")")
        }
        return parts.joined(separator: ", ")
    }

    private static let minHeight: CGFloat = 36
    private static let paddingHorizontal: CGFloat = 14
    private static let paddingVertical: CGFloat = 8
    private static let contentSpacing: CGFloat = 6
    private static let iconSize: CGFloat = 14
    private static let maxWidth: CGFloat = 360
}

private struct SessionBranchPillPreview: View {
    let previewCase: SessionBranchPillPreviewCase

    var body: some View {
        OpenCodeSessionBranchPill(
            branch: previewCase.branch,
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

#Preview("Mixed Dirty Light") {
    SessionBranchPillPreview(previewCase: .mixedDirtyLight)
}

#Preview("Additions Only Light") {
    SessionBranchPillPreview(previewCase: .additionsOnlyLight)
}

#Preview("Deletions Only Dark") {
    SessionBranchPillPreview(previewCase: .deletionsOnlyDark)
}

#Preview("Clean Light") {
    SessionBranchPillPreview(previewCase: .cleanLight)
}

#Preview("Long Branch Dark") {
    SessionBranchPillPreview(previewCase: .longBranchDark)
}

#Preview("Disabled Light") {
    SessionBranchPillPreview(previewCase: .disabledLight)
}
