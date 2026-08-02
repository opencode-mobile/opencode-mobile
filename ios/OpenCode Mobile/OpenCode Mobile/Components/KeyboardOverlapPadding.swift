import SwiftUI
import UIKit

/// Shared keyboard-frame overlap math for views that own keyboard avoidance
/// instead of relying on SwiftUI's automatic keyboard safe area.
enum KeyboardOverlap {
    static func amount(from notification: Notification, in hostFrame: CGRect) -> CGFloat {
        guard
            let frameValue = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue
        else {
            return 0
        }
        let keyboardFrame = frameValue.cgRectValue
        // A zero or off-screen end frame means the keyboard is dismissed.
        guard keyboardFrame.height > 0, keyboardFrame.minY < hostFrame.maxY else {
            return 0
        }
        return max(0, hostFrame.maxY - keyboardFrame.minY)
    }

    static func animationDuration(from notification: Notification) -> Double {
        (notification.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? NSNumber)?
            .doubleValue ?? 0.25
    }

    /// Matches the system keyboard curve, including the private curve (raw value 7)
    /// that UIKit reports for keyboard frame changes.
    static func animation(from notification: Notification) -> Animation {
        let duration = animationDuration(from: notification)
        let curveValue = (notification.userInfo?[UIResponder.keyboardAnimationCurveUserInfoKey] as? NSNumber)?
            .intValue
        switch curveValue {
        case UIView.AnimationCurve.easeInOut.rawValue:
            return .easeInOut(duration: duration)
        case UIView.AnimationCurve.easeIn.rawValue:
            return .easeIn(duration: duration)
        case UIView.AnimationCurve.easeOut.rawValue:
            return .easeOut(duration: duration)
        case UIView.AnimationCurve.linear.rawValue:
            return .linear(duration: duration)
        default:
            // Private keyboard curve — track the keyboard instead of a soft easeOut lag.
            return .smooth(duration: duration, extraBounce: 0)
        }
    }
}

/// Pads the bottom of `content` by the software-keyboard overlap and opts out of
/// SwiftUI's keyboard safe area so system and manual insets do not double-count.
///
/// Needed for Session Chat: the UIKit timeline host plus `UITextView` composer
/// defeat automatic keyboard avoidance on device.
struct KeyboardOverlapPaddingModifier: ViewModifier {
    @State private var overlap: CGFloat = 0

    func body(content: Content) -> some View {
        GeometryReader { geometry in
            content
                .padding(.bottom, overlap)
                .frame(
                    width: geometry.size.width,
                    height: geometry.size.height,
                    alignment: .top
                )
                .onReceive(
                    NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)
                ) { notification in
                    applyOverlap(
                        KeyboardOverlap.amount(
                            from: notification,
                            in: geometry.frame(in: .global)
                        ),
                        animation: KeyboardOverlap.animation(from: notification)
                    )
                }
                .onReceive(
                    NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)
                ) { notification in
                    // Some dismiss paths omit a usable willChangeFrame end frame.
                    applyOverlap(0, animation: KeyboardOverlap.animation(from: notification))
                }
        }
        .ignoresSafeArea(.keyboard)
    }

    private func applyOverlap(_ next: CGFloat, animation: Animation) {
        guard next != overlap else { return }
        withAnimation(animation) {
            overlap = next
        }
    }
}

extension View {
    func keyboardOverlapPadding() -> some View {
        modifier(KeyboardOverlapPaddingModifier())
    }
}
