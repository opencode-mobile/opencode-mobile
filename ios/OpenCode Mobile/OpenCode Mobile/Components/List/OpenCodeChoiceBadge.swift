import SwiftUI

enum OpenCodeChoiceBadgeVariant: Hashable {
    /// Surface pill with rounded-rectangle stroke (gallery / dense controls).
    case standard
    /// Capsule glass chrome aligned with home dock Filter Chip.
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

    func body(content: Content) -> some View {
        if #available(iOS 26, *) {
            if selected {
                content
                    .background(OpenCodeColors.textStrong.opacity(0.12), in: Capsule())
                    // Non-interactive glass: `.interactive()` adds a loud lens press;
                    // press feedback stays on OpenCodeSubtleControlPressStyle.
                    .glassEffect(.regular, in: .capsule)
            } else {
                content
                    .glassEffect(.clear, in: .capsule)
            }
        } else {
            // Fill-only stand-in: outline-only pills read as low-contrast without glass.
            content
                .background(
                    OpenCodeColors.textStrong.opacity(selected ? 0.14 : 0.08),
                    in: Capsule()
                )
        }
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
