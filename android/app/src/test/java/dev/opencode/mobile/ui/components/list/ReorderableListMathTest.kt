package dev.opencode.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ReorderableListMathTest {
    private val heights = listOf(60f, 60f, 60f, 60f)
    private val allMovable: (Int) -> Boolean = { true }
    private val pinnedFirst: (Int) -> Boolean = { it != 0 }

    @Test
    fun targetIndex_staysPutBelowHalfNeighbor() {
        assertEquals(1, ReorderableListMath.targetIndex(1, 29f, heights, allMovable))
        assertEquals(1, ReorderableListMath.targetIndex(1, -29f, heights, allMovable))
    }

    @Test
    fun targetIndex_crossesNeighborsAtMidpoints() {
        assertEquals(1, ReorderableListMath.targetIndex(0, 30f, heights, allMovable))
        assertEquals(2, ReorderableListMath.targetIndex(0, 95f, heights, allMovable))
        assertEquals(1, ReorderableListMath.targetIndex(3, -149f, heights, allMovable))
        assertEquals(0, ReorderableListMath.targetIndex(3, -150f, heights, allMovable))
    }

    @Test
    fun targetIndex_clampsToListBounds() {
        assertEquals(3, ReorderableListMath.targetIndex(2, 900f, heights, allMovable))
        assertEquals(0, ReorderableListMath.targetIndex(2, -900f, heights, allMovable))
    }

    @Test
    fun targetIndex_neverCrossesPinnedRow() {
        assertEquals(1, ReorderableListMath.targetIndex(2, -900f, heights, pinnedFirst))
    }

    @Test
    fun targetIndex_usesEachRowsOwnHeight() {
        val mixed = listOf(40f, 100f, 40f)
        assertEquals(0, ReorderableListMath.targetIndex(0, 49f, mixed, allMovable))
        assertEquals(1, ReorderableListMath.targetIndex(0, 50f, mixed, allMovable))
        assertEquals(2, ReorderableListMath.targetIndex(0, 120f, mixed, allMovable))
    }

    @Test
    fun displacement_shiftsOnlyRowsBetweenSourceAndTarget() {
        assertEquals(-60f, ReorderableListMath.displacement(index = 1, draggedIndex = 0, targetIndex = 2, draggedHeight = 60f))
        assertEquals(-60f, ReorderableListMath.displacement(index = 2, draggedIndex = 0, targetIndex = 2, draggedHeight = 60f))
        assertEquals(0f, ReorderableListMath.displacement(index = 3, draggedIndex = 0, targetIndex = 2, draggedHeight = 60f))
        assertEquals(60f, ReorderableListMath.displacement(index = 1, draggedIndex = 3, targetIndex = 1, draggedHeight = 60f))
        assertEquals(0f, ReorderableListMath.displacement(index = 0, draggedIndex = 3, targetIndex = 1, draggedHeight = 60f))
        assertEquals(0f, ReorderableListMath.displacement(index = 3, draggedIndex = 3, targetIndex = 1, draggedHeight = 60f))
    }

    @Test
    fun clampedTranslation_boundsToMovableSpan() {
        assertEquals(-60f, ReorderableListMath.clampedTranslation(-500f, 2, heights, pinnedFirst))
        assertEquals(60f, ReorderableListMath.clampedTranslation(500f, 2, heights, pinnedFirst))
        assertEquals(15f, ReorderableListMath.clampedTranslation(15f, 2, heights, pinnedFirst))
    }

    @Test
    fun move_reordersAndIgnoresInvalidIndices() {
        assertEquals(listOf("b", "c", "a"), ReorderableListMath.move(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), ReorderableListMath.move(listOf("a", "b", "c"), 2, 0))
        assertEquals(listOf("a", "b", "c"), ReorderableListMath.move(listOf("a", "b", "c"), 1, 1))
        assertEquals(listOf("a", "b", "c"), ReorderableListMath.move(listOf("a", "b", "c"), 5, 0))
    }

    @Test
    fun projectPickerRows_reportWorktreesWithoutAllRow() {
        val tabs =
            listOf(
                SessionListFilterTab(id = SessionListFilterTab.ALL_ID, title = "All", count = 3),
                SessionListFilterTab(id = "/a", title = "a", count = 1),
                SessionListFilterTab(id = "/b", title = "b", count = 1),
                SessionListFilterTab(id = "/c", title = "c", count = 1),
            )
        assertEquals(listOf("/c", "/a", "/b"), reorderedProjectPickerWorktrees(tabs, from = 3, to = 1))
    }
}
