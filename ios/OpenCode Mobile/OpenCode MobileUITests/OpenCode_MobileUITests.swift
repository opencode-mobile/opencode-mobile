import XCTest
import CoreFoundation

final class OpenCode_MobileUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testAppLaunches() throws {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 5))
    }

    @MainActor
    func testChangesScreenMultiFileAccessibilityAndFiltering() throws {
        let app = XCUIApplication()
        app.launchEnvironment["OPENCODE_UI_TEST_CHANGES"] = "1"
        app.launch()

        XCTAssertTrue(app.staticTexts["3 changed files, 4 additions, 3 deletions"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["Refresh"].exists)
        XCTAssertTrue(app.staticTexts["Git changes"].waitForExistence(timeout: 5))
        app.buttons["Choose change source"].tap()
        XCTAssertTrue(app.buttons["Git changes, selected"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Last turn changes"].exists)
        XCTAssertFalse(app.buttons["Main branch"].exists)
        app.buttons["Git changes, selected"].tap()

        let filter = app.textFields["Filter changed files"]
        XCTAssertTrue(filter.waitForExistence(timeout: 5))
        filter.tap()
        filter.typeText("missing")
        XCTAssertTrue(app.staticTexts["No matching files"].waitForExistence(timeout: 5))
        app.buttons["Clear filter"].tap()
        filter.tap()
        filter.typeText("App")

        let file = app.buttons["Sources/App.swift, Modified, file 1 of 1, 1 addition, 1 deletion"]
        XCTAssertTrue(file.waitForExistence(timeout: 5))
        file.tap()

        XCTAssertTrue(app.staticTexts["Sources/App.swift"].waitForExistence(timeout: 5))
        let diff = app.descendants(matching: .any)["Modified file Sources/App.swift, 1 additions, 1 deletions"]
        let longLine = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "horizontalScrollingFixture")
        ).firstMatch
        XCTAssertTrue(diff.waitForExistence(timeout: 5))
        XCTAssertTrue(longLine.waitForExistence(timeout: 5))
        let initialLongLineX = longLine.frame.minX
        diff.swipeLeft()
        let horizontalScrollExpectation = XCTNSPredicateExpectation(
            predicate: NSPredicate { element, _ in
                guard let element = element as? XCUIElement else { return false }
                return element.frame.minX < initialLongLineX - 20
            },
            object: longLine
        )
        XCTAssertEqual(XCTWaiter.wait(for: [horizontalScrollExpectation], timeout: 5), .completed)
        diff.swipeRight()

        XCTAssertFalse(app.buttons["Previous hunk"].isEnabled)
        XCTAssertTrue(app.buttons["Expand context"].isEnabled)
        XCTAssertTrue(app.buttons["Next hunk"].isEnabled)
        let firstHunk = app.staticTexts["hunk, , @@ -1,17 +1,17 @@"]
        let finalHunk = app.staticTexts["hunk, , @@ -30 +30 @@"]
        XCTAssertTrue(firstHunk.waitForExistence(timeout: 5))
        XCTAssertTrue(finalHunk.waitForExistence(timeout: 5))
        let expectedHunkTop = firstHunk.frame.minY
        app.buttons["Next hunk"].tap()
        let topAlignmentExpectation = XCTNSPredicateExpectation(
            predicate: NSPredicate { element, _ in
                guard let element = element as? XCUIElement else { return false }
                return abs(element.frame.minY - expectedHunkTop) < 2
            },
            object: finalHunk
        )
        XCTAssertEqual(
            XCTWaiter.wait(for: [topAlignmentExpectation], timeout: 5),
            .completed,
            "Expected final hunk at \(expectedHunkTop), got \(finalHunk.frame.minY)"
        )
        XCTAssertTrue(app.buttons["Previous hunk"].isEnabled)
        XCTAssertFalse(app.buttons["Next hunk"].isEnabled)
        app.buttons["Expand context"].tap()
        XCTAssertTrue(app.buttons["Collapse context"].exists)
        XCTAssertFalse(app.staticTexts["Filter paths"].exists)

        app.buttons["Back"].tap()
        XCTAssertEqual(filter.value as? String, "App")
        XCTAssertTrue(app.staticTexts["1 changed file, 1 addition, 1 deletion"].exists)
        filter.tap()
        filter.typeText("missing")
        XCTAssertTrue(app.staticTexts["No matching files"].waitForExistence(timeout: 5))
        app.buttons["Clear filter"].tap()
        if app.keyboards.buttons["done"].exists { app.keyboards.buttons["done"].tap() }
        XCTAssertTrue(app.staticTexts["3 changed files, 4 additions, 3 deletions"].waitForExistence(timeout: 5))
        app.buttons["Sources/Removed.swift, Deleted, file 3 of 3, 1 addition, 2 deletions"].tap()
        XCTAssertTrue(app.staticTexts["Preview unavailable"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["Previous hunk"].exists)
    }

    @MainActor
    func testMessageListGalleryTimelineHostFollowsAppends() throws {
        let app = XCUIApplication()
        app.launchEnvironment["OPENCODE_UI_TEST_MESSAGE_LIST"] = "1"
        app.launch()

        XCTAssertTrue(
            app.otherElements["messageListGalleryUITest"].waitForExistence(timeout: 15),
            "Message List gallery UITest harness did not appear."
        )

        let add = app.buttons["Add message"]
        XCTAssertTrue(
            add.waitForExistence(timeout: 10),
            "UIKit MessageListTimelineHost gallery controls did not appear."
        )

        // UICollectionView only exposes on-screen hosted cells to XCTest; assert against
        // a near-end fixture message that the initial scroll-to-end leaves visible.
        let visibleMessage = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "Tap the pill to return here")
        ).firstMatch
        XCTAssertTrue(
            visibleMessage.waitForExistence(timeout: 5),
            "Expected hosted gallery message content."
        )

        add.tap()
        add.tap()
        add.tap()
        Thread.sleep(forTimeInterval: 0.6)

        let scrollToEnd = app.buttons["Scroll to end"]
        if scrollToEnd.waitForExistence(timeout: 2) {
            scrollToEnd.tap()
            Thread.sleep(forTimeInterval: 0.4)
        }

        let resize = app.buttons["Resize message"]
        XCTAssertTrue(resize.waitForExistence(timeout: 5))
        resize.tap()
        Thread.sleep(forTimeInterval: 0.4)
        resize.tap()
        Thread.sleep(forTimeInterval: 0.4)

        let appended = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "Live message")
        ).firstMatch
        XCTAssertTrue(
            appended.waitForExistence(timeout: 5),
            "Expected appended live gallery message after Add taps."
        )

        XCTAssertTrue(add.exists, "Timeline host disappeared after append/resize.")
        XCTAssertFalse(app.staticTexts["Unable to load messages"].exists)
    }

    @MainActor
    func testReviewChangesPillAccessibilityAndDisabledBehavior() throws {
        let app = XCUIApplication()
        app.launchEnvironment["OPENCODE_UI_TEST_REVIEW_CHANGES_PILL"] = "1"
        app.launch()

        let singular = app.buttons["Review changes, 1 file, 12 additions, 3 deletions"]
        XCTAssertTrue(singular.waitForExistence(timeout: 10))
        singular.tap()
        XCTAssertTrue(app.staticTexts["Review changes tapped"].waitForExistence(timeout: 5))

        let disabled = app.buttons["Review changes, 4 files"]
        XCTAssertTrue(disabled.waitForExistence(timeout: 5))
        XCTAssertFalse(disabled.isEnabled)
    }

    @MainActor
    func testLaunchPerformance() throws {
        measure(metrics: [XCTApplicationLaunchMetric()]) {
            XCUIApplication().launch()
        }
    }

    /// Exercises the production connection flow against an actual opencode server.
    ///
    /// This test intentionally has no mock server. It is skipped unless the invoking
    /// environment provides the server credentials, keeping normal simulator and CI
    /// runs isolated from a developer's server. The test updates the simulator app's
    /// saved connection profile; use a dedicated simulator when that profile matters.
    @MainActor
    func testLiveServerLoadsProjectData() throws {
        let configuration = try LiveServerConfiguration(environment: ProcessInfo.processInfo.environment)
        let app = XCUIApplication()
        app.launch()

        openConnectionSetup(in: app)
        replaceText(in: app.textFields["Host"], with: configuration.serverURL, app: app)
        replaceText(in: app.textFields["Username"], with: configuration.username, app: app)
        replaceText(in: app.secureTextFields["Password"], with: configuration.password, app: app)

        app.buttons["Test connection"].tap()
        XCTAssertTrue(app.staticTexts["Server reachable"].waitForExistence(timeout: 15))

        app.buttons["Save"].tap()
        XCTAssertTrue(app.buttons["New session"].waitForExistence(timeout: 15))

        let session = sessionButton(named: "Big Session", in: app)
        XCTAssertTrue(session.waitForExistence(timeout: 10), "The live server did not return the Big Session session.")
        openBigSessionFromFullList(in: app)
        waitForBigSessionHistory(in: app)

        if let expectedProjectName = configuration.expectedProjectName {
            XCTAssertTrue(
                app.staticTexts[expectedProjectName].waitForExistence(timeout: 10),
                "Expected project \(expectedProjectName) was not loaded from the live server."
            )
        }
    }

    @MainActor
    func testBenchmarkBigSessionChatLoad() throws {
        let app = XCUIApplication()
        app.launchEnvironment["OPENCODE_UI_BENCHMARK"] = "1"
        app.launch()

        let probes = SessionPerformanceProbeCollector()
        let startedAt = Date()
        openBigSessionFromFullList(in: app)
        let result = XCTWaiter().wait(for: [probes.timelinePublished], timeout: 45)
        print(probes.report(startedAt: startedAt))
        XCTAssertEqual(result, .completed, "Styled timeline did not publish before the benchmark timeout.")
    }

    @MainActor
    func testBenchmarkBigSessionWarmReopen() throws {
        let app = XCUIApplication()
        app.launch()

        openBigSessionFromFullList(in: app)
        Thread.sleep(forTimeInterval: 100)
        XCTAssertTrue(app.staticTexts["hello"].exists, "Initial history hydration did not complete.")

        app.buttons["Back"].tap()
        let startedAt = Date()
        openBigSessionFromFullList(in: app)
        XCTAssertTrue(app.staticTexts["hello"].waitForExistence(timeout: 15))
        print("Big Session warm reopen duration: \(Date().timeIntervalSince(startedAt)) seconds")
    }

    private func openBigSessionFromFullList(in app: XCUIApplication) {
        let showAll = app.buttons["Show all"]
        XCTAssertTrue(showAll.waitForExistence(timeout: 20))
        showAll.tap()

        let session = app.buttons["Big Session"]
        XCTAssertTrue(session.waitForExistence(timeout: 10), "Big Session was not present in the session list.")
        session.tap()
        XCTAssertTrue(app.staticTexts["Session"].waitForExistence(timeout: 10), "Session navigation did not start.")
    }

    private func waitForBigSessionHistory(in app: XCUIApplication) {
        let loading = app.activityIndicators["sessionChatLoading"]
        XCTAssertTrue(loading.waitForExistence(timeout: 10), "Session loading did not begin.")
        XCTAssertTrue(app.staticTexts["hello"].waitForExistence(timeout: 120), "Expected loaded session message was not visible.")
        XCTAssertFalse(loading.exists, "Session loading indicator remained after the message was visible.")
    }

    private func openConnectionSetup(in app: XCUIApplication) {
        let connectButton = app.buttons["Connect to server"]
        if connectButton.waitForExistence(timeout: 5) {
            connectButton.tap()
            return
        }

        app.buttons["Settings"].tap()
        app.staticTexts["Server Connection"].tap()
    }

    private func sessionButton(named title: String, in app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", title)).firstMatch
    }

    private func replaceText(in field: XCUIElement, with value: String, app: XCUIApplication) {
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.press(forDuration: 1.0)

        let selectAll = app.menuItems["Select All"]
        if selectAll.waitForExistence(timeout: 2) {
            selectAll.tap()
        } else {
            field.typeKey("a", modifierFlags: .command)
        }
        field.typeText(value)
    }
}

