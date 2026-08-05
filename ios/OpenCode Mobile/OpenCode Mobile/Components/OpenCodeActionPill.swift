import SwiftUI

enum OpenCodeActionPillVariant {
    case scrollToEnd
}

struct OpenCodeActionPill: View {
    let variant: OpenCodeActionPillVariant
    let enabled: Bool
    let action: () -> Void

    init(
        variant: OpenCodeActionPillVariant,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) {
        self.variant = variant
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            labelContent
        }
        .buttonStyle(OpenCodeActionPillButtonStyle(enabled: enabled))
        .disabled(!enabled)
        .accessibilityLabel(accessibilityLabel)
    }

    private var labelContent: some View {
        HStack(spacing: Self.iconSpacing) {
            if let icon {
                OpenCodeIconImage(icon: icon, size: Self.iconSize)
            }
            Text(label)
                .font(OpenCodeTypography.button)
        }
        .foregroundStyle(labelColor)
        .padding(.horizontal, Self.paddingHorizontal)
        .padding(.vertical, Self.paddingVertical)
        .frame(minHeight: Self.minHeight)
    }

    private var label: String {
        switch variant {
        case .scrollToEnd:
            "Scroll to end"
        }
    }

    private var icon: OpenCodeIcon? {
        switch variant {
        case .scrollToEnd:
            .arrowDownward
        }
    }

    private var accessibilityLabel: String {
        label
    }

    private var labelColor: Color {
        enabled ? OpenCodeColors.textStrong : OpenCodeColors.textBase
    }

    private static let minHeight: CGFloat = 36
    private static let paddingHorizontal: CGFloat = 14
    private static let paddingVertical: CGFloat = 8
    private static let iconSize: CGFloat = 16
    private static let iconSpacing: CGFloat = 6
}

private enum OpenCodeActionPillMetrics {
    static let shadowRadius: CGFloat = 4
}

private struct OpenCodeActionPillButtonStyle: ButtonStyle {
    let enabled: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(backgroundColor(isPressed: configuration.isPressed), in: Capsule())
            .overlay(
                Capsule()
                    .strokeBorder(OpenCodeColors.borderWeakerBase, lineWidth: 1)
            )
            .shadow(
                color: enabled ? Color.black.opacity(0.12) : .clear,
                radius: OpenCodeActionPillMetrics.shadowRadius,
                y: 2
            )
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }

    private func backgroundColor(isPressed: Bool) -> Color {
        guard enabled else { return OpenCodeColors.surfaceRaisedBase }
        return isPressed ? OpenCodeColors.surfaceRaisedBaseHover : OpenCodeColors.surfaceRaisedBase
    }
}

#Preview("Scroll to end") {
    OpenCodeActionPill(variant: .scrollToEnd, action: {})
        .padding()
        .openCodeTheme()
}

#Preview("Disabled") {
    OpenCodeActionPill(variant: .scrollToEnd, enabled: false, action: {})
        .padding()
        .openCodeTheme()
}

#Preview("Dark") {
    OpenCodeActionPill(variant: .scrollToEnd, action: {})
        .padding()
        .openCodeTheme()
        .preferredColorScheme(.dark)
}
