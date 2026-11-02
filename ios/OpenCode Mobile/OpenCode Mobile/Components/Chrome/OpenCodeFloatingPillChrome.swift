import SwiftUI

/// Shared Liquid Glass / material chrome for floating session-chat capsules
/// (Action, Review Changes, Session Todos). Callers own label content and
/// accessibility; press feedback uses `OpenCodeFloatingPillPressStyle`.
struct OpenCodeFloatingPillChrome: ViewModifier {
    let enabled: Bool
    var backing: OpenCodeFloatingPillBacking = .glass

    func body(content: Content) -> some View {
        if #available(iOS 26, *), backing == .glass {
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

/// How opaque a floating pill's body is.
enum OpenCodeFloatingPillBacking: Equatable {
    /// Liquid Glass over a mostly opaque raised surface; the timeline shows faintly through.
    case glass
    /// Fully opaque raised surface with a hairline and shadow, for pills that stack over
    /// timeline text (the expanded activity stack) and must not show it through.
    case solid
}

struct OpenCodeFloatingPillPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .opacity(configuration.isPressed ? 0.92 : 1)
            .animation(.smooth(duration: 0.16), value: configuration.isPressed)
    }
}

extension View {
    func openCodeFloatingPillChrome(
        enabled: Bool = true,
        backing: OpenCodeFloatingPillBacking = .glass
    ) -> some View {
        modifier(OpenCodeFloatingPillChrome(enabled: enabled, backing: backing))
    }
}
