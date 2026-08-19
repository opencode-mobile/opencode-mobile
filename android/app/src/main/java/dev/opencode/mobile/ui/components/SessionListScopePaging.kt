package dev.opencode.mobile.ui.components

import kotlin.math.max
import kotlin.math.min

object SessionListScopePaging {
    const val COMMIT_DISTANCE = 36f
    const val FLICK_VELOCITY = 800f
    const val SETTLE_DURATION_MS = 220
    const val LIST_CROSSFADE_DURATION_MS = 160

    enum class Direction {
        Previous,
        Next,
    }

    fun neighborIndex(
        selectedIndex: Int,
        count: Int,
        direction: Direction,
    ): Int? =
        when (direction) {
            Direction.Previous -> (selectedIndex - 1).takeIf { it >= 0 }
            Direction.Next -> (selectedIndex + 1).takeIf { it < count }
        }

    fun displayedTranslation(
        translation: Float,
        pageWidth: Float,
        canGoPrevious: Boolean,
        canGoNext: Boolean,
    ): Float {
        val width = max(pageWidth, 1f)
        if (translation > 0f && !canGoPrevious) {
            return rubberBand(translation, width)
        }
        if (translation < 0f && !canGoNext) {
            return -rubberBand(-translation, width)
        }
        if (canGoPrevious || canGoNext) {
            return min(max(translation, -width), width)
        }
        return translation
    }

    fun commitDirection(
        translation: Float,
        velocity: Float,
        canGoPrevious: Boolean,
        canGoNext: Boolean,
    ): Direction? {
        if (canGoNext && (translation <= -COMMIT_DISTANCE || velocity <= -FLICK_VELOCITY)) {
            return Direction.Next
        }
        if (canGoPrevious && (translation >= COMMIT_DISTANCE || velocity >= FLICK_VELOCITY)) {
            return Direction.Previous
        }
        return null
    }

    private fun rubberBand(
        offset: Float,
        dimension: Float,
    ): Float {
        val constant = 0.55f
        return (1f - 1f / (offset * constant / dimension + 1f)) * dimension
    }
}
