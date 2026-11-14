import CoreGraphics
import XCTest
@testable import OpenCode_Mobile

/// Half and full detents of `.openCodeBottomSheet`: stops, settle, the
/// drag's clamp and slide, and how the keyboard caps them. Shared with Android.
final class OpenCodeBottomSheetDetentTests: XCTestCase {
    // iPhone 17 portrait: 866pt available (874 - 8 bottom inset), 62pt top safe area.
    private let available: CGFloat = 866
    private let topSafeArea: CGFloat = 62
    private let half: CGFloat = 433
    private let full: CGFloat = 796

    private func settle(_ extent: CGFloat, velocity: CGFloat = 0, low: CGFloat = 433, high: CGFloat = 796) -> CGFloat? {
        OpenCodeBottomSheetLayoutMath.settleTarget(extent: extent, velocity: velocity, low: low, high: high)
    }

    private func stops(
        _ height: OpenCodeBottomSheetHeight,
        _ mode: BottomSheetPresentationMode,
        fitted: CGFloat = 300,
        available: CGFloat? = nil
    ) -> OpenCodeBottomSheetStops {
        OpenCodeBottomSheetLayoutMath.stops(
            height: height,
            mode: mode,
            fittedHeight: fitted,
            availableHeight: available ?? self.available,
            topSafeArea: topSafeArea
        )
    }

    // MARK: Constants

