import CoreGraphics
import XCTest
@testable import OpenCode_Mobile

final class OpenCodeBottomSheetFollowTests: XCTestCase {
    private func following() -> OpenCodeBottomSheetFollow {
        var follow = OpenCodeBottomSheetFollow()
        follow.paneChanged(follows: true, hasTarget: false)
        return follow
    }

    func testLiveListOpensAtItsBottom() {
        let follow = following()

        XCTAssertTrue(follow.parked)
        XCTAssertEqual(follow.contentChanged(follows: true), .bottom)
    }

    func testSettledListOpensAtItsTop() {
        var follow = OpenCodeBottomSheetFollow()
        follow.paneChanged(follows: false, hasTarget: false)

        XCTAssertFalse(follow.parked)
        XCTAssertEqual(follow.contentChanged(follows: false), .none)
    }

    func testNewStepsFollowOnlyWhileParkedAtTheBottom() {
        var follow = following()

        follow.offsetChanged(distanceFromBottom: 10)
        XCTAssertEqual(follow.contentChanged(follows: true), .bottom)

        follow.offsetChanged(distanceFromBottom: 300)
        XCTAssertEqual(follow.contentChanged(follows: true), .none)

        follow.offsetChanged(distanceFromBottom: 0)
        XCTAssertEqual(follow.contentChanged(follows: true), .bottom)
    }

    func testSettlingWhileOpenStaysWhereTheListIs() {
        var follow = following()
        follow.offsetChanged(distanceFromBottom: 0)

        XCTAssertEqual(follow.contentChanged(follows: false), .none)
    }

    func testBackToAStepDoesNotJumpToTheBottom() {
        var follow = following()

        follow.paneChanged(follows: true, hasTarget: true)

        XCTAssertFalse(follow.parked)
        XCTAssertEqual(follow.contentChanged(follows: true), .target)
        // The step was far from the end, so new steps leave it in place.
        follow.offsetChanged(distanceFromBottom: 400)
        XCTAssertEqual(follow.contentChanged(follows: true), .none)
    }

    func testBackToAStepNearTheEndResumesFollowing() {
        var follow = following()

        follow.paneChanged(follows: true, hasTarget: true)
        follow.offsetChanged(distanceFromBottom: 0)

        XCTAssertEqual(follow.contentChanged(follows: true), .bottom)
    }

    func testDistanceFromBottomUsesTheScrollOffset() {
        let atTop = BottomSheetBodyGeometry(minY: 0, height: 1000)
        let atBottom = BottomSheetBodyGeometry(minY: -600, height: 1000)
        let short = BottomSheetBodyGeometry(minY: 0, height: 200)

        XCTAssertEqual(atTop.distanceFromBottom(viewportHeight: 400), 600)
        XCTAssertEqual(atBottom.distanceFromBottom(viewportHeight: 400), 0)
        XCTAssertLessThan(short.distanceFromBottom(viewportHeight: 400), 0)
    }
}
