import SwiftUI
import UIKit

/// Configures the underlying `UIScrollView.keyboardDismissMode`.
///
/// SwiftUI's `scrollDismissesKeyboard` often fails when the focused field sits
/// outside the scroll view (Session Chat composer is a sibling of the timeline).
/// UIKit's dismiss mode resigns any first responder when the user drags the
/// scroll view, matching Messages-style chat composers.
struct ScrollViewKeyboardDismissModeBridge: UIViewRepresentable {
    var mode: UIScrollView.KeyboardDismissMode

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        // SwiftUI may insert this representable before the scroll view exists.
        DispatchQueue.main.async {
            guard let scrollView = uiView.openCodeEnclosingScrollView() else { return }
            if scrollView.keyboardDismissMode != mode {
                scrollView.keyboardDismissMode = mode
            }
        }
    }
}

extension View {
    func scrollViewKeyboardDismissMode(_ mode: UIScrollView.KeyboardDismissMode) -> some View {
        background {
            ScrollViewKeyboardDismissModeBridge(mode: mode)
        }
    }
}

private extension UIView {
    func openCodeEnclosingScrollView() -> UIScrollView? {
        var current: UIView? = superview
        while let view = current {
            if let scrollView = view as? UIScrollView {
                return scrollView
            }
            current = view.superview
        }
        return nil
    }
}
