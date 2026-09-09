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
}

/// Pads the bottom of `content` by the software-keyboard overlap and opts out of
/// SwiftUI's keyboard safe area so system and manual insets do not double-count.
///
/// Needed for Session Chat: the UIKit timeline host plus `UITextView` composer
/// defeat automatic keyboard avoidance on device.
///
/// Apply this only below the top bar (timeline + floating composer). Bottom
/// alignment is load-bearing for OPE-118: when the composer grows taller than
/// the space above the keyboard, top alignment overflows downward into the
/// keyboard and covers send/attach. Bottom alignment keeps that edge pinned so
/// growth extends upward into the timeline. Wrapping the top bar in the same
/// GeometryReader left a keyboard-sized gap above the chrome (title/back under
/// the status bar / Dynamic Island).
struct KeyboardOverlapPaddingModifier: ViewModifier {
    @State private var overlap: CGFloat = 0

    func body(content: Content) -> some View {
        GeometryReader { geometry in
            content
                .padding(.bottom, overlap)
                .frame(
                    width: geometry.size.width,
                    height: geometry.size.height,
                    alignment: .bottom
                )
                .onReceive(
                    NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)
                ) { notification in
                    // Instant snap — keyboard-matched animation felt laggy; revisit later.
                    overlap = KeyboardOverlap.amount(
                        from: notification,
                        in: geometry.frame(in: .global)
                    )
                }
                .onReceive(
                    NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)
                ) { _ in
                    overlap = 0
                }
        }
        .ignoresSafeArea(.keyboard)
    }
}

extension View {
    func keyboardOverlapPadding() -> some View {
        modifier(KeyboardOverlapPaddingModifier())
    }
}
