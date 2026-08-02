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
                    let next = KeyboardOverlap.amount(
                        from: notification,
                        in: geometry.frame(in: .global)
                    )
                    withAnimation(.easeOut(duration: KeyboardOverlap.animationDuration(from: notification))) {
                        overlap = next
                    }
                }
                .onReceive(
                    NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)
                ) { notification in
                    withAnimation(.easeOut(duration: KeyboardOverlap.animationDuration(from: notification))) {
                        overlap = 0
                    }
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
