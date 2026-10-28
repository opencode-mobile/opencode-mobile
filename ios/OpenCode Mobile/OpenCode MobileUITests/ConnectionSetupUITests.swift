import XCTest

/// Add server's manual form (#827): the host field keeps the form while it is
/// focused and typed into, and the keyboard goes away when Add server closes.
/// No server is needed: the test never taps Connect.
final class ConnectionSetupUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
    }

    @MainActor
    func testHostFieldKeepsManualFormWhileTyping() throws {
        openAddServer()
        app.buttons["Use a host and password"].tap()

        let host = app.textFields["Host"]
        XCTAssertTrue(host.waitForExistence(timeout: 5))
        host.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["Use a host and password"].exists, "Focusing the host field left the manual form.")
        host.typeText("203.0.113.5:4096")
        XCTAssertEqual(host.value as? String, "203.0.113.5:4096")

        // Servers' Back is still in the tree under the cover; the cover's is last.
        app.buttons.matching(identifier: "Back").allElementsBoundByIndex.last?.tap()
        XCTAssertTrue(app.buttons["Add server"].waitForExistence(timeout: 5))
        let keyboardGone = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"),
            object: app.keyboards.firstMatch
        )
        XCTAssertEqual(XCTWaiter.wait(for: [keyboardGone], timeout: 3), .completed, "The keyboard outlived Add server.")
    }

    @MainActor
    private func openAddServer() {
        let skip = app.buttons["Skip"]
        if skip.waitForExistence(timeout: 3) {
            skip.tap()
        }
        let settings = app.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 10))
        settings.tap()
        let servers = app.staticTexts["Servers"]
        XCTAssertTrue(servers.waitForExistence(timeout: 5))
        servers.tap()
        let addServer = app.buttons["Add server"]
        XCTAssertTrue(addServer.waitForExistence(timeout: 5))
        addServer.tap()
        XCTAssertTrue(app.buttons["Use a host and password"].waitForExistence(timeout: 5))
    }
}
