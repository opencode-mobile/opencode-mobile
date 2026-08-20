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

    @Test func commitDirection_visualThresholdSelectsNext() {
        #expect(
            SessionListScopePaging.commitDirection(
                visualOffset: -28,
                canGoPrevious: true,
                canGoNext: true
            ) == .next
        )
    }

    @Test func commitDirection_visualThresholdSelectsPrevious() {
        #expect(
            SessionListScopePaging.commitDirection(
                visualOffset: 28,
                canGoPrevious: true,
                canGoNext: true
            ) == .previous
        )
    }

    @Test func commitDirection_shortPullSnapsBack() {
        #expect(
            SessionListScopePaging.commitDirection(
                visualOffset: -27,
                canGoPrevious: true,
                canGoNext: true
            ) == nil
        )
    }

    @Test func commitDirection_blockedEndDoesNotCommit() {
        #expect(
            SessionListScopePaging.commitDirection(
                visualOffset: -40,
                canGoPrevious: true,
                canGoNext: false
            ) == nil
        )
        #expect(
            SessionListScopePaging.commitDirection(
                visualOffset: 40,
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

    @Test func settleTranslation_exitsAFullSlot() {
        #expect(SessionListScopePaging.settleTranslation(direction: .next, pageWidth: 200) == -200)
        #expect(SessionListScopePaging.settleTranslation(direction: .previous, pageWidth: 200) == 200)
        #expect(SessionListScopePaging.settleTranslation(direction: nil, pageWidth: 200) == 0)
    }
}
