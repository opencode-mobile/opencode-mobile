import SwiftUI

private let listItemCornerRadius: CGFloat = 8
private let listItemStatusDotSize: CGFloat = 6
private let listItemStatusDotSpacing: CGFloat = 4
private let listItemFooterCaptionSpacing: CGFloat = 8

enum OpenCodeListItemStyle {
    case plain
    case card
}

enum OpenCodeListItemSubtitleTone {
    case secondary
    case addition
    case deletion
}

struct OpenCodeListItemSubtitleSegment {
    let text: String
    let tone: OpenCodeListItemSubtitleTone
}

enum OpenCodeListItemStatusTone {
    case interactive
    case success
    case warning
    case error
    case info
}

struct OpenCodeListItemStatus {
    let text: String
    let tone: OpenCodeListItemStatusTone
}

struct OpenCodeListItem<Trailing: View>: View {
    let title: String
    let subtitle: String?
    let subtitleSegments: [OpenCodeListItemSubtitleSegment]?
    let status: OpenCodeListItemStatus?
    let caption: String?
    let selected: Bool
    let enabled: Bool
    let style: OpenCodeListItemStyle
    let startDecorator: StartDecorator
    let action: (() -> Void)?
    @ViewBuilder let trailing: () -> Trailing

    init(
        title: String,
        subtitle: String? = nil,
        subtitleSegments: [OpenCodeListItemSubtitleSegment]? = nil,
        status: OpenCodeListItemStatus? = nil,
        caption: String? = nil,
        selected: Bool = false,
        enabled: Bool = true,
        style: OpenCodeListItemStyle = .plain,
        startDecorator: StartDecorator = .none,
        action: (() -> Void)? = nil,
        @ViewBuilder trailing: @escaping () -> Trailing = { EmptyView() }
    ) {
        precondition(subtitle == nil || subtitleSegments == nil, "List Item accepts either a plain subtitle or semantic segments")
        self.title = title
        self.subtitle = subtitle
        self.subtitleSegments = subtitleSegments
        self.status = status
        self.caption = caption
        self.selected = selected
        self.enabled = enabled
        self.style = style
        self.startDecorator = startDecorator
        self.action = action
        self.trailing = trailing
    }

    var body: some View {
        Group {
            if let action {
                Button(action: action) {
                    content
                }
                .buttonStyle(.plain)
                .disabled(!enabled)
            } else {
                content
            }
        }
    }

    private var content: some View {
        HStack(alignment: rowAlignment, spacing: 12) {
            startDecoratorContent

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(OpenCodeTypography.heading)
                    .foregroundStyle(enabled ? OpenCodeColors.textStrong : OpenCodeColors.textWeaker)
                    .lineLimit(1)

                if let subtitleSegments {
                    semanticSubtitle(subtitleSegments)
                        .font(OpenCodeTypography.caption)
                        .lineLimit(1)
                } else if let subtitle {
                    Text(subtitle)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(enabled ? OpenCodeColors.textBase : OpenCodeColors.textWeaker)
                        .lineLimit(1)
                }

                if status != nil || caption != nil {
                    footerRow
                }
            }

            Spacer(minLength: 0)

            trailing()
                .foregroundStyle(OpenCodeColors.iconBase)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background {
            containerBackground
        }
        .overlay {
            if style == .card {
                RoundedRectangle(cornerRadius: listItemCornerRadius)
                    .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
            }
        }
        .opacity(enabled ? 1 : 0.7)
    }

    private var rowAlignment: VerticalAlignment {
        if case .letter = startDecorator { return .top }
        return .center
    }

