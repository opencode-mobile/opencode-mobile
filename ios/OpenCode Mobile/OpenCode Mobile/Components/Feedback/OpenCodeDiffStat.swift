import SwiftUI

/// Type role for an addition and deletion count.
enum OpenCodeDiffStatStyle {
    /// Caption-scale counts, as in a tool-call meta row.
    case caption
    /// Button-scale counts, as in a floating pill.
    case button
    /// Monospace counts, as on the Android branch pill.
    case mono
    /// Label-scale counts, as in a changed-file row.
    case label
}

/// Space between the addition count and the deletion count.
enum OpenCodeDiffStatSeparator {
    /// One space, as in a tool-call meta row.
    case space
    /// Two spaces, as in a summary line.
    case wide
    /// Fixed gap, as between counts on a file row.
    case split
}

/// `+N` and `−M` counts. A nil side is omitted. Zero is shown when passed.
struct OpenCodeDiffStat: View {
    var additions: Int?
    var deletions: Int?
    var enabled: Bool
    var style: OpenCodeDiffStatStyle
    var separator: OpenCodeDiffStatSeparator

    init(
        additions: Int? = nil,
        deletions: Int? = nil,
        enabled: Bool = true,
        style: OpenCodeDiffStatStyle = .caption,
        separator: OpenCodeDiffStatSeparator = .space
    ) {
        if let additions {
            precondition(additions >= 0, "Diff Stat additions must be nonnegative")
        }
        if let deletions {
            precondition(deletions >= 0, "Diff Stat deletions must be nonnegative")
        }
        self.additions = additions
        self.deletions = deletions
        self.enabled = enabled
        self.style = style
        self.separator = separator
    }

    var body: some View {
        switch separator {
        case .split:
            HStack(spacing: Self.splitSpacing) {
                sideLabels
            }
        case .space, .wide:
            inlineText
                .font(style.font)
                .lineLimit(1)
        }
    }

    /// Colored count run for embedding in an existing `Text` concatenation.
    static func text(
        additions: Int?,
        deletions: Int?,
        enabled: Bool = true,
        separator: OpenCodeDiffStatSeparator = .space
    ) -> Text {
        var parts: [Text] = []
        if let additions {
            parts.append(Text(additionLabel(additions)).foregroundColor(additionColor(enabled: enabled)))
        }
        if let deletions {
            if !parts.isEmpty {
                parts.append(Text(separator.gap))
            }
            parts.append(Text(deletionLabel(deletions)).foregroundColor(deletionColor(enabled: enabled)))
        }
        return parts.reduce(Text("")) { $0 + $1 }
    }

    static func additionLabel(_ additions: Int) -> String { "+\(additions)" }

    static func deletionLabel(_ deletions: Int) -> String { "−\(deletions)" }

    @ViewBuilder
    private var sideLabels: some View {
        if let additions {
            Text(Self.additionLabel(additions))
                .font(style.font)
                .foregroundStyle(Self.additionColor(enabled: enabled))
                .lineLimit(1)
        }
        if let deletions {
            Text(Self.deletionLabel(deletions))
                .font(style.font)
                .foregroundStyle(Self.deletionColor(enabled: enabled))
                .lineLimit(1)
        }
    }

    private var inlineText: Text {
        Self.text(additions: additions, deletions: deletions, enabled: enabled, separator: separator)
    }

    private static func additionColor(enabled: Bool) -> Color {
        enabled ? OpenCodeColors.diffAdd : OpenCodeColors.textBase
    }

    private static func deletionColor(enabled: Bool) -> Color {
        enabled ? OpenCodeColors.diffDelete : OpenCodeColors.textBase
    }

    private static let splitSpacing: CGFloat = 12
}

private extension OpenCodeDiffStatStyle {
    var font: Font {
        switch self {
        case .caption, .label:
            OpenCodeTypography.caption
        case .button:
            OpenCodeTypography.button
        case .mono:
            OpenCodeTypography.caption.weight(.semibold).monospaced()
        }
    }
}

private extension OpenCodeDiffStatSeparator {
    var gap: String {
        switch self {
        case .space, .split:
            " "
        case .wide:
            "  "
        }
    }
}

#Preview("Diff Stat") {
    VStack(alignment: .leading, spacing: 12) {
        OpenCodeDiffStat(additions: 12, deletions: 3)
        OpenCodeDiffStat(additions: 12, deletions: 3, enabled: false, style: .button)
        OpenCodeDiffStat(additions: 4, deletions: 0, style: .label, separator: .split)
    }
    .padding()
    .openCodeTheme()
}
