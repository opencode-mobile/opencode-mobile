import SwiftUI

private let sectionHeaderInsetStandard: CGFloat = 16
private let sectionHeaderTopPadding: CGFloat = 16
private let sectionHeaderBottomPadding: CGFloat = 4
private let sectionHeaderTrailingSpacing: CGFloat = 8
private let sectionHeaderLabelTracking: CGFloat = 0.5

/// How the title text is treated.
enum OpenCodeSectionHeaderVariant {
    /// Short group label rendered uppercase with semibold caption weight (`SERVER`, `RECENT`).
    case label
    /// Free text kept as written and truncated to one line (a directory path, a summary line).
    case plain
}

/// Optional content on the trailing edge of the header.
enum OpenCodeSectionHeaderTrailing {
    /// Quiet item count for the group below.
    case count(Int)
    /// Compact text action scoped to the group below.
    case action(OpenCodeText, () -> Void)
}

/// Horizontal inset relative to the container edge.
enum OpenCodeSectionHeaderInset {
    /// Aligns with `OpenCodeListItem` text (16pt).
    case standard
    /// No horizontal inset; the container already positions the header.
    case flush
}

/// Caption-level title above a group of rows or a region, with an optional trailing count or action.
struct OpenCodeSectionHeader: View {
    let title: OpenCodeText
    var variant: OpenCodeSectionHeaderVariant = .label
    var trailing: OpenCodeSectionHeaderTrailing? = nil
    var inset: OpenCodeSectionHeaderInset = .standard

    init(
        title: OpenCodeText,
        variant: OpenCodeSectionHeaderVariant = .label,
        trailing: OpenCodeSectionHeaderTrailing? = nil,
        inset: OpenCodeSectionHeaderInset = .standard
    ) {
        self.title = title
        self.variant = variant
        self.trailing = trailing
        self.inset = inset
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: sectionHeaderTrailingSpacing) {
            titleText
                .frame(maxWidth: .infinity, alignment: .leading)
            trailingView
        }
        .padding(.horizontal, horizontalInset)
        .padding(.top, sectionHeaderTopPadding)
        .padding(.bottom, sectionHeaderBottomPadding)
        .accessibilityElement(children: accessibilityChildren)
        .accessibilityLabel(accessibilityLabel)
    }

    @ViewBuilder
    private var titleText: some View {
        switch variant {
        case .label:
            Text(verbatim: title.resolved.uppercased())
                .font(OpenCodeTypography.caption.weight(.semibold))
                .foregroundStyle(OpenCodeColors.textBase)
                .tracking(sectionHeaderLabelTracking)
                .lineLimit(1)
        case .plain:
            Text(openCode: title)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
                .lineLimit(1)
                .truncationMode(.middle)
        }
    }

    @ViewBuilder
    private var trailingView: some View {
        switch trailing {
        case let .count(count):
            Text("\(count)")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)
                .monospacedDigit()
        case let .action(label, action):
            Button(action: action) {
                Text(openCode: label)
                    .font(OpenCodeTypography.caption.weight(.medium))
                    .foregroundStyle(OpenCodeColors.interactive)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(openCode: label)
        case nil:
            EmptyView()
        }
    }

    private var horizontalInset: CGFloat {
        switch inset {
        case .standard: sectionHeaderInsetStandard
        case .flush: 0
        }
    }

    private var accessibilityChildren: AccessibilityChildBehavior {
        if case .action = trailing { return .contain }
        return .ignore
    }

    private var accessibilityLabel: String {
        if case let .count(count) = trailing {
            return "\(title.resolved), \(count) \(count == 1 ? "item" : "items")"
        }
        return title.resolved
    }
}

#Preview("Section Header") {
    VStack(spacing: 0) {
        OpenCodeSectionHeader(title: "Server")
        OpenCodeSectionHeader(title: "Sources/App/Features", variant: .plain, trailing: .count(3))
        OpenCodeSectionHeader(title: "3 waiting", variant: .plain, trailing: .action("Clear all", {}))
        OpenCodeSectionHeader(title: "Context breakdown", inset: .flush)
    }
    .padding()
    .openCodeTheme()
}
