import SwiftUI

private let listItemCornerRadius: CGFloat = 8

enum OpenCodeListItemStyle {
    case plain
    case card
}

struct OpenCodeListItem<Trailing: View>: View {
    let title: String
    let subtitle: String?
    let selected: Bool
    let enabled: Bool
    let style: OpenCodeListItemStyle
    let startDecorator: StartDecorator
    let action: (() -> Void)?
    @ViewBuilder let trailing: () -> Trailing

    init(
        title: String,
        subtitle: String? = nil,
        selected: Bool = false,
        enabled: Bool = true,
        style: OpenCodeListItemStyle = .plain,
        startDecorator: StartDecorator = .none,
        action: (() -> Void)? = nil,
        @ViewBuilder trailing: @escaping () -> Trailing = { EmptyView() }
    ) {
        self.title = title
        self.subtitle = subtitle
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
                    .foregroundStyle(enabled ? OpenCodeColors.textPrimary : OpenCodeColors.textDisabled)
                    .lineLimit(1)

                if let subtitle {
                    Text(subtitle)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(enabled ? OpenCodeColors.textSecondary : OpenCodeColors.textDisabled)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: 0)

            trailing()
                .foregroundStyle(OpenCodeColors.iconDefault)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background {
            containerBackground
        }
        .overlay {
            if style == .card {
                RoundedRectangle(cornerRadius: listItemCornerRadius)
                    .stroke(OpenCodeColors.borderSubtle, lineWidth: 1)
            }
        }
        .opacity(enabled ? 1 : 0.7)
    }

    @ViewBuilder
    private var containerBackground: some View {
        switch (style, selected) {
        case (.plain, false):
            Color.clear
        case (.plain, true), (.card, true):
            RoundedRectangle(cornerRadius: listItemCornerRadius)
                .fill(OpenCodeColors.surfaceSecondary)
        case (.card, false):
            RoundedRectangle(cornerRadius: listItemCornerRadius)
                .fill(OpenCodeColors.surfacePrimary)
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
                .foregroundStyle(OpenCodeColors.iconDefault)
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
