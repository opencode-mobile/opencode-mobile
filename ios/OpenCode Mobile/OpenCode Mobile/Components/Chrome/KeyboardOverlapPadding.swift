import SwiftUI
import UIKit

/// Shared keyboard-frame overlap math for views that own keyboard avoidance
/// instead of relying on SwiftUI's automatic keyboard safe area.
enum KeyboardOverlap {
    private static var lastEndFrame: CGRect?

    static func amount(from notification: Notification, in hostFrame: CGRect) -> CGFloat {
        guard
            let frameValue = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue
        else {
            return 0
        }
        let keyboardFrame = frameValue.cgRectValue
        lastEndFrame = keyboardFrame
        return overlap(of: keyboardFrame, in: hostFrame)
    }

    /// Close match for the system keyboard's own curve (an overdamped spring, about half a
    /// second). Chrome that follows the keyboard on this curve moves with it, not behind it.
    static let animation = Animation.interpolatingSpring(mass: 3, stiffness: 1000, damping: 500, initialVelocity: 0)

    static func animationDuration(from notification: Notification) -> Double {
        (notification.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? NSNumber)?
            .doubleValue ?? 0.25
    }

    /// Last keyboard end frame, for sheets that present while the keyboard is
    /// already visible and will not get a fresh `keyboardWillChangeFrame`.
    static func currentAmount(in hostFrame: CGRect) -> CGFloat {
        overlap(of: lastEndFrame, in: hostFrame)
    }

    static func noteHidden() {
        lastEndFrame = .zero
    }

    static func resetCachedFrameForTesting() {
        lastEndFrame = nil
    }

    /// `.dismiss` sheets dismiss on first present and when the keyboard policy
    /// becomes `.dismiss` while already presented (Model picker Back to providers).
    static func shouldDismissSheetKeyboard(
        isPresented: Bool,
        wasPresented: Bool,
        keyboard: OpenCodeBottomSheetKeyboard,
        presentedKeyboard: OpenCodeBottomSheetKeyboard?
    ) -> Bool {
        isPresented
            && keyboard == .dismiss
            && (!wasPresented || presentedKeyboard != .dismiss)
    }

    /// `.dismiss` bottom sheets resign the current first responder so they are not
    /// stacked over an already-visible software keyboard.
    static func dismissSoftwareKeyboard() {
        let keyWindow = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)
        if let keyWindow {
            keyWindow.endEditing(true)
            return
        }
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder),
            to: nil,
            from: nil,
            for: nil
        )
    }

    private static func overlap(of keyboardFrame: CGRect?, in hostFrame: CGRect) -> CGFloat {
        guard let keyboardFrame, keyboardFrame.height > 0, keyboardFrame.minY < hostFrame.maxY else {
            return 0
        }
        return max(0, hostFrame.maxY - keyboardFrame.minY)
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
    /// Called just before `overlap` changes with the keyboard, so callers can move
    /// dependent content on `KeyboardOverlap.animation` too.
    var onKeyboardMove: (() -> Void)?

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
                    // Follows the keyboard on its own curve. An ease-out of the reported
                    // duration ran ahead of it and then dragged, which read as lag.
                    let amount = KeyboardOverlap.amount(
                        from: notification,
                        in: geometry.frame(in: .global)
                    )
                    guard abs(amount - overlap) > 0.5 else { return }
                    onKeyboardMove?()
                    withAnimation(KeyboardOverlap.animation) { overlap = amount }
                }
                .onReceive(
                    NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)
                ) { _ in
                    KeyboardOverlap.noteHidden()
                    guard overlap > 0.5 else { return }
                    onKeyboardMove?()
                    withAnimation(KeyboardOverlap.animation) { overlap = 0 }
                }
        }
        .ignoresSafeArea(.keyboard)
        .ignoresSafeArea(edges: .bottom)
    }
}

extension View {
    func keyboardOverlapPadding(
        overlap: Binding<CGFloat>,
        onKeyboardMove: (() -> Void)? = nil
    ) -> some View {
        modifier(KeyboardOverlapPaddingModifier(overlap: overlap, onKeyboardMove: onKeyboardMove))
    }
}