private final class SessionPerformanceProbeCollector {
    private let lock = NSLock()
    private var timestamps: [String: Date] = [:]
    let timelinePublished = XCTestExpectation(description: "styled timeline published")
    private let events = [
        "session_screen_started",
        "history_request_started",
        "history_request_finished",
        "markdown_preparation_started",
        "markdown_preparation_finished",
        "styled_timeline_published",
    ]

    init() {
        for event in events {
            CFNotificationCenterAddObserver(
                CFNotificationCenterGetDarwinNotifyCenter(),
                Unmanaged.passUnretained(self).toOpaque(),
                { _, observer, name, _, _ in
                    guard let observer,
                          let name,
                          let nameString = name.rawValue as String?
                    else { return }
                    let event = nameString.split(separator: ".").last.map(String.init) ?? ""
                    Unmanaged<SessionPerformanceProbeCollector>.fromOpaque(observer).takeUnretainedValue().record(event)
                },
                CFNotificationName("dev.opencode.mobile.performance.\(event)" as CFString).rawValue,
                nil,
                .deliverImmediately
            )
        }
    }

    private func record(_ event: String) {
        guard events.contains(event) else { return }
        lock.lock()
        timestamps[event] = timestamps[event] ?? Date()
        lock.unlock()
        if event == "styled_timeline_published" { timelinePublished.fulfill() }
    }

