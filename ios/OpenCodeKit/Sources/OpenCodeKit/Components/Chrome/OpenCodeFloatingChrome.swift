import SwiftUI

/// Shared floating glass / material chrome used by Floating Dock, Session
/// Composer, and composer-anchored suggestion overlays. Callers own insets,
/// outlines, and content padding.
public struct OpenCodeFloatingChrome: ViewModifier {
    public var cornerRadius: CGFloat

    public func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        if #available(iOS 26, *) {
            content
                .glassEffect(.regular, in: .rect(cornerRadius: cornerRadius, style: .continuous))
        } else {
            // Raised fill + shadow stand-in: `.regularMaterial` alone reads too
            // low-contrast against background-base on pre–Liquid Glass OS versions.
            content
                .background(OpenCodeColors.surfaceRaisedBase.opacity(0.95), in: shape)
                .shadow(color: Color.black.opacity(0.12), radius: 8, y: 4)
        }
    }

    public init(
        cornerRadius: CGFloat
    ) {
        self.cornerRadius = cornerRadius
    }
}

/// Inner dock / composer controls: Liquid Glass on iOS 26+, a 0.05 ink wash
/// below. Do not paint an opaque raised underlay — that is what made the
/// project selector look solid on glass.
public struct OpenCodeSubtleControlChrome<S: Shape>: ViewModifier {
    public var shape: S

    public func body(content: Content) -> some View {
        if #available(iOS 26, *) {
            content.glassEffect(.regular, in: shape)
        } else {
            content.background(OpenCodeColors.textStrong.opacity(0.05), in: shape)
        }
    }

    public init(
        shape: S
    ) {
        self.shape = shape
    }
}

/// Press cue for subtle glass and wash controls. An opacity fade sinks into a
/// dark dock; a `text-strong` ink wash brightens in dark mode and darkens in
/// light mode. Not `.glassEffect(.interactive())` — that lens is too loud here.
public enum OpenCodeSubtlePress {
    public static let washOpacity: CGFloat = 0.14
    public static let scale: CGFloat = 0.97
    public static let animation: Animation = .smooth(duration: 0.16)
}

public struct OpenCodeSubtleControlPress<S: Shape>: ViewModifier {
    public var isPressed: Bool
    public var shape: S

    public func body(content: Content) -> some View {
        content
            .overlay {
                shape
                    .fill(
                        OpenCodeColors.textStrong.opacity(
                            isPressed ? OpenCodeSubtlePress.washOpacity : 0
                        )
                    )
                    .allowsHitTesting(false)
            }
            .scaleEffect(isPressed ? OpenCodeSubtlePress.scale : 1)
            .animation(OpenCodeSubtlePress.animation, value: isPressed)
    }

    public init(
        isPressed: Bool,
        shape: S
    ) {
        self.isPressed = isPressed
        self.shape = shape
    }
}

public struct OpenCodeSubtleControlPressStyle<S: Shape>: ButtonStyle {
    public var shape: S

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .modifier(OpenCodeSubtleControlPress(isPressed: configuration.isPressed, shape: shape))
    }

    public init(
        shape: S
    ) {
        self.shape = shape
    }
}

extension View {
    public func openCodeFloatingChrome(cornerRadius: CGFloat) -> some View {
        modifier(OpenCodeFloatingChrome(cornerRadius: cornerRadius))
    }

    public func openCodeSubtleControlChrome<S: Shape>(shape: S) -> some View {
        modifier(OpenCodeSubtleControlChrome(shape: shape))
    }

    public func openCodeSubtleControlPress<S: Shape>(isPressed: Bool, shape: S) -> some View {
        modifier(OpenCodeSubtleControlPress(isPressed: isPressed, shape: shape))
    }
}
