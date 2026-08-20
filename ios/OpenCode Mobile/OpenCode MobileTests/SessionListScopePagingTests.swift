import CoreGraphics
import Testing
@testable import OpenCode_Mobile

struct SessionListScopePagingTests {
    @Test func neighborIndex_previousAtStartIsNil() {
        #expect(SessionListScopePaging.neighborIndex(selectedIndex: 0, count: 3, direction: .previous) == nil)
    }

    @Test func neighborIndex_nextAtEndIsNil() {
        #expect(SessionListScopePaging.neighborIndex(selectedIndex: 2, count: 3, direction: .next) == nil)
    }

    @Test func neighborIndex_pagesWithoutWrapping() {
        #expect(SessionListScopePaging.neighborIndex(selectedIndex: 0, count: 3, direction: .next) == 1)
        #expect(SessionListScopePaging.neighborIndex(selectedIndex: 1, count: 3, direction: .previous) == 0)
    }

    @Test func commitDirection_distanceSelectsNext() {
        #expect(
            SessionListScopePaging.commitDirection(
                translation: -36,
                velocity: 0,
                canGoPrevious: true,
                canGoNext: true
            ) == .next
        )
    }

    @Test func commitDirection_distanceSelectsPrevious() {
        #expect(
            SessionListScopePaging.commitDirection(
                translation: 36,
                velocity: 0,
                canGoPrevious: true,
                canGoNext: true
            ) == .previous
        )
    }

    @Test func commitDirection_shortDragWithoutFlickSnapsBack() {
        #expect(
            SessionListScopePaging.commitDirection(
                translation: -35,
                velocity: 0,
                canGoPrevious: true,
                canGoNext: true
            ) == nil
        )
    }

    @Test func commitDirection_flickSelectsNext() {
        #expect(
            SessionListScopePaging.commitDirection(
                translation: -8,
                velocity: -800,
                canGoPrevious: true,
                canGoNext: true
            ) == .next
        )
    }

    @Test func commitDirection_blockedEndDoesNotCommit() {
        #expect(
            SessionListScopePaging.commitDirection(
                translation: -80,
                velocity: -1200,
                canGoPrevious: true,
                canGoNext: false
            ) == nil
        )
        #expect(
            SessionListScopePaging.commitDirection(
                translation: 80,
                velocity: 1200,
                canGoPrevious: false,
                canGoNext: true
            ) == nil
        )
    }

    @Test func rubberBandOffset_resistsBothDirections() {
        let pull = SessionListScopePaging.rubberBandOffset(100)
        #expect(pull > 0)
        #expect(pull < SessionListScopePaging.rubberBandLimit)
        #expect(pull < 100)
        #expect(SessionListScopePaging.rubberBandOffset(-100) == -pull)
        #expect(SessionListScopePaging.rubberBandOffset(0) == 0)
    }

    @Test func displayedTranslation_midListTracksFinger() {
        #expect(
            SessionListScopePaging.displayedTranslation(
                translation: -40,
                pageWidth: 200,
                canGoPrevious: true,
                canGoNext: true,
                resistOverscroll: true
            ) == -40
        )
    }

    @Test func displayedTranslation_startRubberBands() {
        let displayed = SessionListScopePaging.displayedTranslation(
            translation: 100,
            pageWidth: 200,
            canGoPrevious: false,
            canGoNext: true,
            resistOverscroll: true
        )
        #expect(displayed > 0)
        #expect(displayed < SessionListScopePaging.rubberBandLimit)
        #expect(displayed < 100)
    }

    @Test func displayedTranslation_singleTabResistsAndDiminishes() {
        let shortPull = SessionListScopePaging.displayedTranslation(
            translation: 50,
            pageWidth: 200,
            canGoPrevious: false,
            canGoNext: false,
            resistOverscroll: true
        )
        let longPull = SessionListScopePaging.displayedTranslation(
            translation: 150,
            pageWidth: 200,
            canGoPrevious: false,
            canGoNext: false,
            resistOverscroll: true
        )
        #expect(shortPull > 0)
        #expect(longPull > shortPull)
        #expect(longPull - shortPull < shortPull)
        #expect(longPull < SessionListScopePaging.rubberBandLimit)
    }

    @Test func displayedTranslation_releaseUsesRawOffset() {
        #expect(
            SessionListScopePaging.displayedTranslation(
                translation: 12,
                pageWidth: 200,
                canGoPrevious: false,
                canGoNext: false,
                resistOverscroll: false
            ) == 12
        )
    }

    @Test func settleTranslation_commitsAFullPageThenSnaps() {
        #expect(SessionListScopePaging.settleTranslation(direction: .next, pageWidth: 200) == -200)
        #expect(SessionListScopePaging.settleTranslation(direction: .previous, pageWidth: 200) == 200)
        #expect(SessionListScopePaging.settleTranslation(direction: nil, pageWidth: 200) == 0)
    }
}
