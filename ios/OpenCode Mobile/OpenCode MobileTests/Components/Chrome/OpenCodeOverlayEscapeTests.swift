import UIKit
import XCTest
@testable import OpenCode_Mobile

final class OpenCodeOverlayEscapeTests: XCTestCase {
    private let first = NSObject()
    private let second = NSObject()

    override func tearDown() {
        OpenCodeOverlayEscape.unregister(first)
        OpenCodeOverlayEscape.unregister(second)
        super.tearDown()
    }

    func testCommandIsPlainEscapeWithPriorityOverTheSystem() {
        let command = OpenCodeOverlayEscape.makeCommand()
        XCTAssertEqual(command.input, UIKeyCommand.inputEscape)
        XCTAssertEqual(command.modifierFlags, [])
        XCTAssertTrue(command.wantsPriorityOverSystemBehavior)
    }

    func testEscapeDismissesOnlyTheNewestOverlay() {
        var dismissed: [String] = []
        OpenCodeOverlayEscape.register(first, layer: 1) { dismissed.append("first") }
        OpenCodeOverlayEscape.register(second, layer: 2) { dismissed.append("second") }

        OpenCodeOverlayEscape.dismissNewest()
        XCTAssertEqual(dismissed, ["second"])

        OpenCodeOverlayEscape.unregister(second)
        OpenCodeOverlayEscape.dismissNewest()
        XCTAssertEqual(dismissed, ["second", "first"])
    }

    func testRefreshingAnOverlayKeepsItsPlaceAndUsesTheLatestHandler() {
        var dismissed: [String] = []
        OpenCodeOverlayEscape.register(first, layer: 1) { dismissed.append("first-old") }
        OpenCodeOverlayEscape.register(second, layer: 2) { dismissed.append("second") }
        OpenCodeOverlayEscape.register(first, layer: 1) { dismissed.append("first-new") }

        OpenCodeOverlayEscape.dismissNewest()
        XCTAssertEqual(dismissed, ["second"], "a re-render does not make an older overlay the newest")

        OpenCodeOverlayEscape.unregister(second)
        OpenCodeOverlayEscape.dismissNewest()
        XCTAssertEqual(dismissed, ["second", "first-new"])
    }

    func testEscapeWithNoOverlayDoesNothing() {
        XCTAssertFalse(OpenCodeOverlayEscape.hasOverlay)
        OpenCodeOverlayEscape.dismissNewest()
    }

    func testHasOverlayTracksRegistrations() {
        OpenCodeOverlayEscape.register(first, layer: 1) {}
        XCTAssertTrue(OpenCodeOverlayEscape.hasOverlay)
        OpenCodeOverlayEscape.unregister(first)
        XCTAssertFalse(OpenCodeOverlayEscape.hasOverlay)
    }
}