    func report(startedAt: Date) -> String {
        lock.lock()
        let values = timestamps
        lock.unlock()
        let absolute = events.map { event in
            let seconds = values[event].map { $0.timeIntervalSince(startedAt) } ?? -1
            return "\(event)=\(String(format: "%.3f", seconds))s"
        }.joined(separator: " ")
        let historyDuration = duration(from: "history_request_started", to: "history_request_finished", values: values)
        let markdownDuration = duration(from: "markdown_preparation_started", to: "markdown_preparation_finished", values: values)
        return "PERFORMANCE_PROBE \(absolute) history_request=\(historyDuration) markdown_preparation=\(markdownDuration)"
    }

    private func duration(from start: String, to end: String, values: [String: Date]) -> String {
        guard let startTime = values[start], let endTime = values[end] else { return "incomplete" }
        return String(format: "%.3fs", endTime.timeIntervalSince(startTime))
    }

    deinit {
        CFNotificationCenterRemoveObserver(
            CFNotificationCenterGetDarwinNotifyCenter(),
            Unmanaged.passUnretained(self).toOpaque(),
            nil,
            nil
        )
    }
}

private struct LiveServerConfiguration {
    let serverURL: String
    let username: String
    let password: String
    let expectedProjectName: String?

    init(environment: [String: String]) throws {
        guard let serverURL = environment["OPENCODE_UI_TEST_SERVER_URL"], !serverURL.isEmpty,
              let password = environment["OPENCODE_UI_TEST_PASSWORD"], !password.isEmpty
        else {
            throw XCTSkip(
                "Set OPENCODE_UI_TEST_SERVER_URL and OPENCODE_UI_TEST_PASSWORD to run the live-server UI test."
            )
        }

        self.serverURL = serverURL
        username = environment["OPENCODE_UI_TEST_USERNAME"] ?? "opencode"
        self.password = password
        expectedProjectName = environment["OPENCODE_UI_TEST_PROJECT_NAME"]
    }
}
