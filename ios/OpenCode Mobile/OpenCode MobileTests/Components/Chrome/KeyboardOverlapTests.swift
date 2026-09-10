import UIKit
import XCTest
@testable import OpenCode_Mobile

final class KeyboardOverlapTests: XCTestCase {
    override func setUp() {
        super.setUp()
        KeyboardOverlap.resetCachedFrameForTesting()
    }

    override func tearDown() {
        KeyboardOverlap.resetCachedFrameForTesting()
        super.tearDown()
    }

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

    func testCurrentAmountReplaysLastKeyboardFrameWithoutANewNotification() {
        let host = CGRect(x: 0, y: 0, width: 390, height: 800)
        let keyboard = CGRect(x: 0, y: 500, width: 390, height: 300)
        _ = KeyboardOverlap.amount(from: makeKeyboardNotification(frame: keyboard), in: host)

        XCTAssertEqual(KeyboardOverlap.currentAmount(in: host), 300)
    }

    func testCurrentAmountIsZeroBeforeAnyKeyboardNotification() {
        let host = CGRect(x: 0, y: 0, width: 390, height: 800)
        XCTAssertEqual(KeyboardOverlap.currentAmount(in: host), 0)
    }

    func testNoteHiddenClearsCachedOverlap() {
        let host = CGRect(x: 0, y: 0, width: 390, height: 800)
        let keyboard = CGRect(x: 0, y: 500, width: 390, height: 300)
        _ = KeyboardOverlap.amount(from: makeKeyboardNotification(frame: keyboard), in: host)

        KeyboardOverlap.noteHidden()

        XCTAssertEqual(KeyboardOverlap.currentAmount(in: host), 0)
    }

    func testDismissesActionListOnFirstPresent() {
        XCTAssertTrue(
            KeyboardOverlap.shouldDismissActionListKeyboard(
                isPresented: true,
                wasPresented: false,
                contentMode: .actionList,
                presentedContentMode: nil
            )
        )
    }

    func testDoesNotDismissFormOnFirstPresent() {
        XCTAssertFalse(
            KeyboardOverlap.shouldDismissActionListKeyboard(
                isPresented: true,
                wasPresented: false,
                contentMode: .form,
                presentedContentMode: nil
            )
        )
    }

    func testDismissesWhenSwitchingFromFormToActionList() {
        XCTAssertTrue(
            KeyboardOverlap.shouldDismissActionListKeyboard(
                isPresented: true,
                wasPresented: true,
                contentMode: .actionList,
                presentedContentMode: .form
            )
        )
    }

    func testDoesNotDismissRepeatedActionListUpdates() {
        XCTAssertFalse(
            KeyboardOverlap.shouldDismissActionListKeyboard(
                isPresented: true,
                wasPresented: true,
                contentMode: .actionList,
                presentedContentMode: .actionList
            )
        )
    }

    private func makeKeyboardNotification(frame: CGRect) -> Notification {
        Notification(
            name: UIResponder.keyboardWillChangeFrameNotification,
            object: nil,
            userInfo: [UIResponder.keyboardFrameEndUserInfoKey: NSValue(cgRect: frame)]
        )
    }
}
