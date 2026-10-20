import UIKit
import XCTest
@testable import OpenCode_Mobile

final class OpenCodeBottomSheetEscapeTests: XCTestCase {
    private let first = NSObject()
    private let second = NSObject()

    override func tearDown() {
        OpenCodeBottomSheetEscape.unregister(first)
        OpenCodeBottomSheetEscape.unregister(second)
        super.tearDown()
    }

    func testCommandIsPlainEscapeWithPriorityOverTheSystem() {
        let command = OpenCodeBottomSheetEscape.makeCommand()
        XCTAssertEqual(command.input, UIKeyCommand.inputEscape)
        XCTAssertEqual(command.modifierFlags, [])
        XCTAssertTrue(command.wantsPriorityOverSystemBehavior)
    }

    func testEscapeDismissesOnlyTheNewestSheet() {
        var dismissed: [String] = []
        OpenCodeBottomSheetEscape.register(first) { dismissed.append("first") }
        OpenCodeBottomSheetEscape.register(second) { dismissed.append("second") }

        OpenCodeBottomSheetEscape.dismissNewest()
        XCTAssertEqual(dismissed, ["second"])

        OpenCodeBottomSheetEscape.unregister(second)
        OpenCodeBottomSheetEscape.dismissNewest()
        XCTAssertEqual(dismissed, ["second", "first"])
    }

    func testRefreshingASheetKeepsItsPlaceAndUsesTheLatestHandler() {
        var dismissed: [String] = []
        OpenCodeBottomSheetEscape.register(first) { dismissed.append("first-old") }
        OpenCodeBottomSheetEscape.register(second) { dismissed.append("second") }
        OpenCodeBottomSheetEscape.register(first) { dismissed.append("first-new") }

        OpenCodeBottomSheetEscape.dismissNewest()
        XCTAssertEqual(dismissed, ["second"], "a re-render does not make an older sheet the newest")

        OpenCodeBottomSheetEscape.unregister(second)
        OpenCodeBottomSheetEscape.dismissNewest()
        XCTAssertEqual(dismissed, ["second", "first-new"])
    }

    func testEscapeWithNoSheetDoesNothing() {
        OpenCodeBottomSheetEscape.dismissNewest()
    }
}
