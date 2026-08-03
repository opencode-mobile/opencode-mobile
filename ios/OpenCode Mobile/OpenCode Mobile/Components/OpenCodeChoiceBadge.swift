import SwiftUI

enum OpenCodeChoiceBadgeVariant: Hashable {
    /// Surface pill with rounded-rectangle stroke (gallery / dense controls).
    case standard
    /// Capsule glass chrome aligned with home dock Filter Chip.
    case glass
}

struct OpenCodeChoiceBadge: View {
    let label: String
    let selected: Bool
    let enabled: Bool
    let variant: OpenCodeChoiceBadgeVariant
    let action: () -> Void

    init(
        label: String,
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
            Text(label)
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
            Text(label)
                .font(OpenCodeTypography.button)
                .foregroundStyle(glassLabelColor)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .frame(minHeight: 36)
                .fixedSize(horizontal: true, vertical: false)
                .contentShape(Capsule())
        }
        .buttonStyle(OpenCodeChoiceBadgeGlassPressStyle())
        .modifier(OpenCodeChoiceBadgeGlassChrome(selected: selected))
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

private struct OpenCodeChoiceBadgeGlassPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .opacity(configuration.isPressed ? 0.92 : 1)
            .animation(.smooth(duration: 0.18), value: configuration.isPressed)
    }
}

private struct OpenCodeChoiceBadgeGlassChrome: ViewModifier {
    let selected: Bool

    func body(content: Content) -> some View {
        if #available(iOS 26, *) {
            if selected {
                content
                    .background(OpenCodeColors.textStrong.opacity(0.12), in: Capsule())
                    .glassEffect(.regular.interactive(), in: .capsule)
            } else {
                content
                    .glassEffect(.clear.interactive(), in: .capsule)
            }
        } else if selected {
            content
                .background(OpenCodeColors.surfaceRaisedBase, in: Capsule())
                .overlay(
                    Capsule()
                        .strokeBorder(OpenCodeColors.borderWeakBase, lineWidth: 1.5)
                )
        } else {
            content
                .background(OpenCodeColors.surfaceRaisedBase.opacity(0.001), in: Capsule())
                .overlay(
                    Capsule()
                        .strokeBorder(OpenCodeColors.borderWeakerBase, lineWidth: 1)
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
