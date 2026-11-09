import SwiftUI

enum OpenCodeButtonVariant {
    case fullWidthText(title: OpenCodeText)
    case compactText(title: OpenCodeText)
    case link(title: OpenCodeText)
    case iconSquare(OpenCodeIcon, accessibilityLabel: OpenCodeText?)
    case iconCircle(OpenCodeIcon, accessibilityLabel: OpenCodeText?)
    /// The glyph text is shown verbatim (a symbol such as "/", not a word).
    case iconCircleText(String, accessibilityLabel: OpenCodeText?)
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

/// Where a button sits in an `OpenCodeSplitButton`; `.whole` for every standalone button.
enum OpenCodeButtonSegment {
    case whole
    /// The split button's label half: outer corners on the leading side.
    case leading
    /// The split button's narrow menu half: outer corners on the trailing side.
    case trailing

    /// Corner of the joined edges, kept small so the halves read as one control.
    static let innerCornerRadius: CGFloat = 3
    /// Width of the trailing menu half.
    static let trailingWidth: CGFloat = 32

    var shape: UnevenRoundedRectangle {
        let outer = OpenCodeRadius.small
        let inner = Self.innerCornerRadius
        return switch self {
        case .whole:
            UnevenRoundedRectangle(cornerRadii: .init(
                topLeading: outer, bottomLeading: outer, bottomTrailing: outer, topTrailing: outer
            ), style: .circular)
        case .leading:
            UnevenRoundedRectangle(cornerRadii: .init(
                topLeading: outer, bottomLeading: outer, bottomTrailing: inner, topTrailing: inner
            ), style: .circular)
        case .trailing:
            UnevenRoundedRectangle(cornerRadii: .init(
                topLeading: inner, bottomLeading: inner, bottomTrailing: outer, topTrailing: outer
            ), style: .circular)
        }
    }
}

struct OpenCodeButton: View {
    let variant: OpenCodeButtonVariant
    let size: OpenCodeButtonSize
    let enabled: Bool
    let contrast: OpenCodeButtonContrast
    let action: () -> Void
    let longPress: (() -> Void)?
    /// Set only by `OpenCodeSplitButton` (see `segmented(_:)`).
    private(set) var segment = OpenCodeButtonSegment.whole

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

    /// The same button drawn as one half of an `OpenCodeSplitButton`.
    func segmented(_ segment: OpenCodeButtonSegment) -> OpenCodeButton {
        var copy = self
        copy.segment = segment
        return copy
    }

    var body: some View {
        content
            .openCodeKeyboardFocusable(variant.focusRing, enabled: enabled, activate: action)
            // Right-click opens what a long-press opens.
            .openCodeSecondaryClick(enabled ? longPress : nil)
    }

    @ViewBuilder
    private var content: some View {
        if case let .link(title) = variant {
            Button(action: action) {
                Text(openCode: title)
                    .font(OpenCodeTypography.body)
                    .fontWeight(.semibold)
                    .underline()
                    .foregroundStyle(enabled ? OpenCodeColors.textStrong : OpenCodeColors.textBase)
            }
            .buttonStyle(.plain)
            .disabled(!enabled)
        } else {
            filledButton
        }
    }

    @ViewBuilder
    private var filledButton: some View {
        let button = Button(action: longPress == nil ? action : {}) {
            label
        }
        .buttonStyle(
            OpenCodeButtonStyle(
                variant: variant,
                size: size,
                enabled: enabled,
                contrast: contrast,
                prepareHapticOnPress: longPress != nil,
                segment: segment
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
            Text(openCode: title)
                .font(OpenCodeTypography.button)
        case let .compactText(title):
            Text(openCode: title)
                .font(OpenCodeTypography.button)
                .lineLimit(1)
        case let .iconSquare(icon, accessibilityLabel):
            OpenCodeIconImage(icon: icon, size: segment == .trailing ? 16 : 18)
                .accessibilityLabel(openCode: accessibilityLabel ?? .empty)
                .accessibilityHidden(accessibilityLabel == nil)
        case let .iconCircle(icon, accessibilityLabel):
            OpenCodeIconImage(icon: icon, size: 18)
                .accessibilityLabel(openCode: accessibilityLabel ?? .empty)
                .accessibilityHidden(accessibilityLabel == nil)
        case let .iconCircleText(text, accessibilityLabel):
            Text(verbatim: text)
                .font(.system(size: 18, weight: .semibold))
                .accessibilityLabel(openCode: accessibilityLabel ?? .empty)
                .accessibilityHidden(accessibilityLabel == nil)
        case let .link(title):
            Text(openCode: title)
                .font(OpenCodeTypography.body)
                .fontWeight(.semibold)
                .underline()
        }
    }
}

private struct OpenCodeButtonStyle: ButtonStyle {
    let variant: OpenCodeButtonVariant
    let size: OpenCodeButtonSize
    let enabled: Bool
    let contrast: OpenCodeButtonContrast
    let prepareHapticOnPress: Bool
    let segment: OpenCodeButtonSegment

