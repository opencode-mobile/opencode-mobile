import SwiftUI

enum OpenCodeToolCallCardStatus: String, CaseIterable, Sendable {
    case pending
    case running
    case completed
    case error
}

struct OpenCodeToolCallCard: View {
    let status: OpenCodeToolCallCardStatus
    let title: String
    let subtitle: String?
    let additions: Int?
    let deletions: Int?
    let errorText: String?
    let action: (() -> Void)?

    init(
        status: OpenCodeToolCallCardStatus,
        title: String,
        subtitle: String? = nil,
        additions: Int? = nil,
        deletions: Int? = nil,
        errorText: String? = nil,
        action: (() -> Void)? = nil
    ) {
        precondition(additions.map { $0 >= 0 } ?? true, "Tool Call Card additions must be nonnegative")
        precondition(deletions.map { $0 >= 0 } ?? true, "Tool Call Card deletions must be nonnegative")
        self.status = status
        self.title = title
        self.subtitle = subtitle
        self.additions = additions
        self.deletions = deletions
        self.errorText = errorText
        self.action = action
    }

    var body: some View {
        Group {
            if let action {
                Button(action: action) {
                    cardContent
                }
                .buttonStyle(.plain)
            } else {
                cardContent
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityAddTraits(action == nil ? [] : .isButton)
    }

    private var cardContent: some View {
        HStack(alignment: .top, spacing: Self.statusDotSpacing) {
            Circle()
                .fill(status.dotColor)
                .frame(width: Self.statusDotSize, height: Self.statusDotSize)
                .padding(.top, Self.statusDotTopPadding)

            VStack(alignment: .leading, spacing: Self.contentSpacing) {
                Text(title)
                    .font(OpenCodeTypography.heading)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .lineLimit(1)

                if hasMetaRow {
                    metaRow
                }

                if let errorText, !errorText.isEmpty {
                    Text(errorText)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.error)
                        .lineLimit(3)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, Self.paddingHorizontal)
        .padding(.vertical, Self.paddingVertical)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(RoundedRectangle(cornerRadius: Self.cornerRadius))
        .background {
            RoundedRectangle(cornerRadius: Self.cornerRadius)
                .fill(OpenCodeColors.surfaceBase)
        }
        .overlay {
            RoundedRectangle(cornerRadius: Self.cornerRadius)
                .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
        }
    }

    private var hasMetaRow: Bool {
        let hasSubtitle = subtitle.map { !$0.isEmpty } ?? false
        return hasSubtitle || hasDiffCounts
    }

    private var hasDiffCounts: Bool {
        (additions ?? 0) > 0 || (deletions ?? 0) > 0
    }

    private var metaRow: some View {
        HStack(alignment: .center, spacing: Self.metaRowSpacing) {
            if let subtitle, !subtitle.isEmpty {
                Text(subtitle)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .lineLimit(1)
            }

            Spacer(minLength: 0)

            if hasDiffCounts {
                diffCountsText
                    .font(OpenCodeTypography.caption)
                    .lineLimit(1)
            }
        }
    }

    private var diffCountsText: Text {
        var parts: [Text] = []
        if let additions, additions > 0 {
            parts.append(Text("+\(additions)").foregroundColor(OpenCodeColors.diffAdd))
        }
        if let deletions, deletions > 0 {
            if !parts.isEmpty {
                parts.append(Text(" ").foregroundColor(OpenCodeColors.textBase))
            }
            parts.append(Text("−\(deletions)").foregroundColor(OpenCodeColors.diffDelete))
        }
        return parts.reduce(Text("")) { $0 + $1 }
    }

    private var accessibilityLabel: String {
        var parts = [status.rawValue, title]
        if let subtitle, !subtitle.isEmpty {
            parts.append(subtitle)
        }
        if let additions, additions > 0 {
            parts.append("+\(additions)")
        }
        if let deletions, deletions > 0 {
            parts.append("−\(deletions)")
        }
        if let errorText, !errorText.isEmpty {
            parts.append(errorText)
        }
        return parts.joined(separator: ", ")
    }

    private static let cornerRadius: CGFloat = 8
    private static let paddingHorizontal: CGFloat = 12
    private static let paddingVertical: CGFloat = 10
    private static let statusDotSize: CGFloat = 6
    private static let statusDotSpacing: CGFloat = 10
    private static let statusDotTopPadding: CGFloat = 6
    private static let contentSpacing: CGFloat = 4
    private static let metaRowSpacing: CGFloat = 8
}

private extension OpenCodeToolCallCardStatus {
    var dotColor: Color {
        switch self {
        case .pending, .running:
            OpenCodeColors.warning
        case .completed:
            OpenCodeColors.success
        case .error:
            OpenCodeColors.error
        }
    }
}

#if DEBUG
private enum ToolCallCardPreviewCase: String, CaseIterable, Identifiable {
    case pending
    case running
    case completedWithDiff
    case error
    case tappable
    case titleOnly

    var id: String { rawValue }

    var status: OpenCodeToolCallCardStatus {
        switch self {
        case .pending: .pending
        case .running: .running
        case .completedWithDiff, .tappable, .titleOnly: .completed
        case .error: .error
        }
    }

    var title: String {
        switch self {
        case .pending: "Called bash"
        case .running: "Called task"
        case .completedWithDiff, .tappable: "Called edit"
        case .error: "Called webfetch"
        case .titleOnly: "Called skill"
        }
    }

    var subtitle: String? {
        switch self {
        case .pending: "npm test"
        case .running: "Explore codebase"
        case .completedWithDiff, .tappable: "OpenCodeListItem.swift"
        case .error: nil
        case .titleOnly: nil
        }
    }

    var additions: Int? {
        switch self {
        case .completedWithDiff, .tappable: 12
        default: nil
        }
    }

    var deletions: Int? {
        switch self {
        case .completedWithDiff, .tappable: 3
        default: nil
        }
    }

    var errorText: String? {
        switch self {
        case .error: "Request failed with status 403"
        default: nil
        }
    }

    var tappable: Bool {
        self == .tappable
    }
}

#Preview("Tool Call Card") {
    ScrollView {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(ToolCallCardPreviewCase.allCases) { preview in
                OpenCodeToolCallCard(
                    status: preview.status,
                    title: preview.title,
                    subtitle: preview.subtitle,
                    additions: preview.additions,
                    deletions: preview.deletions,
                    errorText: preview.errorText,
                    action: preview.tappable ? {} : nil
                )
            }
        }
        .padding()
    }
    .openCodeTheme()
}
#endif
