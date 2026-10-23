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
            OpenCodeBottomSheetLayoutMath.availableHeight(containerHeight: containerHeight, keyboardOverlap: 0),
            866
        )
        XCTAssertEqual(
            OpenCodeBottomSheetLayoutMath.availableHeight(containerHeight: containerHeight, keyboardOverlap: keyboard),
            530
        )
        XCTAssertEqual(
            OpenCodeBottomSheetLayoutMath.availableHeight(containerHeight: 100, keyboardOverlap: 200),
            0
        )
    }

    func testKeepHeightUsesRestingHeightWithKeyboard() {
        let available = OpenCodeBottomSheetLayoutMath.availableHeight(
            containerHeight: containerHeight,
            keyboardOverlap: keyboard
        )
        let height = OpenCodeBottomSheetLayoutMath.cardHeight(
            restingHeight: available * 0.5,
            availableHeight: available,
            topSafeArea: topSafeArea,
            keyboardOverlap: keyboard,
            keyboard: .keep
        )
        XCTAssertEqual(height, 265)
    }

    func testKeepHeightClampsRestingHeightToAvailable() {
        let height = OpenCodeBottomSheetLayoutMath.cardHeight(
            restingHeight: 900,
            availableHeight: 530,
            topSafeArea: topSafeArea,
            keyboardOverlap: keyboard,
            keyboard: .keep
        )
        XCTAssertEqual(height, 530)
    }

    func testKeepHeightLeavesContentSizedCardContentSized() {
        XCTAssertNil(
            OpenCodeBottomSheetLayoutMath.cardHeight(
                restingHeight: nil,
                availableHeight: 530,
                topSafeArea: topSafeArea,
                keyboardOverlap: keyboard,
                keyboard: .keep
            )
        )
    }

    func testExpandFillsSpaceBetweenTopSafeAreaAndKeyboard() {
        let available = OpenCodeBottomSheetLayoutMath.availableHeight(
            containerHeight: containerHeight,
            keyboardOverlap: keyboard
        )
        let height = OpenCodeBottomSheetLayoutMath.cardHeight(
            restingHeight: available * 0.5,
            availableHeight: available,
            topSafeArea: topSafeArea,
            keyboardOverlap: keyboard,
            keyboard: .expand
        )
        // 874 - 8 bottom inset - 336 keyboard - 62 safe area - 8 top gap.
        XCTAssertEqual(height, 460)
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.expandedTopGap, 8)
    }

    func testExpandReturnsToRestingHeightWhenKeyboardHides() {
        let available = OpenCodeBottomSheetLayoutMath.availableHeight(
            containerHeight: containerHeight,
            keyboardOverlap: 0
        )
        let height = OpenCodeBottomSheetLayoutMath.cardHeight(
            restingHeight: available * 0.5,
            availableHeight: available,
            topSafeArea: topSafeArea,
            keyboardOverlap: 0,
            keyboard: .expand
        )
        XCTAssertEqual(height, 433)
    }

    func testExpandWithoutKeyboardKeepsContentSizedCard() {
        XCTAssertNil(
            OpenCodeBottomSheetLayoutMath.cardHeight(
                restingHeight: nil,
                availableHeight: 866,
                topSafeArea: topSafeArea,
                keyboardOverlap: 0,
                keyboard: .expand
            )
        )
    }

    func testExpandGivesContentSizedCardAFixedHeightWithKeyboard() {
        let height = OpenCodeBottomSheetLayoutMath.cardHeight(
            restingHeight: nil,
            availableHeight: 530,
            topSafeArea: topSafeArea,
            keyboardOverlap: keyboard,
            keyboard: .expand
        )
        XCTAssertEqual(height, 460)
    }

    func testExpandNeverShrinksBelowRestingHeight() {
        // Landscape-like: little room, resting height already larger than the expanded target.
        let height = OpenCodeBottomSheetLayoutMath.cardHeight(
            restingHeight: 120,
            availableHeight: 150,
            topSafeArea: 40,
            keyboardOverlap: 200,
            keyboard: .expand
        )
        XCTAssertEqual(height, 120)
    }

    func testExpandedHeightIsNeverNegative() {
        let height = OpenCodeBottomSheetLayoutMath.cardHeight(
            restingHeight: nil,
            availableHeight: 20,
            topSafeArea: 62,
            keyboardOverlap: keyboard,
            keyboard: .expand
        )
        XCTAssertEqual(height, 0)
    }

    func testKeyboardExpandedOnlyForExpandWithVisibleKeyboard() {
        XCTAssertTrue(OpenCodeBottomSheetLayoutMath.isKeyboardExpanded(keyboard: .expand, keyboardOverlap: keyboard))
        XCTAssertFalse(OpenCodeBottomSheetLayoutMath.isKeyboardExpanded(keyboard: .expand, keyboardOverlap: 0))
        XCTAssertFalse(OpenCodeBottomSheetLayoutMath.isKeyboardExpanded(keyboard: .keep, keyboardOverlap: keyboard))
        XCTAssertFalse(OpenCodeBottomSheetLayoutMath.isKeyboardExpanded(keyboard: .dismiss, keyboardOverlap: keyboard))
    }

    func testOnlyDismissSheetsIgnoreTheKeyboard() {
        XCTAssertFalse(OpenCodeBottomSheetLayoutMath.tracksKeyboard(.dismiss))
        XCTAssertTrue(OpenCodeBottomSheetLayoutMath.tracksKeyboard(.keep))
        XCTAssertTrue(OpenCodeBottomSheetLayoutMath.tracksKeyboard(.expand))
    }

    func testDismissKeyboardNeverGrowsTheCard() {
        let height = OpenCodeBottomSheetLayoutMath.cardHeight(
            restingHeight: 433,
            availableHeight: 866,
            topSafeArea: topSafeArea,
            keyboardOverlap: keyboard,
            keyboard: .dismiss
        )
        XCTAssertEqual(height, 433)
    }

    func testScrollableHeightIsHalfOfAvailable() {
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.scrollableHeight(availableHeight: 866), 433)
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.scrollableDetentFraction, 0.5)
    }

    // MARK: Bottom clearance

    // iPhone 17: 34pt home indicator minus the card's 8pt bottom inset.
    private let safeAreaBottom: CGFloat = 26

    private func clearance(
        _ height: OpenCodeBottomSheetHeight,
        _ policy: OpenCodeBottomSheetKeyboard,
        keyboardOverlap: CGFloat = 0,
        safeAreaBottom: CGFloat? = nil
    ) -> CGFloat {
        OpenCodeBottomSheetLayoutMath.bottomClearance(
            height: height,
            keyboard: policy,
            keyboardOverlap: keyboardOverlap,
            safeAreaBottom: safeAreaBottom ?? self.safeAreaBottom
        )
    }

    func testFitContentSheetsUseTheCompactClearance() {
        XCTAssertEqual(clearance(.fitContent, .dismiss), 20)
        XCTAssertEqual(clearance(.fitContent, .keep), 20)
        XCTAssertEqual(clearance(.fitContent, .expand), 20)
        XCTAssertEqual(clearance(.fitContent, .keep, keyboardOverlap: keyboard), 20)
        XCTAssertEqual(clearance(.fitContent, .expand, keyboardOverlap: keyboard), 20)
    }

    func testHalfSheetThatDismissesTheKeyboardUsesTheCompactClearance() {
        XCTAssertEqual(clearance(.half, .dismiss), 20)
        XCTAssertEqual(clearance(.half, .dismiss, keyboardOverlap: keyboard), 20)
    }

    func testHalfSheetThatKeepsTheKeyboardClearsTheHomeIndicator() {
        XCTAssertEqual(clearance(.half, .keep), 26)
        XCTAssertEqual(clearance(.half, .keep, keyboardOverlap: keyboard), 26)
        XCTAssertEqual(clearance(.half, .expand), 26)
    }

    func testExpandedHalfSheetOnTheKeyboardUsesTheCompactClearance() {
        XCTAssertEqual(clearance(.half, .expand, keyboardOverlap: keyboard), 20)
    }

    func testHomeIndicatorClearanceNeverDropsBelowCompact() {
        XCTAssertEqual(clearance(.half, .keep, safeAreaBottom: 0), 20)
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.compactBottomClearance, 20)
    }

    // MARK: Fit content

    func testFitContentHeightCountsEveryPartIncludingThePinnedBottom() {
        let height = OpenCodeBottomSheetLayoutMath.fitContentHeight(
            headerHeight: 60,
            pinnedTopHeight: 40,
            contentHeight: 192,
            pinnedBottomHeight: 56,
            bottomClearance: 20
        )
        XCTAssertEqual(height, 368)
    }

    func testFitContentStaysCompactUpToTheLimit() {
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.compactMaxHeight, 420)
        XCTAssertTrue(OpenCodeBottomSheetLayoutMath.fitsCompact(fitContentHeight: 420))
        XCTAssertFalse(OpenCodeBottomSheetLayoutMath.fitsCompact(fitContentHeight: 420.5))
    }

    func testFitAllContentWrapsUpToTheExpandedLimit() {
        // iPhone 17: 866pt available, 62pt top safe area, 8pt top gap.
        let limit = OpenCodeBottomSheetLayoutMath.compactLimit(height: .fitAllContent, availableHeight: 866, topSafeArea: 62)
        XCTAssertEqual(limit, 796)
        XCTAssertEqual(limit, OpenCodeBottomSheetLayoutMath.fitAllMaxHeight(availableHeight: 866, topSafeArea: 62))
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.compactLimit(height: .fitContent, availableHeight: 866, topSafeArea: 62), 420)
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.compactLimit(height: .half, availableHeight: 866, topSafeArea: 62), 420)
    }

    func testFitAllContentScrollsAtItsLimitInsteadOfTheHalfDetent() {
        XCTAssertEqual(
            OpenCodeBottomSheetLayoutMath.restingScrollableHeight(height: .fitAllContent, availableHeight: 866, topSafeArea: 62),
            796
        )
        XCTAssertEqual(
            OpenCodeBottomSheetLayoutMath.restingScrollableHeight(height: .fitContent, availableHeight: 866, topSafeArea: 62),
            433
        )
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.fitAllMaxHeight(availableHeight: 40, topSafeArea: 62), 0)
    }

    func testPinnedBottomCanPushFitContentToTheScrollableDetent() {
        let withoutFooter = OpenCodeBottomSheetLayoutMath.fitContentHeight(
            headerHeight: 60,
            pinnedTopHeight: 0,
            contentHeight: 300,
            pinnedBottomHeight: 0,
            bottomClearance: 20
        )
        let withFooter = OpenCodeBottomSheetLayoutMath.fitContentHeight(
            headerHeight: 60,
            pinnedTopHeight: 0,
            contentHeight: 300,
            pinnedBottomHeight: 56,
            bottomClearance: 20
        )
        XCTAssertTrue(OpenCodeBottomSheetLayoutMath.fitsCompact(fitContentHeight: withoutFooter))
        XCTAssertFalse(OpenCodeBottomSheetLayoutMath.fitsCompact(fitContentHeight: withFooter))
    }

    // MARK: Scroll area

    func testScrollAreaGetsTheCardMinusPinnedPartsAndClearance() {
        // Half detent (433) - header 60 - pinned top 100 - footer 56 (8 gap + 48 button) - clearance 26.
        let height = OpenCodeBottomSheetLayoutMath.scrollAreaHeight(
            cardHeight: 433,
            headerHeight: 60,
            pinnedTopHeight: 100,
            pinnedBottomHeight: 56,
            bottomClearance: 26
        )
        XCTAssertEqual(height, 191)
    }

    func testScrollAreaWithoutPinnedBottomMatchesTheOldLayout() {
        let height = OpenCodeBottomSheetLayoutMath.scrollAreaHeight(
            cardHeight: 433,
            headerHeight: 60,
            pinnedTopHeight: 0,
            pinnedBottomHeight: 0,
            bottomClearance: 26
        )
        XCTAssertEqual(height, 347)
    }

    func testExpandedScrollAreaGrowsWhileTheFooterKeepsItsHeight() {
        let available = OpenCodeBottomSheetLayoutMath.availableHeight(
            containerHeight: containerHeight,
            keyboardOverlap: keyboard
        )
        let card = OpenCodeBottomSheetLayoutMath.cardHeight(
            restingHeight: OpenCodeBottomSheetLayoutMath.scrollableHeight(availableHeight: available),
            availableHeight: available,
            topSafeArea: topSafeArea,
            keyboardOverlap: keyboard,
            keyboard: .expand
        ) ?? 0
        let bottomClearance = clearance(.half, .expand, keyboardOverlap: keyboard)
        let height = OpenCodeBottomSheetLayoutMath.scrollAreaHeight(
            cardHeight: card,
            headerHeight: 60,
            pinnedTopHeight: 100,
            pinnedBottomHeight: 56,
            bottomClearance: bottomClearance
        )
        // 460 expanded card - 60 - 100 - 56 - 20.
        XCTAssertEqual(height, 224)
    }

    func testScrollAreaKeepsAMinimumWhenPinnedPartsFillTheCard() {
        let height = OpenCodeBottomSheetLayoutMath.scrollAreaHeight(
            cardHeight: 200,
            headerHeight: 60,
            pinnedTopHeight: 100,
            pinnedBottomHeight: 56,
            bottomClearance: 26
        )
        XCTAssertEqual(height, OpenCodeBottomSheetLayoutMath.minScrollAreaHeight)
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.minScrollAreaHeight, 48)
    }

    func testPinnedBottomAddsItsGapOnlyWhenItRendersSomething() {
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.pinnedBottomTopSpacing, 8)
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.pinnedBottomHeight(contentHeight: 48), 56)
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.pinnedBottomHeight(contentHeight: 0), 0)
    }

    // MARK: Shared rules with Android

    func testCompactThresholdMatchesAndroid() {
        XCTAssertEqual(OpenCodeBottomSheetLayoutMath.compactMaxHeight, 420)
        XCTAssertTrue(OpenCodeBottomSheetLayoutMath.fitsCompact(fitContentHeight: 420))
        XCTAssertFalse(OpenCodeBottomSheetLayoutMath.fitsCompact(fitContentHeight: 421))
    }

    func testCompactBodyHeightFollowsTheBodyBothWays() {
        XCTAssertEqual(
            OpenCodeBottomSheetLayoutMath.nextContentHeight(current: 300, measured: 120, lockedScrollable: false, containerHeight: 866),
            120
        )
        XCTAssertEqual(
            OpenCodeBottomSheetLayoutMath.nextContentHeight(current: 120, measured: 300, lockedScrollable: false, containerHeight: 866),
            300
        )
        XCTAssertNil(OpenCodeBottomSheetLayoutMath.nextContentHeight(current: 120, measured: 120, lockedScrollable: false, containerHeight: 866))
        XCTAssertNil(OpenCodeBottomSheetLayoutMath.nextContentHeight(current: 120, measured: 0, lockedScrollable: false, containerHeight: 866))
    }

    func testMeasureBeforeTheOverlayHasASizeIsIgnored() {
        // The first pass lays out at zero size: text wraps at no width and reads far too tall.
        XCTAssertNil(
            OpenCodeBottomSheetLayoutMath.nextContentHeight(current: 0, measured: 775, lockedScrollable: false, containerHeight: 0)
        )
        XCTAssertEqual(
            OpenCodeBottomSheetLayoutMath.nextContentHeight(current: 0, measured: 160, lockedScrollable: false, containerHeight: 866),
            160
        )
    }

    func testLockedScrollableSheetStopsMeasuring() {
        XCTAssertNil(OpenCodeBottomSheetLayoutMath.nextContentHeight(current: 900, measured: 120, lockedScrollable: true, containerHeight: 866))
    }

    func testDragDismissesPastTheDistanceOrOnAFastFling() {
        XCTAssertFalse(OpenCodeBottomSheetLayoutMath.dragDismisses(offset: 95, velocity: 0))
        XCTAssertTrue(OpenCodeBottomSheetLayoutMath.dragDismisses(offset: 96, velocity: 0))
        XCTAssertTrue(OpenCodeBottomSheetLayoutMath.dragDismisses(offset: 10, velocity: 1200))
        XCTAssertFalse(OpenCodeBottomSheetLayoutMath.dragDismisses(offset: 10, velocity: 1199))
        XCTAssertFalse(OpenCodeBottomSheetLayoutMath.dragDismisses(offset: 10, velocity: -3000))
    }

    func testHiddenOffsetSlidesTheCardJustBelowTheEdge() {
        XCTAssertEqual(
            OpenCodeBottomSheetLayoutMath.hiddenOffset(cardHeight: 300, containerHeight: containerHeight, bottomSpace: 8),
            308
        )
        // Before the card measures, half the container keeps it off screen.
        XCTAssertEqual(
            OpenCodeBottomSheetLayoutMath.hiddenOffset(cardHeight: 0, containerHeight: containerHeight, bottomSpace: 8),
            445
        )
    }

    // MARK: Display corner radius

    func testDisplayCornerRadiusIsNilWhenThePrivateKeyIsMissing() {
        XCTAssertNil(BottomSheetDisplayCornerRadius.read(from: NSObject()))
    }

    func testCardBottomRadiusIsConcentricAndNeverBelowTheMinimum() {
        XCTAssertEqual(BottomSheetDisplayCornerRadius.cardBottomRadius(displayRadius: 55), 47)
        XCTAssertEqual(BottomSheetDisplayCornerRadius.cardBottomRadius(displayRadius: 0), 16)
    }

    func testDisplayCornerRadiusFallsBackWithoutAScreen() {
        XCTAssertEqual(BottomSheetDisplayCornerRadius.value(for: nil), 55)
    }
}
