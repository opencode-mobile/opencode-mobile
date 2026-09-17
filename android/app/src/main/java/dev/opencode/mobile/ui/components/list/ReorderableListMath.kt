package dev.opencode.mobile.ui.components

/**
 * Pure geometry for long-press drag reordering of stacked rows.
 *
 * Shared meaning with iOS `OpenCodeReorderableListMath`: the dragged row
 * settles at the first neighbor whose midpoint it has crossed, never crosses a
 * row that cannot move, and displaced rows shift by the dragged row's height.
 */
object ReorderableListMath {
    /** Width of the action strip a row reveals when swiped open. */
    const val SWIPE_REVEAL_WIDTH_DP = 88f

    /** Reveal past this much and the release settles open instead of closed. */
    const val SWIPE_OPEN_THRESHOLD_DP = 44f

    fun targetIndex(
        from: Int,
        translation: Float,
        rowHeights: List<Float>,
        canMove: (Int) -> Boolean,
    ): Int {
        if (from !in rowHeights.indices) return from
        var target = from
        var passed = 0f
        if (translation > 0f) {
            var candidate = from + 1
            while (candidate < rowHeights.size && canMove(candidate)) {
                val height = rowHeights[candidate]
                if (translation < passed + height / 2f) break
                target = candidate
                passed += height
                candidate += 1
            }
        } else if (translation < 0f) {
            var candidate = from - 1
            while (candidate >= 0 && canMove(candidate)) {
                val height = rowHeights[candidate]
                if (-translation < passed + height / 2f) break
                target = candidate
                passed += height
                candidate -= 1
            }
        }
        return target
    }

    fun displacement(
        index: Int,
        draggedIndex: Int,
        targetIndex: Int,
        draggedHeight: Float,
    ): Float {
        if (index == draggedIndex) return 0f
        if (draggedIndex < index && index <= targetIndex) return -draggedHeight
        if (targetIndex <= index && index < draggedIndex) return draggedHeight
        return 0f
    }

    /** Keeps the lifted row inside the span of rows it is allowed to cross. */
    fun clampedTranslation(
        translation: Float,
        from: Int,
        rowHeights: List<Float>,
        canMove: (Int) -> Boolean,
    ): Float {
        if (from !in rowHeights.indices) return translation
        var upward = 0f
        var candidate = from - 1
        while (candidate >= 0 && canMove(candidate)) {
            upward += rowHeights[candidate]
            candidate -= 1
        }
        var downward = 0f
        candidate = from + 1
        while (candidate < rowHeights.size && canMove(candidate)) {
            downward += rowHeights[candidate]
            candidate += 1
        }
        return translation.coerceIn(-upward, downward)
    }

    fun <T> move(
        items: List<T>,
        from: Int,
        to: Int,
    ): List<T> {
        if (from !in items.indices || to !in items.indices || from == to) return items
        return items.toMutableList().apply { add(to, removeAt(from)) }
    }
}
