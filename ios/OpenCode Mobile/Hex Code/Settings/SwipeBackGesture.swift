import ObjectiveC
import SwiftUI
import UIKit

/// Keeps the edge swipe back working on a pushed page that hides the system navigation bar and
/// draws its own top bar.
struct SwipeBackGesture: UIViewRepresentable {
    func makeUIView(context: Context) -> SwipeBackGestureView {
        SwipeBackGestureView()
    }

    func updateUIView(_ uiView: SwipeBackGestureView, context: Context) {
        uiView.install()
    }
}

final class SwipeBackGestureView: UIView {
    override func didMoveToWindow() {
        super.didMoveToWindow()
        // SwiftUI may add the view before its hosting controller is in the responder chain.
        DispatchQueue.main.async { [weak self] in self?.install() }
    }

    func install() {
        guard window != nil, let navigationController = owningNavigationController,
              let recognizer = navigationController.interactivePopGestureRecognizer else { return }
        let delegate = navigationController.swipeBackDelegate
        guard recognizer.delegate !== delegate else { return }
        recognizer.delegate = delegate
        recognizer.isEnabled = true
    }

    private var owningNavigationController: UINavigationController? {
        var responder: UIResponder? = self
        while let current = responder {
            if let viewController = current as? UIViewController {
                return viewController.navigationController
            }
            responder = current.next
        }
        return nil
    }
}

/// Lets the swipe begin only when there is a page to go back to and no transition is running.
private final class SwipeBackDelegate: NSObject, UIGestureRecognizerDelegate {
    weak var navigationController: UINavigationController?

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard let navigationController else { return false }
        return navigationController.viewControllers.count > 1 && navigationController.transitionCoordinator == nil
    }
}

private var swipeBackDelegateKey: UInt8 = 0

private extension UINavigationController {
    var swipeBackDelegate: SwipeBackDelegate {
        if let delegate = objc_getAssociatedObject(self, &swipeBackDelegateKey) as? SwipeBackDelegate {
            return delegate
        }
        let delegate = SwipeBackDelegate()
        delegate.navigationController = self
        objc_setAssociatedObject(self, &swipeBackDelegateKey, delegate, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        return delegate
    }
}
