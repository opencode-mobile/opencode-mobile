import CoreGraphics
import XCTest
@testable import OpenCode_Mobile

final class OpenCodeBottomSheetHeightTests: XCTestCase {
    // iPhone 17 portrait: 874pt window, 62pt top safe area, 336pt keyboard.
    private let containerHeight: CGFloat = 874
    private let topSafeArea: CGFloat = 62
    private let keyboard: CGFloat = 336

    func testAvailableHeightSubtractsBottomInsetAndKeyboard() {
        XCTAssertEqual(
            OpenCodeBottomSheetHeight.availableHeight(containerHeight: containerHeight, keyboardOverlap: 0),
            866
        )
        XCTAssertEqual(
            OpenCodeBottomSheetHeight.availableHeight(containerHeight: containerHeight, keyboardOverlap: keyboard),
            530
        )
        XCTAssertEqual(
            OpenCodeBottomSheetHeight.availableHeight(containerHeight: 100, keyboardOverlap: 200),
            0
        )
    }

    func testKeepHeightUsesRestingHeightWithKeyboard() {
        let available = OpenCodeBottomSheetHeight.availableHeight(
            containerHeight: containerHeight,
            keyboardOverlap: keyboard
        )
        let height = OpenCodeBottomSheetHeight.cardHeight(
            restingHeight: available * 0.5,
            availableHeight: available,
            topSafeArea: topSafeArea,
            keyboardOverlap: keyboard,
            behavior: .keepHeight
        )
        XCTAssertEqual(height, 265)
    }

    func testKeepHeightClampsRestingHeightToAvailable() {
        let height = OpenCodeBottomSheetHeight.cardHeight(
            restingHeight: 900,
            availableHeight: 530,
            topSafeArea: topSafeArea,
            keyboardOverlap: keyboard,
            behavior: .keepHeight
        )
        XCTAssertEqual(height, 530)
    }

    func testKeepHeightLeavesContentSizedCardContentSized() {
        XCTAssertNil(
            OpenCodeBottomSheetHeight.cardHeight(
                restingHeight: nil,
                availableHeight: 530,
                topSafeArea: topSafeArea,
                keyboardOverlap: keyboard,
                behavior: .keepHeight
            )
        )
    }

    func testExpandFillsSpaceBetweenTopSafeAreaAndKeyboard() {
        let available = OpenCodeBottomSheetHeight.availableHeight(
            containerHeight: containerHeight,
            keyboardOverlap: keyboard
        )
        let height = OpenCodeBottomSheetHeight.cardHeight(
            restingHeight: available * 0.5,
            availableHeight: available,
            topSafeArea: topSafeArea,
            keyboardOverlap: keyboard,
            behavior: .expand
        )
        // 874 - 8 bottom inset - 336 keyboard - 62 safe area - 8 top gap.
        XCTAssertEqual(height, 460)
        XCTAssertEqual(OpenCodeBottomSheetHeight.expandedTopGap, 8)
    }

    func testExpandReturnsToRestingHeightWhenKeyboardHides() {
        let available = OpenCodeBottomSheetHeight.availableHeight(
            containerHeight: containerHeight,
            keyboardOverlap: 0
        )
        let height = OpenCodeBottomSheetHeight.cardHeight(
            restingHeight: available * 0.5,
            availableHeight: available,
            topSafeArea: topSafeArea,
            keyboardOverlap: 0,
            behavior: .expand
        )
        XCTAssertEqual(height, 433)
    }

    func testExpandWithoutKeyboardKeepsContentSizedCard() {
        XCTAssertNil(
            OpenCodeBottomSheetHeight.cardHeight(
                restingHeight: nil,
                availableHeight: 866,
                topSafeArea: topSafeArea,
                keyboardOverlap: 0,
                behavior: .expand
            )
        )
    }

    func testExpandGivesContentSizedCardAFixedHeightWithKeyboard() {
        let height = OpenCodeBottomSheetHeight.cardHeight(
            restingHeight: nil,
            availableHeight: 530,
            topSafeArea: topSafeArea,
            keyboardOverlap: keyboard,
            behavior: .expand
        )
        XCTAssertEqual(height, 460)
    }

    func testExpandNeverShrinksBelowRestingHeight() {
        // Landscape-like: little room, resting height already larger than the expanded target.
        let height = OpenCodeBottomSheetHeight.cardHeight(
            restingHeight: 120,
            availableHeight: 150,
            topSafeArea: 40,
            keyboardOverlap: 200,
            behavior: .expand
        )
        XCTAssertEqual(height, 120)
    }

    func testExpandedHeightIsNeverNegative() {
        let height = OpenCodeBottomSheetHeight.cardHeight(
            restingHeight: nil,
            availableHeight: 20,
            topSafeArea: 62,
            keyboardOverlap: keyboard,
            behavior: .expand
        )
        XCTAssertEqual(height, 0)
    }

    func testKeyboardExpandedOnlyForExpandWithVisibleKeyboard() {
        XCTAssertTrue(OpenCodeBottomSheetHeight.isKeyboardExpanded(behavior: .expand, keyboardOverlap: keyboard))
        XCTAssertFalse(OpenCodeBottomSheetHeight.isKeyboardExpanded(behavior: .expand, keyboardOverlap: 0))
        XCTAssertFalse(OpenCodeBottomSheetHeight.isKeyboardExpanded(behavior: .keepHeight, keyboardOverlap: keyboard))
    }

    func testFormsAlwaysTrackKeyboardAndActionListsOnlyWhenExpanding() {
        XCTAssertTrue(OpenCodeBottomSheetHeight.tracksKeyboard(contentMode: .form, behavior: .keepHeight))
        XCTAssertTrue(OpenCodeBottomSheetHeight.tracksKeyboard(contentMode: .form, behavior: .expand))
        XCTAssertTrue(OpenCodeBottomSheetHeight.tracksKeyboard(contentMode: .actionList, behavior: .expand))
        XCTAssertFalse(OpenCodeBottomSheetHeight.tracksKeyboard(contentMode: .actionList, behavior: .keepHeight))
    }
}
