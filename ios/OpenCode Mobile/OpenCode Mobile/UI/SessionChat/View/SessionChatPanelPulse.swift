import SwiftUI

extension View {
    /// Briefly pulses the permission or question panel each time `trigger` changes,
    /// so a tap on a pill shows where the request opened. Reduce Motion swaps the
    /// bounce for a short fade.
    func sessionChatPanelPulse(trigger: Int) -> some View {
        modifier(SessionChatPanelPulse(trigger: trigger))
    }
}

private struct SessionChatPanelPulse: ViewModifier {
    let trigger: Int

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.keyframeAnimator(initialValue: 1.0, trigger: trigger) { view, value in
            view
                .scaleEffect(reduceMotion ? 1 : value, anchor: .bottom)
                .opacity(reduceMotion ? value : 1)
        } keyframes: { _ in
            if reduceMotion {
                LinearKeyframe(0.55, duration: 0.12)
                LinearKeyframe(1.0, duration: 0.3)
            } else {
                SpringKeyframe(1.03, duration: 0.14)
                SpringKeyframe(1.0, duration: 0.36, spring: .bouncy)
            }
        }
    }
}
