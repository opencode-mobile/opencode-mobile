import ObjectiveC
import SwiftUI
import UIKit

/// Restores UIKit's native interactive-pop gesture while SwiftUI hides the
/// system navigation bar in favor of `OpenCodeTopBar`.
struct OpenCodeInteractivePopGestureBridge: UIViewRepresentable {
    func makeUIView(context: Context) -> InteractivePopGestureReaderView {
        InteractivePopGestureReaderView()
    }

    func updateUIView(_ uiView: InteractivePopGestureReaderView, context: Context) {
        uiView.installIfPossible()
    }

    static func dismantleUIView(_ uiView: InteractivePopGestureReaderView, coordinator: ()) {
        uiView.uninstall()
    }
}

final class InteractivePopGestureReaderView: UIView {
    private weak var coordinator: InteractivePopGestureCoordinator?

    override func didMoveToWindow() {
        super.didMoveToWindow()

        guard window != nil else {
            uninstall()
            return
        }

        // SwiftUI may add the representable before its hosting controller is in
        // the responder chain. Retry once after the current layout transaction.
        DispatchQueue.main.async { [weak self] in
            self?.installIfPossible()
        }
    }

    func installIfPossible() {
        guard window != nil, let navigationController = owningNavigationController else { return }

        let coordinator = navigationController.openCodeInteractivePopGestureCoordinator
        if self.coordinator === coordinator {
            coordinator.installAfterCurrentTransition()
            return
        }

        uninstall()
        self.coordinator = coordinator
        coordinator.register(self)
    }

    func uninstall() {
        coordinator?.unregister(self)
        coordinator = nil
    }

    private var owningNavigationController: UINavigationController? {
        var responder: UIResponder? = self
        while let current = responder {
            if let viewController = current as? UIViewController,
               let navigationController = viewController.navigationController
            {
                return navigationController
            }
            responder = current.next
        }
        return nil
    }
}

private final class InteractivePopGestureCoordinator: NSObject, UIGestureRecognizerDelegate {
    private weak var navigationController: UINavigationController?
    private weak var previousDelegate: (any UIGestureRecognizerDelegate)?
    private var previousIsEnabled: Bool?
    private let owners = NSHashTable<InteractivePopGestureReaderView>.weakObjects()

    init(navigationController: UINavigationController) {
        self.navigationController = navigationController
    }

    func register(_ owner: InteractivePopGestureReaderView) {
        owners.add(owner)
        installAfterCurrentTransition()
    }

    func installAfterCurrentTransition() {
        guard !owners.allObjects.isEmpty else { return }

        if let transitionCoordinator = navigationController?.transitionCoordinator {
            transitionCoordinator.animate(alongsideTransition: nil) { [weak self] _ in
                self?.installRecognizer()
            }
        } else {
            installRecognizer()
        }
    }

    private func installRecognizer() {
        guard !owners.allObjects.isEmpty else { return }

        guard
            let recognizer = navigationController?.interactivePopGestureRecognizer,
            recognizer.delegate !== self
        else { return }

        if previousIsEnabled == nil {
            previousDelegate = recognizer.delegate
            previousIsEnabled = recognizer.isEnabled
        }
        recognizer.delegate = self
        recognizer.isEnabled = true
    }

    func unregister(_ owner: InteractivePopGestureReaderView) {
        owners.remove(owner)
        guard owners.allObjects.isEmpty else { return }
        restoreRecognizer()
    }

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard let navigationController else { return false }
        return navigationController.viewControllers.count > 1
            && navigationController.transitionCoordinator == nil
    }

    private func restoreRecognizer() {
        guard let recognizer = navigationController?.interactivePopGestureRecognizer else { return }
        guard recognizer.delegate === self else { return }

        recognizer.delegate = previousDelegate
        if let previousIsEnabled {
            recognizer.isEnabled = previousIsEnabled
        }
        previousDelegate = nil
        previousIsEnabled = nil
    }
}

private var interactivePopGestureCoordinatorKey: UInt8 = 0

private extension UINavigationController {
    var openCodeInteractivePopGestureCoordinator: InteractivePopGestureCoordinator {
        if let coordinator = objc_getAssociatedObject(
            self,
            &interactivePopGestureCoordinatorKey
        ) as? InteractivePopGestureCoordinator {
            return coordinator
        }

        let coordinator = InteractivePopGestureCoordinator(navigationController: self)
        objc_setAssociatedObject(
            self,
            &interactivePopGestureCoordinatorKey,
            coordinator,
            .OBJC_ASSOCIATION_RETAIN_NONATOMIC
        )
        return coordinator
    }
}