    private var footerRow: some View {
        HStack(spacing: listItemFooterCaptionSpacing) {
            if let status {
                HStack(spacing: listItemStatusDotSpacing) {
                    Circle()
                        .fill(statusColor(status.tone))
                        .frame(width: listItemStatusDotSize, height: listItemStatusDotSize)
                    Text(status.text)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(statusColor(status.tone))
                        .lineLimit(1)
                }
            }

            if let caption {
                Text(caption)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeaker)
                    .lineLimit(1)
            }
        }
        .lineLimit(1)
    }

    private func semanticSubtitle(_ segments: [OpenCodeListItemSubtitleSegment]) -> Text {
        segments.reduce(Text("")) { result, segment in
            result + Text(segment.text)
                .foregroundColor(subtitleColor(segment.tone))
        }
    }

    private func subtitleColor(_ tone: OpenCodeListItemSubtitleTone) -> Color {
        guard enabled else { return OpenCodeColors.textWeaker }
        switch tone {
        case .secondary:
            return OpenCodeColors.textBase
        case .addition:
            return OpenCodeColors.diffAdd
        case .deletion:
            return OpenCodeColors.diffDelete
        }
    }

    private func statusColor(_ tone: OpenCodeListItemStatusTone) -> Color {
        guard enabled else { return OpenCodeColors.textWeaker }
        switch tone {
        case .interactive:
            return OpenCodeColors.interactive
        case .success:
            return OpenCodeColors.success
        case .warning:
            return OpenCodeColors.warning
        case .error:
            return OpenCodeColors.error
        case .info:
            return OpenCodeColors.info
        }
    }

    @ViewBuilder
    private var containerBackground: some View {
        switch (style, selected) {
        case (.plain, false):
            Color.clear
        case (.plain, true), (.card, true):
            RoundedRectangle(cornerRadius: listItemCornerRadius)
                .fill(OpenCodeColors.surfaceRaisedBase)
        case (.card, false):
            RoundedRectangle(cornerRadius: listItemCornerRadius)
                .fill(OpenCodeColors.surfaceBase)
        }
    }

    @ViewBuilder
    private var startDecoratorContent: some View {
        switch startDecorator {
        case .none:
            EmptyView()
        case let .letter(character, accent):
            Text(normalizedBadgeLetter(character))
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(accent.badgeTint)
                .frame(width: 36, height: 36)
                .background(accent.badgeFill, in: RoundedRectangle(cornerRadius: 6))
                .accessibilityHidden(true)
        case let .icon(icon, accessibilityLabel):
            OpenCodeIconImage(icon: icon)
                .font(.system(size: 24))
                .foregroundStyle(OpenCodeColors.iconBase)
                .frame(width: 24, height: 24)
                .accessibilityHidden(accessibilityLabel == nil)
                .accessibilityLabel(accessibilityLabel ?? "")
        }
    }
}

private func normalizedBadgeLetter(_ character: Character) -> String {
    let upper = String(character).uppercased(with: .current)
    guard let first = upper.first else { return "?" }
    return first.isLetter || first.isNumber ? String(first) : "?"
}

#Preview {
    VStack(spacing: 12) {
        OpenCodeListItem(
            title: "Material Design Icons integration plan",
            subtitle: "http://127.0.0.1:4096/"
        )
        OpenCodeListItem(
            title: "contact.html",
            subtitleSegments: [
                .init(text: "Modified · ", tone: .secondary),
                .init(text: "+51", tone: .addition),
                .init(text: " ", tone: .secondary),
                .init(text: "−7", tone: .deletion)
            ]
        )
        OpenCodeListItem(
            title: "Sidebar v2 UX Redesign",
            subtitle: "There's a local SQLite state database — I can look at the real threads…",
            status: OpenCodeListItemStatus(text: "Working · 14m", tone: .interactive),
            caption: "sidebar-v2-concepts",
            startDecorator: .letter(Character("t"), .blue)
        )
        OpenCodeListItem(
            title: "iOS color palette and typography screens",
            subtitle: "http://127.0.0.1:4096/",
            selected: true
        )
        OpenCodeListItem(
            title: "Update agents.md with notes on screenshot",
            subtitle: "Unavailable",
            status: OpenCodeListItemStatus(text: "Working · 14m", tone: .interactive),
            caption: "sidebar-v2-concepts",
            enabled: false
        )
        OpenCodeListItem(
            title: "Letter violet",
            subtitle: "Badge",
            startDecorator: .letter(Character("m"), .violet)
        )
        OpenCodeListItem(
            title: "Card style with border",
            subtitle: "http://127.0.0.1:4096/",
            style: .card
        )
    }
    .padding(16)
    .openCodeTheme()
}
