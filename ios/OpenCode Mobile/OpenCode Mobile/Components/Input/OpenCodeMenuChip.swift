import SwiftUI

private let menuChipMinHeight: CGFloat = 32
private let menuChipHorizontalPadding: CGFloat = 12
private let menuChipSpacing: CGFloat = 6
private let menuChipIconSize: CGFloat = 14
private let menuChipLetterSize: CGFloat = 18
private let menuChipLetterGlyphSize: CGFloat = 10
private let menuChipChevronSize: CGFloat = 14
private let menuChipFont = Font.system(size: 13, weight: .semibold)

/// What leads a menu chip's title.
enum OpenCodeMenuChipLeading {
    case none
    case icon(OpenCodeIcon)
    /// A project's letter badge.
    case letter(Character, LetterAccent)
}

/// Capsule that shows the current choice of a menu and opens it: leading icon or letter badge,
/// the choice, then a chevron. `selected` marks a non-default choice with the interactive tint.
struct OpenCodeMenuChip: View {
    let title: OpenCodeText
    let leading: OpenCodeMenuChipLeading
    let selected: Bool
    let enabled: Bool
    let accessibilityLabel: OpenCodeText?
    let action: () -> Void

    init(
        title: OpenCodeText,
        leading: OpenCodeMenuChipLeading = .none,
        selected: Bool = false,
        enabled: Bool = true,
        accessibilityLabel: OpenCodeText? = nil,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.leading = leading
        self.selected = selected
        self.enabled = enabled
        self.accessibilityLabel = accessibilityLabel
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: menuChipSpacing) {
                leadingContent
                Text(openCode: title)
                    .font(menuChipFont)
                    .lineLimit(1)
                OpenCodeIconImage(icon: .keyboardArrowDown, size: menuChipChevronSize)
                    .accessibilityHidden(true)
            }
            .foregroundStyle(foreground)
            .padding(.horizontal, menuChipHorizontalPadding)
            .frame(minHeight: menuChipMinHeight)
            .background(background, in: Capsule())
            .overlay(Capsule().strokeBorder(border, lineWidth: 1))
            .contentShape(Capsule())
        }
        .buttonStyle(OpenCodeSubtleControlPressStyle(shape: Capsule()))
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
        .accessibilityLabel(openCode: accessibilityLabel ?? title)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .openCodeKeyboardFocusable(.capsule, enabled: enabled, activate: action)
    }

    @ViewBuilder
    private var leadingContent: some View {
        switch leading {
        case .none:
            EmptyView()
        case let .icon(icon):
            OpenCodeIconImage(icon: icon, size: menuChipIconSize)
                .accessibilityHidden(true)
        case let .letter(character, accent):
            Text(Self.badgeLetter(character))
                .font(.system(size: menuChipLetterGlyphSize, weight: .bold, design: .rounded))
                .foregroundStyle(accent.badgeTint)
                .frame(width: menuChipLetterSize, height: menuChipLetterSize)
                .background(accent.badgeFill, in: Circle())
                .accessibilityHidden(true)
        }
    }

    private var foreground: Color {
        guard enabled else { return OpenCodeColors.textWeaker }
        return selected ? OpenCodeColors.interactive : OpenCodeColors.textBase
    }

    private var background: Color {
        selected ? OpenCodeColors.interactive.opacity(0.14) : OpenCodeColors.textStrong.opacity(0.08)
    }

    private var border: Color {
        selected ? OpenCodeColors.interactive.opacity(0.3) : .clear
    }

    static func badgeLetter(_ character: Character) -> String {
        let upper = String(character).uppercased(with: .current)
        guard let first = upper.first, first.isLetter || first.isNumber else { return "?" }
        return String(first)
    }
}

#Preview("Menu Chip") {
    VStack(alignment: .leading, spacing: 12) {
        OpenCodeMenuChip(title: "All projects", leading: .icon(.widgets), action: {})
        OpenCodeMenuChip(title: "acme-invoices", leading: .letter("a", .teal), selected: true, action: {})
        OpenCodeMenuChip(title: "Disabled", enabled: false, action: {})
    }
    .padding()
    .openCodeTheme()
}
