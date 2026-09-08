import ObjectiveC
import SwiftUI
import UIKit

enum InteractivePopBeginDecision: Equatable {
    case consumeOverlay
    case allowNavigationPop
    case deny
}

enum InteractivePopBeginPolicy {
    static func decision(
        overlayConsumerOnTopViewController: Bool,
        navigationControllerCount: Int,
        isTransitioning: Bool
    ) -> InteractivePopBeginDecision {
        if isTransitioning {
            return .deny
        }
        if overlayConsumerOnTopViewController {
            return .consumeOverlay
        }
        if navigationControllerCount > 1 {
            return .allowNavigationPop
        }
        return .deny
    }
}

/// Restores UIKit's native interactive-pop gesture while SwiftUI hides the
/// system navigation bar in favor of `OpenCodeTopBar`.
///
/// When `onConsume` is set, a leading-edge swipe invokes that action instead of
/// popping the navigation stack. Use this for overlay pages that look like
/// destinations but must not dismiss the screen underneath.
struct OpenCodeInteractivePopGestureBridge: UIViewRepresentable {
    var onConsume: (() -> Void)? = nil

    func makeUIView(context: Context) -> InteractivePopGestureReaderView {
        let view = InteractivePopGestureReaderView()
        view.onConsume = onConsume
        return view
    }

    func updateUIView(_ uiView: InteractivePopGestureReaderView, context: Context) {
        uiView.onConsume = onConsume
        uiView.installIfPossible()
    }

    static func dismantleUIView(_ uiView: InteractivePopGestureReaderView, coordinator: ()) {
        uiView.uninstall()
    }
}

final class InteractivePopGestureReaderView: UIView {
    var onConsume: (() -> Void)?
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

    var owningViewController: UIViewController? {
        var responder: UIResponder? = self
        while let current = responder {
            if let viewController = current as? UIViewController {
                return viewController
            }
            responder = current.next
        }
        return nil
    }

    private var owningNavigationController: UINavigationController? {
        owningViewController?.navigationController
    }

    func isOwnedByTopViewController(
        _ topViewController: UIViewController,
        in navigationController: UINavigationController
    ) -> Bool {
        navigationDestinationViewController(in: navigationController) === topViewController
    }

    func navigationDestinationViewController(
        in navigationController: UINavigationController
    ) -> UIViewController? {
        var current: UIViewController? = owningViewController
        while let viewController = current {
            if navigationController.viewControllers.contains(where: { $0 === viewController }) {
                return viewController
            }
            current = viewController.parent
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

        let consumeAction = overlayConsumeActionOnTopViewController()
        switch InteractivePopBeginPolicy.decision(
            overlayConsumerOnTopViewController: consumeAction != nil,
            navigationControllerCount: navigationController.viewControllers.count,
            isTransitioning: navigationController.transitionCoordinator != nil
        ) {
        case .consumeOverlay:
            consumeAction?()
            return false
        case .allowNavigationPop:
            return true
        case .deny:
            return false
        }
    }

    private func overlayConsumeActionOnTopViewController() -> (() -> Void)? {
        guard
            let navigationController,
            let topViewController = navigationController.topViewController
        else { return nil }
        return owners.allObjects.first { owner in
            owner.window != nil
                && owner.onConsume != nil
                && owner.isOwnedByTopViewController(
                    topViewController,
                    in: navigationController
                )
        }?.onConsume
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
