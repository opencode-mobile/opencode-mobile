import SwiftUI

enum OpenCodeButtonVariant {
    case fullWidthText(title: String)
    case compactText(title: String)
    case iconSquare(systemName: String, accessibilityLabel: String?)
}

struct OpenCodeButton: View {
    let variant: OpenCodeButtonVariant
    let enabled: Bool
    let action: () -> Void

    init(
        variant: OpenCodeButtonVariant,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) {
        self.variant = variant
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            label
        }
        .buttonStyle(OpenCodeButtonStyle(variant: variant, enabled: enabled))
        .disabled(!enabled)
    }

    @ViewBuilder
    private var label: some View {
        switch variant {
        case let .fullWidthText(title):
            Text(title)
                .font(OpenCodeTypography.button)
        case let .compactText(title):
            Text(title)
                .font(OpenCodeTypography.button)
        case let .iconSquare(systemName, accessibilityLabel):
            Image(systemName: systemName)
                .font(.system(size: 18, weight: .semibold))
                .accessibilityLabel(accessibilityLabel ?? "")
                .accessibilityHidden(accessibilityLabel == nil)
        }
    }
}

private struct OpenCodeButtonStyle: ButtonStyle {
    let variant: OpenCodeButtonVariant
    let enabled: Bool

    func makeBody(configuration: Configuration) -> some View {
        let isPressed = configuration.isPressed && enabled
        let maxWidth: CGFloat? = variant.isFullWidth ? .infinity : nil
        let minWidth: CGFloat? = variant.isIconSquare ? 40 : nil
        let fixedWidth: CGFloat? = variant.isIconSquare ? 40 : nil
        let fixedHeight: CGFloat? = variant.isIconSquare ? 40 : nil
        configuration.label
            .foregroundStyle(foregroundColor)
            .padding(.horizontal, horizontalPadding)
            .padding(.vertical, verticalPadding)
            .frame(
                minWidth: minWidth,
                maxWidth: maxWidth,
                minHeight: minHeight
            )
            .frame(
                width: fixedWidth,
                height: fixedHeight
            )
            .background(backgroundColor(isPressed: isPressed), in: RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .strokeBorder(borderColor(isPressed: isPressed), lineWidth: 1)
            )
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }

    private var minHeight: CGFloat {
        switch variant {
        case .fullWidthText:
            return 48
        case .compactText:
            return 40
        case .iconSquare:
            return 40
        }
    }

    private var horizontalPadding: CGFloat {
        switch variant {
        case .iconSquare:
            return 0
        default:
            return 14
        }
    }

    private var verticalPadding: CGFloat {
        switch variant {
        case .iconSquare:
            return 0
        default:
            return 10
        }
    }

    private var foregroundColor: Color {
        enabled ? OpenCodeColors.surfacePrimary : OpenCodeColors.textSecondary
    }

    private func backgroundColor(isPressed: Bool) -> Color {
        if !enabled { return OpenCodeColors.surfaceSecondary }
        if isPressed { return OpenCodeColors.textSecondary }
        return OpenCodeColors.textPrimary
    }

    private func borderColor(isPressed: Bool) -> Color {
        if !enabled { return OpenCodeColors.borderSubtle }
        if isPressed { return OpenCodeColors.textSecondary }
        return OpenCodeColors.textPrimary
    }
}

private extension OpenCodeButtonVariant {
    var isFullWidth: Bool {
        if case .fullWidthText = self { return true }
        return false
    }

    var isIconSquare: Bool {
        if case .iconSquare = self { return true }
        return false
    }
}

#Preview("Full Width") {
    OpenCodeButton(variant: .fullWidthText(title: "Run"), action: {})
        .padding()
        .openCodeTheme()
}

#Preview("Compact") {
    OpenCodeButton(variant: .compactText(title: "Build"), action: {})
        .padding()
        .openCodeTheme()
}

#Preview("Icon") {
    OpenCodeButton(variant: .iconSquare(systemName: "arrow.up", accessibilityLabel: "Send"), action: {})
        .padding()
        .openCodeTheme()
}

#Preview("Disabled") {
    OpenCodeButton(variant: .fullWidthText(title: "Run"), enabled: false, action: {})
        .padding()
        .openCodeTheme()
}
