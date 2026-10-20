import XCTest

/// Bottom sheet behavior on the `OPENCODE_UI_TEST_BOTTOM_SHEET` fixture: height
/// choice and latch, dismiss by Close, scrim and drag, and modal accessibility.
/// Hardware Escape is covered by `OpenCodeBottomSheetEscapeTests`: XCUITest only
/// delivers hardware keys with the simulator's hardware keyboard connected.
final class BottomSheetUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchEnvironment["OPENCODE_UI_TEST_BOTTOM_SHEET"] = "1"
        app.launch()
    }

    private var title: XCUIElement { app.staticTexts["Test sheet"] }

    private func present(_ button: String) {
        app.buttons[button].tap()
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        // Let the enter motion settle before reading frames.
        waitForStableFrame(of: title)
    }

    private func waitForStableFrame(of element: XCUIElement) {
        var last = element.frame
        for _ in 0 ..< 20 {
            Thread.sleep(forTimeInterval: 0.1)
            let next = element.frame
            if abs(next.minY - last.minY) < 0.5 { return }
            last = next
        }
    }

    private func assertDismissed(count: Int, file: StaticString = #filePath, line: UInt = #line) {
        let gone = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: title)
        XCTAssertEqual(XCTWaiter.wait(for: [gone], timeout: 3), .completed, file: file, line: line)
        XCTAssertTrue(app.staticTexts["Dismissals: \(count)"].exists, file: file, line: line)
    }

    @MainActor
    func testFitContentWrapsFollowsBodyAndLatchesAtHalf() {
        let window = app.windows.firstMatch.frame
        present("Show short sheet")
        let compactTop = title.frame.minY
        XCTAssertGreaterThan(compactTop, window.midY + 48, "short body stays compact")

        app.buttons["Grow body"].tap()
        waitForStableFrame(of: title)
        XCTAssertLessThan(title.frame.minY, compactTop, "taller body raises the sheet")
        app.buttons["Shrink body"].tap()
        waitForStableFrame(of: title)
        XCTAssertEqual(title.frame.minY, compactTop, accuracy: 1, "compact sheet shrinks with its body")

        app.buttons["Close"].tap()
        assertDismissed(count: 1)

        present("Show long sheet")
        let halfTop = title.frame.minY
        XCTAssertLessThan(halfTop, window.midY + 24, "long body rests at half height")
        app.buttons["Shrink body"].tap()
        waitForStableFrame(of: title)
        XCTAssertEqual(title.frame.minY, halfTop, accuracy: 1, "half height latches")
    }

    @MainActor
    func testCloseDismisses() {
        present("Show short sheet")
        app.buttons["Close"].tap()
        assertDismissed(count: 1)
    }

    @MainActor
    func testScrimDismissesButSheetTapsDoNot() {
        present("Show short sheet")
        title.tap()
        XCTAssertTrue(app.staticTexts["Dismissals: 0"].exists)
        XCTAssertTrue(title.exists)

        app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.15)).tap()
        assertDismissed(count: 1)
    }

    @MainActor
    func testHeaderDragFollowsFingerAndDismissesPastThreshold() {
        present("Show short sheet")
        let restingTop = title.frame.minY

        // A short drag springs back.
        let start = title.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        start.press(forDuration: 0.1, thenDragTo: start.withOffset(CGVector(dx: 0, dy: 40)), withVelocity: .slow, thenHoldForDuration: 0.1)
        waitForStableFrame(of: title)
        XCTAssertTrue(app.staticTexts["Dismissals: 0"].exists)
        XCTAssertEqual(title.frame.minY, restingTop, accuracy: 1)

        let dragStart = title.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        dragStart.press(forDuration: 0.1, thenDragTo: dragStart.withOffset(CGVector(dx: 0, dy: 200)), withVelocity: .slow, thenHoldForDuration: 0.1)
        assertDismissed(count: 1)
    }

    @MainActor
    func testModalAccessibility() {
        present("Show short sheet")
        XCTAssertTrue(app.buttons["Close"].exists)
        XCTAssertTrue(title.exists)
        let selected = app.buttons["Selected row"]
        XCTAssertTrue(selected.exists)
        XCTAssertTrue(selected.isSelected, "picker choice exposes the selected trait")
        XCTAssertFalse(app.buttons["Grow body"].isSelected)
        // The sheet is modal: the screen behind it cannot be activated.
        XCTAssertFalse(app.buttons["Show short sheet"].isHittable)
        XCTAssertFalse(app.buttons["Show long sheet"].isHittable)
    }
}
