import SwiftUI

enum OpenCodeChoiceBadgeVariant: Hashable {
    /// Surface pill with rounded-rectangle stroke (gallery / dense controls).
    case standard
    /// Capsule well for chips on floating glass chrome (composer), matching the
    /// home dock controls.
    case glass
}

struct OpenCodeChoiceBadge: View {
    let label: OpenCodeText
    let selected: Bool
    let enabled: Bool
    let variant: OpenCodeChoiceBadgeVariant
    let action: () -> Void

    init(
        label: OpenCodeText,
        selected: Bool,
        enabled: Bool = true,
        variant: OpenCodeChoiceBadgeVariant = .standard,
        action: @escaping () -> Void
    ) {
        self.label = label
        self.selected = selected
        self.enabled = enabled
        self.variant = variant
        self.action = action
    }

    var body: some View {
        switch variant {
        case .standard:
            standardBadge
        case .glass:
            glassBadge
        }
    }

    private var standardBadge: some View {
        Button(action: action) {
            Text(openCode: label)
                .font(OpenCodeTypography.button)
                .foregroundStyle(standardLabelColor)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .frame(minHeight: 32)
                .background(standardBackgroundColor, in: RoundedRectangle(cornerRadius: 16))
                .overlay(
                    RoundedRectangle(cornerRadius: 16)
                        .strokeBorder(standardBorderColor, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .openCodeKeyboardFocusable(.roundedRectangle(cornerRadius: 16), enabled: enabled, activate: action)
    }

    private var glassBadge: some View {
        Button(action: action) {
            Text(openCode: label)
                .font(OpenCodeTypography.button)
                .foregroundStyle(glassLabelColor)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .frame(minHeight: 36)
                .fixedSize(horizontal: true, vertical: false)
                .contentShape(Capsule())
                .modifier(OpenCodeChoiceBadgeGlassChrome(selected: selected))
        }
        .buttonStyle(OpenCodeSubtleControlPressStyle(shape: Capsule()))
        .fixedSize(horizontal: true, vertical: false)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .openCodeKeyboardFocusable(.capsule, enabled: enabled, activate: action)
    }

    private var standardLabelColor: Color {
        enabled ? OpenCodeColors.textStrong : OpenCodeColors.textBase
    }

    private var standardBackgroundColor: Color {
        guard enabled else { return OpenCodeColors.surfaceRaisedBase }
        return selected ? OpenCodeColors.surfaceRaisedBase : OpenCodeColors.surfaceBase
    }

    private var standardBorderColor: Color {
        guard enabled else { return OpenCodeColors.borderWeakerBase }
        return selected ? OpenCodeColors.borderWeakBase : OpenCodeColors.borderWeakerBase
    }

    private var glassLabelColor: Color {
        guard enabled else { return OpenCodeColors.textBase }
        // Selected vs unselected differs by chrome background only.
        return OpenCodeColors.textStrong
    }
}

private struct OpenCodeChoiceBadgeGlassChrome: ViewModifier {
    let selected: Bool

    /// A well on the floating chrome, like the dock controls. Not its own
    /// `.glassEffect`: glass on glass doubles the rims and turns into a heavy
    /// slab with Tinted Liquid Glass.
    func body(content: Content) -> some View {
        content.background(
            OpenCodeColors.textStrong.opacity(
                selected ? OpenCodeSubtleWell.selectedOpacity : OpenCodeSubtleWell.opacity
            ),
            in: Capsule()
        )
    }
}

#Preview("Standard") {
    OpenCodeChoiceBadge(label: "Label", selected: false, action: {})
        .padding()
        .openCodeTheme()
}

#Preview("Standard selected") {
    OpenCodeChoiceBadge(label: "Label", selected: true, action: {})
        .padding()
        .openCodeTheme()
}

#Preview("Glass") {
    OpenCodeChoiceBadge(label: "Grok 4.5", selected: true, variant: .glass, action: {})
        .padding()
        .openCodeTheme()
}

#Preview("Disabled") {
    OpenCodeChoiceBadge(label: "Label", selected: false, enabled: false, action: {})
        .padding()
        .openCodeTheme()
}
