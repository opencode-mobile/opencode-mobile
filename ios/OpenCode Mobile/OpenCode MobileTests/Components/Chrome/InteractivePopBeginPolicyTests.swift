import Testing
import UIKit
@testable import OpenCode_Mobile

@MainActor
struct InteractivePopBeginPolicyTests {
    @Test func overlayConsumerOnTop_consumesInsteadOfPoppingSession() {
        #expect(
            InteractivePopBeginPolicy.decision(
                overlayConsumerOnTopViewController: true,
                navigationControllerCount: 2,
                isTransitioning: false
            ) == .consumeOverlay
        )
    }

    @Test func pushedScreenWithoutOverlay_allowsNavigationPop() {
        #expect(
            InteractivePopBeginPolicy.decision(
                overlayConsumerOnTopViewController: false,
                navigationControllerCount: 2,
                isTransitioning: false
            ) == .allowNavigationPop
        )
    }

    @Test func overlayUnderPushedDestination_allowsNavigationPop() {
        #expect(
            InteractivePopBeginPolicy.decision(
                overlayConsumerOnTopViewController: false,
                navigationControllerCount: 3,
                isTransitioning: false
            ) == .allowNavigationPop
        )
    }

    @Test func rootScreen_deniesPop() {
        #expect(
            InteractivePopBeginPolicy.decision(
                overlayConsumerOnTopViewController: false,
                navigationControllerCount: 1,
                isTransitioning: false
            ) == .deny
        )
    }

    @Test func transitionInFlight_deniesEvenWithOverlayConsumer() {
        #expect(
            InteractivePopBeginPolicy.decision(
                overlayConsumerOnTopViewController: true,
                navigationControllerCount: 2,
                isTransitioning: true
            ) == .deny
        )
    }
}

struct OpenCodeTopBarLeadingInteractivePopTests {
    @Test func back_defaultDoesNotConsumeInteractivePop() {
        #expect(OpenCodeTopBarLeading.back(action: {}).consumesInteractivePop == false)
    }

    @Test func back_canConsumeInteractivePopForOverlayPages() {
        #expect(
            OpenCodeTopBarLeading.back(consumesInteractivePop: true, action: {}).consumesInteractivePop
        )
    }
}

@MainActor
struct InteractivePopGestureReaderOwnershipTests {
    @Test func overlayOnTopViewController_isOwnedByThatController() {
        let sessionChat = UIViewController()
        let nav = UINavigationController(rootViewController: sessionChat)
        let reader = InteractivePopGestureReaderView()
        sessionChat.view.addSubview(reader)

        #expect(reader.owningViewController === sessionChat)
        #expect(reader.isOwnedByTopViewController(sessionChat, in: nav))
    }

    @Test func overlayOnCoveredViewController_isNotOwnedByPushedTop() {
        let sessionChat = UIViewController()
        let changes = UIViewController()
        let nav = UINavigationController(rootViewController: sessionChat)
        nav.pushViewController(changes, animated: false)
        let reader = InteractivePopGestureReaderView()
        sessionChat.view.addSubview(reader)

        #expect(reader.isOwnedByTopViewController(sessionChat, in: nav))
        #expect(reader.isOwnedByTopViewController(changes, in: nav) == false)
    }
}