    func testSettleConstantsMatchAndroid() {
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.flickVelocity, 900)
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.dismissFraction, 0.6)
    }

    // MARK: Settle

    func testFlickDownFromFullStepsToHalf() {
        XCTAssertEqual(settle(full, velocity: 1500), half)
        XCTAssertEqual(settle(600, velocity: 901), half)
        // Just above the low stop still counts as above it.
        XCTAssertEqual(settle(half + 2, velocity: 1500), half)
    }

    func testFlickDownFromHalfDismisses() {
        XCTAssertNil(settle(half, velocity: 1500))
        XCTAssertNil(settle(half + 1, velocity: 1500))
        XCTAssertNil(settle(300, velocity: 901))
    }

    func testFlickUpGoesToFull() {
        XCTAssertEqual(settle(half, velocity: -1500), full)
        XCTAssertEqual(settle(100, velocity: -901), full)
        XCTAssertEqual(settle(full, velocity: -901), full)
    }

    func testFlickNeedsMoreThanTheThreshold() {
        // Exactly 900 is not a flick: the position decides.
        XCTAssertEqual(settle(full, velocity: 900), full)
        XCTAssertEqual(settle(half, velocity: -900), half)
    }

    func testSlowReleaseBelowSixTenthsOfLowDismisses() {
        let threshold = 0.6 * half
        XCTAssertNil(settle(threshold - 1))
        XCTAssertNil(settle(0))
        XCTAssertEqual(settle(threshold), half)
        XCTAssertEqual(settle(threshold + 1), half)
    }

    func testSlowReleaseTakesTheNearerStop() {
        let midpoint = (half + full) / 2
        XCTAssertEqual(settle(midpoint - 1), half)
        XCTAssertEqual(settle(midpoint), full)
        XCTAssertEqual(settle(midpoint + 1), full)
        XCTAssertEqual(settle(half), half)
        XCTAssertEqual(settle(full), full)
    }

    func testSingleStopSheetSlidesBackOrDismisses() {
        // A compact action menu (fitted 300): no growth, a pull down slides it away.
        XCTAssertEqual(settle(300, low: 300, high: 300), 300)
        XCTAssertEqual(settle(250, low: 300, high: 300), 300)
        XCTAssertEqual(settle(180, low: 300, high: 300), 300)
        XCTAssertNil(settle(179, low: 300, high: 300))
        XCTAssertNil(settle(300, velocity: 1000, low: 300, high: 300))
        XCTAssertEqual(settle(280, velocity: -1000, low: 300, high: 300), 300)
    }

    // MARK: Stops

    func testFullIsTheExpandedLimitAndNeverBelowHalf() {
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.fullHeight(availableHeight: available, topSafeArea: topSafeArea), full)
        XCTAssertEqual(
            OpenCodeBottomSheetLayoutMath.fullHeight(availableHeight: available, topSafeArea: topSafeArea),
            OpenCodeBottomSheetLayoutMath.fitAllMaxHeight(availableHeight: available, topSafeArea: topSafeArea)
        )
        // A short window with a tall top inset: full falls back to half.
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.fullHeight(availableHeight: 100, topSafeArea: 62), 50)
    }

    func testHalfSheetsMoveBetweenHalfAndFull() {
        XCTAssertEqual(stops(.half, .scrollable), OpenCodeBottomSheetStops(low: half, high: full))
        XCTAssertTrue(stops(.half, .scrollable).hasRange)
    }

    func testFitContentWrapsWithASingleStopUntilItScrolls() {
        XCTAssertEqual(stops(.fitContent, .compact, fitted: 300), OpenCodeBottomSheetStops(low: 300, high: 300))
        XCTAssertFalse(stops(.fitContent, .compact).hasRange)
        XCTAssertEqual(stops(.fitContent, .scrollable), OpenCodeBottomSheetStops(low: half, high: full))
    }

    func testFitAllContentRestsAtItsFittedHeightOrFull() {
        XCTAssertEqual(stops(.fitAllContent, .compact, fitted: 640), OpenCodeBottomSheetStops(low: 640, high: 640))
        XCTAssertEqual(stops(.fitAllContent, .scrollable), OpenCodeBottomSheetStops(low: full, high: full))
        XCTAssertFalse(stops(.fitAllContent, .scrollable).hasRange)
    }

    func testStopsFollowTheWindow() {
        // Landscape-like 390pt: half 195, full 390 - 62 - 8.
        XCTAssertEqual(stops(.half, .scrollable, available: 390), OpenCodeBottomSheetStops(low: 195, high: 320))
    }

    func testStopsNeverInvert() {
        let stops = OpenCodeBottomSheetStops(low: 500, high: 400)
        XCTAssertEqual(stops.low, 500)
        XCTAssertEqual(stops.high, 500)
        XCTAssertEqual(OpenCodeBottomSheetStops(low: -10, high: 20).low, 0)
    }

    func testAtHighAllowsHalfAPointOfRounding() {
        let stops = OpenCodeBottomSheetStops(low: half, high: full)
        XCTAssertTrue(stops.isAtHigh(full))
        XCTAssertTrue(stops.isAtHigh(full - 0.4))
        XCTAssertFalse(stops.isAtHigh(full - 1))
    }

    // MARK: Drag, clamp and slide

    func testDragMovesTheExtentBothWaysWithinNothingAndFull() {
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.draggedExtent(half, by: -100, high: full), 533)
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.draggedExtent(half, by: 100, high: full), 333)
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.draggedExtent(full, by: -50, high: full), full)
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.draggedExtent(100, by: 300, high: full), 0)
    }

    func testCardGrowsBetweenStopsAndKeepsItsLowHeightBelow() {
        let stops = OpenCodeBottomSheetStops(low: half, high: full)
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.layoutHeight(extent: 600, stops: stops), 600)
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.layoutHeight(extent: 200, stops: stops), half)
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.layoutHeight(extent: 900, stops: stops), full)
    }

    func testBelowTheLowStopTheCardSlidesDown() {
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.slideOffset(extent: 333, low: half), 100)
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.slideOffset(extent: half, low: half), 0)
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.slideOffset(extent: 600, low: half), 0)
    }

    func testPromotedCompactCardStartsWhereItWasAndSlidesUp() {
        // A 300pt compact card turns scrollable: it keeps its top edge, then settles on half.
        let stops = OpenCodeBottomSheetStops(low: half, high: full)
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.layoutHeight(extent: 300, stops: stops), half)
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.slideOffset(extent: 300, low: half), 133)
    }

    // MARK: Keyboard

    func testKeepCapsBothStopsAtTheRoomAboveTheKeyboard() {
        // 336pt keyboard: 530 above it, 460 room under the safe area and gap.
        let room = OpenCodeBottomSheetLayoutMath.keyboardRoom(availableHeight: 530, topSafeArea: topSafeArea)
        XCTAssertEqual(room, 460)
        func capped(_ value: CGFloat) -> CGFloat? {
            OpenCodeBottomSheetLayoutMath.cardHeight(
                layoutHeight: value,
                availableHeight: 530,
                topSafeArea: topSafeArea,
                keyboardOverlap: 336,
                keyboard: .keep
            )
        }
        XCTAssertEqual(capped(half), half)
        XCTAssertEqual(capped(full), 460)
    }

    func testExpandFillsTheRoomFromEitherStop() {
        for layoutHeight in [half, full, nil] {
            XCTAssertEqual(
                OpenCodeBottomSheetLayoutMath.cardHeight(
                    layoutHeight: layoutHeight,
                    availableHeight: 530,
                    topSafeArea: topSafeArea,
                    keyboardOverlap: 336,
                    keyboard: .expand
                ),
                460
            )
        }
    }
}
