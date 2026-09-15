import XCTest

final class StoreScreenshotUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testHome() throws {
        let out = try outputDirectory()
        let app = launchApp()
        waitForHome(in: app)
        settle()
        try writeScreenshot(named: "01-home", to: out)
    }

    @MainActor
    func testChatCode() throws {
        let out = try outputDirectory()
        let app = launchApp()
        waitForHome(in: app)
        openSession(named: "Code samples request", in: app)
        XCTAssertTrue(
            app.descendants(matching: .any)["sessionChatComposer"].waitForExistence(timeout: 20),
            "Session composer did not appear."
        )
        revealCodeBlock(in: app)
        settle()
        try writeScreenshot(named: "02-chat-code", to: out)
    }

    @MainActor
    func testChatPermission() throws {
        let out = try outputDirectory()
        let app = launchApp()
        waitForHome(in: app)
        openSession(named: "Showing app permission dialog", in: app)
        XCTAssertTrue(
            app.descendants(matching: .any)["sessionChatPermission"].waitForExistence(timeout: 20),
            "Pending permission card did not appear."
        )
        XCTAssertTrue(
            app.buttons["Allow once"].waitForExistence(timeout: 5),
            "Permission Allow once action was not visible."
        )
        settle()
        try writeScreenshot(named: "03-chat-permission", to: out)
    }

    @MainActor
    func testChanges() throws {
        let out = try outputDirectory()
        let app = launchApp()
        waitForHome(in: app)
        openSession(named: "Code samples request", in: app)
        XCTAssertTrue(
            app.descendants(matching: .any)["sessionChatComposer"].waitForExistence(timeout: 20),
            "Session composer did not appear."
        )
        let more = app.buttons["More options"]
        XCTAssertTrue(more.waitForExistence(timeout: 10), "Chat overflow control did not appear.")
        more.tap()
        let review = app.buttons["Review changes"]
        XCTAssertTrue(review.waitForExistence(timeout: 5), "Review changes overflow action did not appear.")
        review.tap()
        XCTAssertTrue(
            app.descendants(matching: .any)["changesContent"].waitForExistence(timeout: 20),
            "Changes file list did not appear."
        )
        settle()
        try writeScreenshot(named: "04-changes", to: out)
    }

    @MainActor
    func testSettings() throws {
        let out = try outputDirectory()
        let app = launchApp()
        waitForHome(in: app)
        let settings = app.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 10), "Settings control did not appear.")
        settings.tap()
        XCTAssertTrue(
            app.descendants(matching: .any)["settingsContent"].waitForExistence(timeout: 10),
            "Settings screen did not appear."
        )
        XCTAssertTrue(app.staticTexts["Servers"].waitForExistence(timeout: 5))
        settle()
        try writeScreenshot(named: "05-settings", to: out)
    }

    @MainActor
    private func launchApp() -> XCUIApplication {
        let app = XCUIApplication()
        let serverURL = environmentValue("STORE_SCREENSHOT_SERVER_URL") ?? "http://127.0.0.1:4399"
        let password = environmentValue("STORE_SCREENSHOT_SERVER_PASSWORD") ?? "store-screenshots"
        app.launchEnvironment["OPENCODE_STORE_SERVER_URL"] = serverURL
        app.launchEnvironment["OPENCODE_STORE_SERVER_PASSWORD"] = password
        app.launchArguments += [
            "-OPENCODE_STORE_SERVER_URL", serverURL,
            "-OPENCODE_STORE_SERVER_PASSWORD", password,
        ]
        app.launch()
        return app
    }

    @MainActor
    private func waitForHome(in app: XCUIApplication) {
        XCTAssertTrue(
            waitForAny(
                [
                    app.descendants(matching: .any)["homeContent"].firstMatch,
                    app.buttons["New session"],
                    app.staticTexts["Code samples request"],
                ],
                timeout: 30
            ),
            "Home session list did not appear."
        )
        XCTAssertTrue(
            waitForAny(
                [
                    app.staticTexts["Live updates live"],
                    app.staticTexts["live"],
                    app.descendants(matching: .any)["liveUpdatesStatus"].firstMatch,
                ],
                timeout: 20
            ) && liveStatusIsConnected(in: app),
            "Live updates never reached connected."
        )
        XCTAssertTrue(
            waitForAny(
                [
                    app.staticTexts["Code samples request"],
                    sessionButton(named: "Code samples request", in: app),
                ],
                timeout: 20
            ),
            "Fixture session Code samples request was not listed."
        )
        XCTAssertTrue(
            waitForAny(
                [
                    app.staticTexts["Running"],
                    app.descendants(matching: .any).matching(
                        NSPredicate(format: "label CONTAINS %@", "Running")
                    ).firstMatch,
                ],
                timeout: 15
            ),
            "Running badge was not visible on the busy session."
        )
    }

    @MainActor
    private func openSession(named title: String, in app: XCUIApplication) {
        let session = sessionButton(named: title, in: app)
        XCTAssertTrue(
            waitForAny([session, app.staticTexts[title]], timeout: 20),
            "Session \(title) was not listed."
        )
        if session.exists {
            session.tap()
        } else {
            app.staticTexts[title].tap()
        }
        settle(0.4)
    }

    @MainActor
    private func revealCodeBlock(in app: XCUIApplication) {
        let code = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "OpenCodeButtonVariant")
        ).firstMatch
        if code.waitForExistence(timeout: 8) {
            return
        }
        for _ in 0 ..< 10 {
            app.swipeDown()
            if code.waitForExistence(timeout: 1.2) {
                return
            }
        }
        XCTAssertTrue(
            code.waitForExistence(timeout: 2),
            "Kotlin code block was not visible in the session transcript."
        )
    }

    private func sessionButton(named title: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(
            NSPredicate(format: "identifier == %@ OR label BEGINSWITH %@", title, title)
        ).firstMatch
    }

    private func environmentValue(_ name: String) -> String? {
        let env = ProcessInfo.processInfo.environment
        let value = env[name] ?? env["TEST_RUNNER_\(name)"]
        guard let value, !value.isEmpty else { return nil }
        return value
    }

    private func outputDirectory() throws -> URL {
        guard let path = environmentValue("STORE_SCREENSHOT_OUT") else {
            XCTFail("STORE_SCREENSHOT_OUT is unset. Forward TEST_RUNNER_STORE_SCREENSHOT_OUT from xcodebuild.")
            throw StoreScreenshotError.missingOutputDirectory
        }
        let url = URL(fileURLWithPath: path, isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func writeScreenshot(named name: String, to directory: URL) throws {
        let data = XCUIScreen.main.screenshot().pngRepresentation
        let url = directory.appendingPathComponent("\(name).png")
        try data.write(to: url, options: .atomic)
    }

    private func settle(_ seconds: TimeInterval = 0.8) {
        Thread.sleep(forTimeInterval: seconds)
    }

    @MainActor
    private func waitForAny(_ elements: [XCUIElement], timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if elements.contains(where: { $0.exists }) { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        }
        return elements.contains(where: { $0.exists })
    }

    @MainActor
    private func liveStatusIsConnected(in app: XCUIApplication) -> Bool {
        let deadline = Date().addingTimeInterval(15)
        while Date() < deadline {
            let labels = app.descendants(matching: .any).matching(identifier: "liveUpdatesStatus").allElementsBoundByIndex
                .map(\.label)
            if labels.contains(where: { $0 == "Live updates live" || $0.hasSuffix(" live") }) {
                return true
            }
            if app.staticTexts["live"].exists || app.staticTexts["Live updates live"].exists {
                return true
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        return false
    }
}

private enum StoreScreenshotError: Error, LocalizedError {
    case missingOutputDirectory

    var errorDescription: String? {
        "STORE_SCREENSHOT_OUT is unset. Forward TEST_RUNNER_STORE_SCREENSHOT_OUT from xcodebuild."
    }
}
