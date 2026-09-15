import CoreGraphics
import Testing
@testable import OpenCode_Mobile

struct OpenCodeReorderableListMathTests {
    private let heights: [CGFloat] = [60, 60, 60, 60]
    private let allMovable: (Int) -> Bool = { _ in true }

    @Test func targetIndex_staysPutBelowHalfNeighbor() {
        #expect(
            OpenCodeReorderableListMath.targetIndex(from: 1, translation: 29, rowHeights: heights, canMove: allMovable) == 1
        )
        #expect(
            OpenCodeReorderableListMath.targetIndex(from: 1, translation: -29, rowHeights: heights, canMove: allMovable) == 1
        )
    }

    @Test func targetIndex_crossesNeighborsAtMidpoints() {
        #expect(
            OpenCodeReorderableListMath.targetIndex(from: 0, translation: 30, rowHeights: heights, canMove: allMovable) == 1
        )
        #expect(
            OpenCodeReorderableListMath.targetIndex(from: 0, translation: 95, rowHeights: heights, canMove: allMovable) == 2
        )
        #expect(
            OpenCodeReorderableListMath.targetIndex(from: 3, translation: -149, rowHeights: heights, canMove: allMovable) == 1
        )
        #expect(
            OpenCodeReorderableListMath.targetIndex(from: 3, translation: -150, rowHeights: heights, canMove: allMovable) == 0
        )
    }

    @Test func targetIndex_clampsToListBounds() {
        #expect(
            OpenCodeReorderableListMath.targetIndex(from: 2, translation: 900, rowHeights: heights, canMove: allMovable) == 3
        )
        #expect(
            OpenCodeReorderableListMath.targetIndex(from: 2, translation: -900, rowHeights: heights, canMove: allMovable) == 0
        )
    }

    @Test func targetIndex_neverCrossesPinnedRow() {
        let pinnedFirst: (Int) -> Bool = { $0 != 0 }
        #expect(
            OpenCodeReorderableListMath.targetIndex(from: 2, translation: -900, rowHeights: heights, canMove: pinnedFirst) == 1
        )
    }

    @Test func targetIndex_usesEachRowsOwnHeight() {
        let mixed: [CGFloat] = [40, 100, 40]
        #expect(OpenCodeReorderableListMath.targetIndex(from: 0, translation: 49, rowHeights: mixed, canMove: allMovable) == 0)
        #expect(OpenCodeReorderableListMath.targetIndex(from: 0, translation: 50, rowHeights: mixed, canMove: allMovable) == 1)
        #expect(OpenCodeReorderableListMath.targetIndex(from: 0, translation: 120, rowHeights: mixed, canMove: allMovable) == 2)
    }

    @Test func displacement_shiftsOnlyRowsBetweenSourceAndTarget() {
        #expect(OpenCodeReorderableListMath.displacement(forRowAt: 1, draggedIndex: 0, targetIndex: 2, draggedHeight: 60) == -60)
        #expect(OpenCodeReorderableListMath.displacement(forRowAt: 2, draggedIndex: 0, targetIndex: 2, draggedHeight: 60) == -60)
        #expect(OpenCodeReorderableListMath.displacement(forRowAt: 3, draggedIndex: 0, targetIndex: 2, draggedHeight: 60) == 0)
        #expect(OpenCodeReorderableListMath.displacement(forRowAt: 1, draggedIndex: 3, targetIndex: 1, draggedHeight: 60) == 60)
        #expect(OpenCodeReorderableListMath.displacement(forRowAt: 0, draggedIndex: 3, targetIndex: 1, draggedHeight: 60) == 0)
        #expect(OpenCodeReorderableListMath.displacement(forRowAt: 3, draggedIndex: 3, targetIndex: 1, draggedHeight: 60) == 0)
    }

    @Test func clampedTranslation_boundsToMovableSpan() {
        let pinnedFirst: (Int) -> Bool = { $0 != 0 }
        #expect(
            OpenCodeReorderableListMath.clampedTranslation(-500, from: 2, rowHeights: heights, canMove: pinnedFirst) == -60
        )
        #expect(
            OpenCodeReorderableListMath.clampedTranslation(500, from: 2, rowHeights: heights, canMove: pinnedFirst) == 60
        )
        #expect(
            OpenCodeReorderableListMath.clampedTranslation(15, from: 2, rowHeights: heights, canMove: pinnedFirst) == 15
        )
    }

    @Test func move_reordersAndIgnoresInvalidIndices() {
        #expect(OpenCodeReorderableListMath.move(["a", "b", "c"], from: 0, to: 2) == ["b", "c", "a"])
        #expect(OpenCodeReorderableListMath.move(["a", "b", "c"], from: 2, to: 0) == ["c", "a", "b"])
        #expect(OpenCodeReorderableListMath.move(["a", "b", "c"], from: 1, to: 1) == ["a", "b", "c"])
        #expect(OpenCodeReorderableListMath.move(["a", "b", "c"], from: 5, to: 0) == ["a", "b", "c"])
    }

    @Test func projectPickerRows_reportWorktreesWithoutAllRow() {
        let tabs = [
            SessionListFilterTab(id: SessionListFilterTab.allId, title: "All", count: 3),
            SessionListFilterTab(id: "/a", title: "a", count: 1),
            SessionListFilterTab(id: "/b", title: "b", count: 1),
            SessionListFilterTab(id: "/c", title: "c", count: 1),
        ]
        #expect(SessionListProjectPickerRows.reorderedWorktrees(tabs: tabs, from: 3, to: 1) == ["/c", "/a", "/b"])
    }
}
