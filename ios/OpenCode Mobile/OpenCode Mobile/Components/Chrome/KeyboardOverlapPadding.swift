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

    /// Action-list sheets resign the current first responder so they are not
    /// stacked over an already-visible software keyboard.
    static func dismissSoftwareKeyboard() {
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder),
            to: nil,
            from: nil,
            for: nil
        )
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
///
/// The host also ignores the home-indicator safe area so the timeline can paint
/// to the physical bottom behind the floating composer (home Session List
/// already does this via ScrollView). Callers lift the composer by the home
/// indicator when the keyboard is hidden; `overlap` is that keyboard inset.
struct KeyboardOverlapPaddingModifier: ViewModifier {
    @Binding var overlap: CGFloat

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
        .ignoresSafeArea(edges: .bottom)
    }
}

extension View {
    func keyboardOverlapPadding(overlap: Binding<CGFloat>) -> some View {
        modifier(KeyboardOverlapPaddingModifier(overlap: overlap))
    }
}