    @ViewBuilder
    func makeBody(configuration: Configuration) -> some View {
        let isPressed = configuration.isPressed && enabled
        let maxWidth: CGFloat? = variant.isFullWidth ? .infinity : nil
        let iconSize: CGFloat = size == .large ? 48 : 40
        let iconWidth = segment == .trailing ? OpenCodeButtonSegment.trailingWidth : iconSize
        let minWidth: CGFloat? = variant.isIconOnly ? iconWidth : nil
        let fixedWidth: CGFloat? = variant.isIconOnly ? iconWidth : nil
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
                .background { chromeFill(isPressed: isPressed, in: Circle()) }
                .overlay(
                    Circle()
                        .strokeBorder(borderColor(isPressed: isPressed), lineWidth: 1)
                )
                .overlay { lowContrastPressWash(isPressed: isPressed, in: Circle()) }
                .opacity(pressOpacity(isPressed: isPressed))
                .scaleEffect(pressScale(isPressed: isPressed))
                .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
                .animation(accentEnableAnimation, value: enabled)
            } else if segment != .whole {
                baseLabel(
                    configuration: configuration,
                    minWidth: minWidth,
                    maxWidth: maxWidth,
                    fixedWidth: fixedWidth,
                    fixedHeight: fixedHeight
                )
                .background { chromeFill(isPressed: isPressed, in: segment.shape) }
                .overlay(segment.shape.strokeBorder(borderColor(isPressed: isPressed), lineWidth: 1))
                .overlay { lowContrastPressWash(isPressed: isPressed, in: segment.shape) }
                .opacity(pressOpacity(isPressed: isPressed))
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
                .background { chromeFill(isPressed: isPressed, in: RoundedRectangle(cornerRadius: 8)) }
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .strokeBorder(borderColor(isPressed: isPressed), lineWidth: 1)
                )
                .overlay {
                    lowContrastPressWash(
                        isPressed: isPressed,
                        in: RoundedRectangle(cornerRadius: 8)
                    )
                }
                .opacity(pressOpacity(isPressed: isPressed))
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

    @ViewBuilder
    private func chromeFill<S: Shape>(isPressed: Bool, in shape: S) -> some View {
        if contrast == .accent, !enabled {
            ZStack {
                shape.fill(OpenCodeColors.surfaceRaisedBase)
                shape.fill(OpenCodeColors.interactive.opacity(0.10))
            }
        } else {
            shape.fill(backgroundColor(isPressed: isPressed))
        }
    }

    private var accentEnableAnimation: Animation? {
        guard contrast == .accent else { return nil }
        return .spring(response: 0.32, dampingFraction: 0.68)
    }

    @ViewBuilder
    private func lowContrastPressWash<S: Shape>(isPressed: Bool, in shape: S) -> some View {
        if contrast == .low {
            shape
                .fill(
                    OpenCodeColors.textStrong.opacity(
                        isPressed ? OpenCodeSubtlePress.washOpacity : 0
                    )
                )
                .allowsHitTesting(false)
        }
    }

    private func pressOpacity(isPressed: Bool) -> CGFloat {
        // Low contrast is a near-match for the page. Fading it hides the press
        // in dark mode; the ink wash is the cue instead.
        if contrast == .low { return 1 }
        return isPressed ? 0.92 : 1
    }

    private func pressScale(isPressed: Bool) -> CGFloat {
        if isPressed { return contrast == .low ? OpenCodeSubtlePress.scale : 0.98 }
        if contrast == .accent, !enabled { return 0.96 }
        return 1
    }

    private var foregroundColor: Color {
        if !enabled {
            return contrast == .accent ? OpenCodeColors.textWeak : OpenCodeColors.textBase
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
            return OpenCodeColors.surfaceRaisedBase
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
                ? OpenCodeColors.interactive.opacity(0.10)
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
    var focusRing: OpenCodeFocusRingShape {
        switch self {
        case .iconCircle, .iconCircleText: .circle
        case .link: .roundedRectangle(cornerRadius: OpenCodeRadius.small)
        case .fullWidthText, .compactText, .iconSquare: .roundedRectangle(cornerRadius: OpenCodeRadius.small)
        }
    }

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
