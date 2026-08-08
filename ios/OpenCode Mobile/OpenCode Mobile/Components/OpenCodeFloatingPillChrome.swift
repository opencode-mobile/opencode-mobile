import SwiftUI

/// Shared Liquid Glass / material chrome for floating session-chat capsules
/// (Action, Review Changes, Session Todos). Callers own label content and
/// accessibility; press feedback uses `OpenCodeFloatingPillPressStyle`.
struct OpenCodeFloatingPillChrome: ViewModifier {
    let enabled: Bool

    func body(content: Content) -> some View {
        if #available(iOS 26, *) {
            content
                .background(
                    OpenCodeColors.surfaceRaisedBase.opacity(enabled ? 0.92 : 0.7),
                    in: Capsule()
                )
                // Non-interactive glass: `.interactive()` adds a loud lens press;
                // press feedback stays on OpenCodeFloatingPillPressStyle.
                .glassEffect(.regular, in: .capsule)
                .opacity(enabled ? 1 : 0.72)
        } else {
            content
                .background(OpenCodeColors.surfaceRaisedBase, in: Capsule())
                .overlay(
                    Capsule()
                        .strokeBorder(OpenCodeColors.borderWeakerBase, lineWidth: 1)
                )
                .shadow(
                    color: enabled ? Color.black.opacity(0.12) : .clear,
                    radius: 4,
                    y: 2
                )
                .opacity(enabled ? 1 : 0.72)
        }
    }
}

struct OpenCodeFloatingPillPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .opacity(configuration.isPressed ? 0.78 : 1)
            .animation(.smooth(duration: 0.16), value: configuration.isPressed)
    }
}

extension View {
    func openCodeFloatingPillChrome(enabled: Bool = true) -> some View {
        modifier(OpenCodeFloatingPillChrome(enabled: enabled))
    }
}
