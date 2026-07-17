import OSLog
import SwiftUI
import UIKit

/// Configures the underlying `UIScrollView.keyboardDismissMode` and reports
/// user pan begins so SwiftUI can clear focus when the field sits outside the
/// scroll view (Session Chat composer).
struct ScrollViewKeyboardDismissModeBridge: UIViewRepresentable {
    var mode: UIScrollView.KeyboardDismissMode
    var onUserPanBegan: (() -> Void)?

    func makeCoordinator() -> Coordinator {
        Coordinator(onUserPanBegan: onUserPanBegan)
    }

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.onUserPanBegan = onUserPanBegan
        // SwiftUI may insert this representable before the scroll view exists.
        DispatchQueue.main.async {
            context.coordinator.attach(from: uiView, mode: mode)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
            context.coordinator.attach(from: uiView, mode: mode)
        }
    }

    static func dismantleUIView(_ uiView: UIView, coordinator: Coordinator) {
        coordinator.detach()
    }

    final class Coordinator: NSObject {
        var onUserPanBegan: (() -> Void)?
        private weak var observedScrollView: UIScrollView?
        private weak var observedPan: UIPanGestureRecognizer?

        init(onUserPanBegan: (() -> Void)?) {
            self.onUserPanBegan = onUserPanBegan
        }

        func attach(from probe: UIView, mode: UIScrollView.KeyboardDismissMode) {
            guard let scrollView = probe.openCodeNearestScrollView() else {
                OpenCodeLog.sessionChat.debug("keyboard-dismiss: scroll view not found yet")
                return
            }

            if scrollView.keyboardDismissMode != mode {
                scrollView.keyboardDismissMode = mode
                OpenCodeLog.sessionChat.debug(
                    "keyboard-dismiss: set mode=\(String(describing: mode), privacy: .public) on \(String(describing: type(of: scrollView)), privacy: .public)"
                )
            }

            guard observedPan !== scrollView.panGestureRecognizer else { return }
            detach()
            observedScrollView = scrollView
            observedPan = scrollView.panGestureRecognizer
            scrollView.panGestureRecognizer.addTarget(self, action: #selector(handlePan(_:)))
        }

        func detach() {
            if let pan = observedPan {
                pan.removeTarget(self, action: #selector(handlePan(_:)))
            }
            observedPan = nil
            observedScrollView = nil
        }

        @objc private func handlePan(_ gesture: UIPanGestureRecognizer) {
            guard gesture.state == .began else { return }
            onUserPanBegan?()
        }
    }
}

extension View {
    func scrollViewKeyboardDismissMode(
        _ mode: UIScrollView.KeyboardDismissMode,
        onUserPanBegan: (() -> Void)? = nil
    ) -> some View {
        background {
            ScrollViewKeyboardDismissModeBridge(mode: mode, onUserPanBegan: onUserPanBegan)
        }
    }
}

private extension UIView {
    func openCodeNearestScrollView() -> UIScrollView? {
        if let ancestor = openCodeEnclosingScrollView() {
            return ancestor
        }
        var current: UIView? = self
        while let view = current {
            if let scrollView = view.openCodeFirstDescendantScrollView() {
                return scrollView
            }
            current = view.superview
        }
        return window?.openCodeFirstDescendantScrollView()
    }

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

    func openCodeFirstDescendantScrollView() -> UIScrollView? {
        if let scrollView = self as? UIScrollView {
            return scrollView
        }
        for subview in subviews {
            if let scrollView = subview.openCodeFirstDescendantScrollView() {
                return scrollView
            }
        }
        return nil
    }
}
