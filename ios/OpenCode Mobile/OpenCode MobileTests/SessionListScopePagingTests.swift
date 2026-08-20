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

    @Test func displayedTranslation_midListTracksFinger() {
        #expect(
            SessionListScopePaging.displayedTranslation(
                translation: -40,
                pageWidth: 200,
                canGoPrevious: true,
                canGoNext: true
            ) == -40
        )
    }

    @Test func displayedTranslation_startRubberBands() {
        let displayed = SessionListScopePaging.displayedTranslation(
            translation: 100,
            pageWidth: 200,
            canGoPrevious: false,
            canGoNext: true
        )
        #expect(displayed > 0)
        #expect(displayed < 100)
    }
}
