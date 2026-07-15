import SwiftUI

private let listItemCornerRadius: CGFloat = 8

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

struct OpenCodeListItem<Trailing: View>: View {
    let title: String
    let subtitle: String?
    let subtitleSegments: [OpenCodeListItemSubtitleSegment]?
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
        HStack(spacing: 12) {
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
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(accent.badgeTint)
                .frame(width: 24, height: 24)
                .background(accent.badgeFill, in: RoundedRectangle(cornerRadius: 4))
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
            title: "iOS color palette and typography screens",
            subtitle: "http://127.0.0.1:4096/",
            selected: true
        )
        OpenCodeListItem(
            title: "Update agents.md with notes on screenshot",
            subtitle: "Unavailable",
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
