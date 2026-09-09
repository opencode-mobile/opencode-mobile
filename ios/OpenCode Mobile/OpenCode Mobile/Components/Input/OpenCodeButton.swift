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
    case accent
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
    let longPress: (() -> Void)?

    init(
        variant: OpenCodeButtonVariant,
        size: OpenCodeButtonSize = .medium,
        enabled: Bool = true,
        contrast: OpenCodeButtonContrast = .high,
        longPress: (() -> Void)? = nil,
        action: @escaping () -> Void
    ) {
        self.variant = variant
        self.size = size
        self.enabled = enabled
        self.contrast = contrast
        self.longPress = longPress
        self.action = action
    }

    var body: some View {
        let button = Button(action: longPress == nil ? action : {}) {
            label
        }
        .buttonStyle(
            OpenCodeButtonStyle(
                variant: variant,
                size: size,
                enabled: enabled,
                contrast: contrast,
                prepareHapticOnPress: longPress != nil
            )
        )
        .disabled(!enabled)
        if longPress != nil {
            button.simultaneousGesture(longPressExclusiveTap)
        } else {
            button
        }
    }

    private var longPressExclusiveTap: some Gesture {
        LongPressGesture(minimumDuration: 0.4)
            .onEnded { _ in
                guard enabled, let longPress else { return }
                OpenCodeHaptics.contextAction()
                longPress()
            }
            .exclusively(before: TapGesture().onEnded {
                guard enabled else { return }
                action()
            })
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
                .lineLimit(1)
        case let .iconSquare(icon, accessibilityLabel):
            OpenCodeIconImage(icon: icon, size: 18)
                .accessibilityLabel(accessibilityLabel ?? "")
                .accessibilityHidden(accessibilityLabel == nil)
        case let .iconCircle(icon, accessibilityLabel):
            OpenCodeIconImage(icon: icon, size: 18)
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
    let prepareHapticOnPress: Bool

    @ViewBuilder
    func makeBody(configuration: Configuration) -> some View {
        let isPressed = configuration.isPressed && enabled
        let maxWidth: CGFloat? = variant.isFullWidth ? .infinity : nil
        let iconSize: CGFloat = size == .large ? 48 : 40
        let minWidth: CGFloat? = variant.isIconOnly ? iconSize : nil
        let fixedWidth: CGFloat? = variant.isIconOnly ? iconSize : nil
        let fixedHeight: CGFloat? = variant.isIconOnly ? iconSize : nil
        Group {
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
                .opacity(isPressed ? 0.92 : 1)
                .scaleEffect(pressScale(isPressed: isPressed))
                .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
                .animation(accentEnableAnimation, value: enabled)
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
                .opacity(isPressed ? 0.92 : 1)
                .scaleEffect(pressScale(isPressed: isPressed))
                .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
                .animation(accentEnableAnimation, value: enabled)
            }
        }
        .onChange(of: configuration.isPressed) { _, pressed in
            guard prepareHapticOnPress, enabled, pressed else { return }
            OpenCodeHaptics.prepareContextAction()
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

    private var accentEnableAnimation: Animation? {
        guard contrast == .accent else { return nil }
        return .spring(response: 0.32, dampingFraction: 0.68)
    }

    private func pressScale(isPressed: Bool) -> CGFloat {
        if isPressed { return 0.98 }
        if contrast == .accent, !enabled { return 0.96 }
        return 1
    }

    private var foregroundColor: Color {
        if !enabled {
            return contrast == .accent ? OpenCodeColors.textInteractiveBase : OpenCodeColors.textBase
        }
        switch contrast {
        case .low: return OpenCodeColors.textStrong
        case .accent: return OpenCodeColors.textOnBrandBase
        case .destructive: return OpenCodeColors.surfaceBase
        case .high: return OpenCodeColors.surfaceBase
        }
    }

    private func backgroundColor(isPressed: Bool) -> Color {
        if !enabled {
            return contrast == .accent
                ? OpenCodeColors.surfaceInteractiveWeak
                : OpenCodeColors.surfaceRaisedBase
        }
        switch contrast {
        case .low:
            // Hover emphasis only — a solid textBase flash is too high-contrast.
            return isPressed ? OpenCodeColors.surfaceRaisedBaseHover : OpenCodeColors.surfaceRaisedBase
        case .accent:
            return OpenCodeColors.interactive
        case .destructive:
            return OpenCodeColors.error
        case .high:
            return OpenCodeColors.textStrong
        }
    }

    private func borderColor(isPressed: Bool) -> Color {
        if !enabled {
            return contrast == .accent
                ? OpenCodeColors.surfaceInteractiveWeak
                : OpenCodeColors.borderWeakerBase
        }
        switch contrast {
        case .low:
            return isPressed ? OpenCodeColors.borderWeakBase : OpenCodeColors.borderWeakerBase
        case .accent:
            return OpenCodeColors.interactive
        case .destructive:
            return OpenCodeColors.error
        case .high:
            return OpenCodeColors.textStrong
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
