import UIKit
import XCTest
@testable import OpenCode_Mobile

final class KeyboardOverlapTests: XCTestCase {
    func testAmountReturnsOverlapWhenKeyboardCoversHostBottom() {
        let host = CGRect(x: 0, y: 0, width: 390, height: 800)
        let keyboard = CGRect(x: 0, y: 500, width: 390, height: 300)
        let notification = makeKeyboardNotification(frame: keyboard)

        XCTAssertEqual(KeyboardOverlap.amount(from: notification, in: host), 300)
    }

    func testAmountReturnsZeroWhenKeyboardIsBelowHost() {
        let host = CGRect(x: 0, y: 0, width: 390, height: 400)
        let keyboard = CGRect(x: 0, y: 500, width: 390, height: 300)
        let notification = makeKeyboardNotification(frame: keyboard)

        XCTAssertEqual(KeyboardOverlap.amount(from: notification, in: host), 0)
    }

    func testAmountReturnsZeroForDismissedKeyboardFrame() {
        let host = CGRect(x: 0, y: 0, width: 390, height: 800)
        let keyboard = CGRect(x: 0, y: 800, width: 390, height: 0)
        let notification = makeKeyboardNotification(frame: keyboard)

        XCTAssertEqual(KeyboardOverlap.amount(from: notification, in: host), 0)
    }

    func testAnimationDurationFallsBackWhenMissing() {
        let notification = Notification(name: UIResponder.keyboardWillChangeFrameNotification)
        XCTAssertEqual(KeyboardOverlap.animationDuration(from: notification), 0.25)
    }

    func testAnimationUsesSmoothCurveForPrivateKeyboardCurve() {
        let notification = makeKeyboardNotification(
            frame: CGRect(x: 0, y: 500, width: 390, height: 300),
            duration: 0.35,
            curve: 7
        )
        _ = KeyboardOverlap.animation(from: notification)
        XCTAssertEqual(KeyboardOverlap.animationDuration(from: notification), 0.35)
    }

    private func makeKeyboardNotification(
        frame: CGRect,
        duration: Double? = nil,
        curve: Int? = nil
    ) -> Notification {
        var userInfo: [AnyHashable: Any] = [
            UIResponder.keyboardFrameEndUserInfoKey: NSValue(cgRect: frame),
        ]
        if let duration {
            userInfo[UIResponder.keyboardAnimationDurationUserInfoKey] = NSNumber(value: duration)
        }
        if let curve {
            userInfo[UIResponder.keyboardAnimationCurveUserInfoKey] = NSNumber(value: curve)
        }
        return Notification(
            name: UIResponder.keyboardWillChangeFrameNotification,
            object: nil,
            userInfo: userInfo
        )
    }
}
