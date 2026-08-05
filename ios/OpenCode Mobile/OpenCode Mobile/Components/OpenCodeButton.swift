import SwiftUI

enum OpenCodeButtonVariant {
    case fullWidthText(title: String)
    case compactText(title: String)
    case iconSquare(OpenCodeIcon, accessibilityLabel: String?)
    case iconCircle(OpenCodeIcon, accessibilityLabel: String?)
    case iconCircleText(String, accessibilityLabel: String?)
}

enum OpenCodeButtonContrast {
    case high
    case low
    case destructive
}

enum OpenCodeButtonSize {
    case large
    case medium
}

struct OpenCodeButton: View {
    let variant: OpenCodeButtonVariant
    let size: OpenCodeButtonSize
    let enabled: Bool
    let contrast: OpenCodeButtonContrast
    let action: () -> Void

    init(
        variant: OpenCodeButtonVariant,
        size: OpenCodeButtonSize = .medium,
        enabled: Bool = true,
        contrast: OpenCodeButtonContrast = .high,
        action: @escaping () -> Void
    ) {
        self.variant = variant
        self.size = size
        self.enabled = enabled
        self.contrast = contrast
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            label
        }
        .buttonStyle(OpenCodeButtonStyle(variant: variant, size: size, enabled: enabled, contrast: contrast))
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
        case let .iconSquare(icon, accessibilityLabel):
            OpenCodeIconImage(icon: icon)
                .font(.system(size: 18, weight: .semibold))
                .accessibilityLabel(accessibilityLabel ?? "")
                .accessibilityHidden(accessibilityLabel == nil)
        case let .iconCircle(icon, accessibilityLabel):
            OpenCodeIconImage(icon: icon)
                .font(.system(size: 18, weight: .semibold))
                .accessibilityLabel(accessibilityLabel ?? "")
                .accessibilityHidden(accessibilityLabel == nil)
        case let .iconCircleText(text, accessibilityLabel):
            Text(text)
                .font(.system(size: 18, weight: .semibold))
                .accessibilityLabel(accessibilityLabel ?? "")
                .accessibilityHidden(accessibilityLabel == nil)
        }
    }
}

private struct OpenCodeButtonStyle: ButtonStyle {
    let variant: OpenCodeButtonVariant
    let size: OpenCodeButtonSize
    let enabled: Bool
    let contrast: OpenCodeButtonContrast

    @ViewBuilder
    func makeBody(configuration: Configuration) -> some View {
        let isPressed = configuration.isPressed && enabled
        let maxWidth: CGFloat? = variant.isFullWidth ? .infinity : nil
        let iconSize: CGFloat = size == .large ? 48 : 40
        let minWidth: CGFloat? = variant.isIconOnly ? iconSize : nil
        let fixedWidth: CGFloat? = variant.isIconOnly ? iconSize : nil
        let fixedHeight: CGFloat? = variant.isIconOnly ? iconSize : nil
        if variant.isIconCircle {
            baseLabel(
                configuration: configuration,
                minWidth: minWidth,
                maxWidth: maxWidth,
                fixedWidth: fixedWidth,
                fixedHeight: fixedHeight
            )
            .background(backgroundColor(isPressed: isPressed), in: Circle())
            .overlay(
                Circle()
                    .strokeBorder(borderColor(isPressed: isPressed), lineWidth: 1)
            )
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
        } else {
            baseLabel(
                configuration: configuration,
                minWidth: minWidth,
                maxWidth: maxWidth,
                fixedWidth: fixedWidth,
                fixedHeight: fixedHeight
            )
            .background(backgroundColor(isPressed: isPressed), in: RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .strokeBorder(borderColor(isPressed: isPressed), lineWidth: 1)
            )
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
        }
    }

    private func baseLabel(
        configuration: Configuration,
        minWidth: CGFloat?,
        maxWidth: CGFloat?,
        fixedWidth: CGFloat?,
        fixedHeight: CGFloat?
    ) -> some View {
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
    }

    private var minHeight: CGFloat {
        switch size {
        case .large:
            return 48
        case .medium:
            return 40
        }
    }

    private var horizontalPadding: CGFloat {
        switch variant {
        case .iconSquare, .iconCircle, .iconCircleText:
            return 0
        default:
            return 14
        }
    }

    private var verticalPadding: CGFloat {
        switch variant {
        case .iconSquare, .iconCircle, .iconCircleText:
            return 0
        default:
            return 10
        }
    }

    private var foregroundColor: Color {
        if !enabled { return OpenCodeColors.textBase }
        switch contrast {
        case .low: return OpenCodeColors.textStrong
        case .destructive: return OpenCodeColors.surfaceBase
        case .high: return OpenCodeColors.surfaceBase
        }
    }

    private func backgroundColor(isPressed: Bool) -> Color {
        if !enabled { return OpenCodeColors.surfaceRaisedBase }
        if isPressed { return OpenCodeColors.textBase }
        switch contrast {
        case .low: return OpenCodeColors.surfaceRaisedBase
        case .destructive: return OpenCodeColors.error
        case .high: return OpenCodeColors.textStrong
        }
    }

    private func borderColor(isPressed: Bool) -> Color {
        if !enabled { return OpenCodeColors.borderWeakerBase }
        if isPressed { return OpenCodeColors.textBase }
        switch contrast {
        case .low: return OpenCodeColors.borderWeakerBase
        case .destructive: return OpenCodeColors.error
        case .high: return OpenCodeColors.textStrong
        }
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

    var isIconCircle: Bool {
        switch self {
        case .iconCircle, .iconCircleText:
            return true
        default:
            return false
        }
    }

    var isIconOnly: Bool {
        switch self {
        case .iconSquare, .iconCircle, .iconCircleText:
            return true
        default:
            return false
        }
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
    OpenCodeButton(variant: .iconSquare(.arrowUpward, accessibilityLabel: "Send"), action: {})
        .padding()
        .openCodeTheme()
}

#Preview("Icon Circle") {
    OpenCodeButton(variant: .iconCircle(.arrowUpward, accessibilityLabel: "Send"), action: {})
        .padding()
        .openCodeTheme()
}

#Preview("Icon Circle Text") {
    OpenCodeButton(variant: .iconCircleText("/", accessibilityLabel: "Open commands"), action: {})
        .padding()
        .openCodeTheme()
}

#Preview("Low Contrast") {
    OpenCodeButton(
        variant: .iconCircle(.add, accessibilityLabel: "Attach context"),
        contrast: .low,
        action: {}
    )
    .padding()
    .openCodeTheme()
}

#Preview("Disabled") {
    OpenCodeButton(variant: .fullWidthText(title: "Run"), enabled: false, action: {})
        .padding()
        .openCodeTheme()
}
