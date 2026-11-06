import SwiftUI

/// The one full-width action at the bottom of a screen: 50 pt, `solid`, with a loading state.
struct HexPrimaryButton: View {
    let title: Text
    var enabled = true
    var loading = false
    let action: () -> Void

    init(_ title: LocalizedStringResource, enabled: Bool = true, loading: Bool = false, action: @escaping () -> Void) {
        self.init(text: Text(title), enabled: enabled, loading: loading, action: action)
    }

    init(verbatim title: String, enabled: Bool = true, loading: Bool = false, action: @escaping () -> Void) {
        self.init(text: Text(verbatim: title), enabled: enabled, loading: loading, action: action)
    }

    init(text: Text, enabled: Bool = true, loading: Bool = false, action: @escaping () -> Void) {
        self.title = text
        self.enabled = enabled
        self.loading = loading
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if loading {
                    HexSpinner(color: HexColor.onSolid)
                }
                title
                    .hexFont(.bodyStrong)
                    .lineLimit(1)
            }
            .foregroundStyle(HexColor.onSolid)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(HexColor.solid, in: RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous))
            .opacity(enabled ? 1 : 0.2)
            .contentShape(Rectangle())
        }
        .buttonStyle(HexPressStyle())
        .disabled(!enabled || loading)
    }
}

/// A 30 pt (small) or 38 pt (regular) button with an optional leading icon, or an icon alone.
/// While `loading`, a spinner takes the icon's place and taps do nothing.
struct HexButton: View {
    enum Size {
        /// 30 pt, radius `s`, `small` type (`.btn`).
        case small
        /// 38 pt, radius `m`, `button` type (`.cbtn`).
        case regular
        /// 44 pt, radius `l`, beside a `HexTextField` (`.pk-up`). Not in the spec yet: proposed.
        case field
    }

    enum Style {
        /// `panel2` with a `line` border.
        case secondary
        /// `solid`.
        case primary
        /// Red text on `panel2`.
        case danger
    }

    var title: Text?
    var icon: HexIcon?
    var size: Size = .small
    var style: Style = .secondary
    var enabled = true
    var loading = false
    /// Stretches to the width it is offered, for a lone action at the bottom of a sheet.
    var fullWidth = false
    let action: () -> Void

    init(
        _ title: LocalizedStringResource? = nil,
        icon: HexIcon? = nil,
        size: Size = .small,
        style: Style = .secondary,
        enabled: Bool = true,
        loading: Bool = false,
        fullWidth: Bool = false,
        action: @escaping () -> Void
    ) {
        self.init(
            text: title.map { Text($0) },
            icon: icon,
            size: size,
            style: style,
            enabled: enabled,
            loading: loading,
            fullWidth: fullWidth,
            action: action
        )
    }

    /// A title already made, such as a toast's action.
    init(
        text: Text?,
        icon: HexIcon? = nil,
        size: Size = .small,
        style: Style = .secondary,
        enabled: Bool = true,
        loading: Bool = false,
        fullWidth: Bool = false,
        action: @escaping () -> Void
    ) {
        self.loading = loading
        self.title = text
        self.icon = icon
        self.size = size
        self.style = style
        self.enabled = enabled
        self.fullWidth = fullWidth
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if loading {
                    HexSpinner(size: size == .small ? HexIconSize.small : HexIconSize.regular, color: style == .primary ? HexColor.onSolid : nil)
                } else if let icon {
                    HexIconView(icon, size: size == .small ? HexIconSize.small : HexIconSize.regular)
                        .foregroundStyle(iconColor)
                }
                if let title {
                    title
                        .hexFont(size == .small ? .small : .button)
                        .lineLimit(1)
                }
            }
            .foregroundStyle(textColor)
            .padding(.horizontal, title == nil ? 0 : (size == .small ? 11 : 16))
            .frame(minWidth: height, maxWidth: fullWidth ? .infinity : nil, minHeight: height, maxHeight: height)
            .background(fill, in: shape)
            .overlay(shape.strokeBorder(style == .primary ? .clear : HexColor.line, lineWidth: 1))
            .opacity(enabled ? 1 : 0.3)
            .contentShape(shape)
        }
        .buttonStyle(HexPressStyle(scale: 0.96))
        .disabled(!enabled)
        .allowsHitTesting(!loading)
    }

    private var height: CGFloat {
        switch size {
        case .small: 30
        case .regular: 38
        case .field: HexSize.touch
        }
    }

    private var shape: RoundedRectangle {
        let radius = switch size {
        case .small: HexRadius.s
        case .regular: HexRadius.m
        case .field: HexRadius.l
        }
        return RoundedRectangle(cornerRadius: radius, style: .continuous)
    }

    private var fill: Color { style == .primary ? HexColor.solid : HexColor.panel2 }

    private var textColor: Color {
        switch style {
        case .secondary: HexColor.text1
        case .primary: HexColor.onSolid
        case .danger: HexColor.red
        }
    }

    private var iconColor: Color {
        switch style {
        case .secondary: HexColor.text2
        case .primary: HexColor.onSolid
        case .danger: HexColor.red
        }
    }
}

/// A 44 pt outlined circle for the top bar: back, goal, settings.
struct HexRoundButton: View {
    let icon: HexIcon
    let label: Text
    /// Lit: the icon turns green (today's goal is running).
    var on = false
    let action: () -> Void

    init(_ icon: HexIcon, label: LocalizedStringResource, on: Bool = false, action: @escaping () -> Void) {
        self.icon = icon
        self.label = Text(label)
        self.on = on
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HexIconView(icon, size: HexIconSize.regular)
                .foregroundStyle(on ? HexColor.green : HexColor.tint(85))
                .frame(width: HexSize.touch, height: HexSize.touch)
                .background(HexColor.tint(3.5), in: Circle())
                .overlay(Circle().strokeBorder(HexColor.tint(10), lineWidth: 1))
                .contentShape(Circle())
        }
        .buttonStyle(HexPressStyle(scale: 0.94))
        .accessibilityLabel(label)
    }
}

/// A 46 pt borderless icon button beside the talk button. The stop tone is red; disabled is 35 %.
struct HexIconButton: View {
    enum Tone {
        /// `text2`.
        case plain
        /// `red`: the agent's stop.
        case stop
        /// `text1`: the one action that finishes something, such as ✓ on a locked take.
        case strong
    }

    let icon: HexIcon
    let label: Text
    var tone: Tone = .plain
    var enabled = true
    let action: () -> Void

    init(_ icon: HexIcon, label: LocalizedStringResource, tone: Tone = .plain, enabled: Bool = true, action: @escaping () -> Void) {
        self.icon = icon
        self.label = Text(label)
        self.tone = tone
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HexIconView(icon, size: HexIconSize.large)
                .foregroundStyle(tone == .stop ? HexColor.red : tone == .strong ? HexColor.text1 : HexColor.text2)
                .frame(width: 46, height: 46)
                .contentShape(RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous))
        }
        .buttonStyle(HexRowPressStyle(radius: HexRadius.l))
        .opacity(enabled ? 1 : 0.35)
        .disabled(!enabled)
        .accessibilityLabel(label)
    }
}
