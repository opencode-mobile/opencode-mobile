package dev.opencode.mobile.ui.components

import kotlin.math.max
import kotlin.math.min

object SessionListScopePaging {
    const val COMMIT_DISTANCE = 36f
    const val FLICK_VELOCITY = 800f
    const val SETTLE_DURATION_MS = 400
    const val LIST_CROSSFADE_DURATION_MS = 160
    const val RUBBER_BAND_LIMIT_DP = 52f
    const val RUBBER_BAND_CONSTANT = 0.50f
    const val RUBBER_BAND_RETURN_DURATION_MS = 180

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

    fun rubberBandOffset(
        translation: Float,
        rubberBandLimit: Float = RUBBER_BAND_LIMIT_DP,
    ): Float =
        when {
            translation > 0f -> rubberBand(translation, rubberBandLimit)
            translation < 0f -> -rubberBand(-translation, rubberBandLimit)
            else -> 0f
        }

    fun displayedTranslation(
        translation: Float,
        pageWidth: Float,
        canGoPrevious: Boolean,
        canGoNext: Boolean,
        resistOverscroll: Boolean,
        rubberBandLimit: Float = RUBBER_BAND_LIMIT_DP,
    ): Float {
        val width = max(pageWidth, 1f)
        if (resistOverscroll) {
            if (translation > 0f && !canGoPrevious) {
                return rubberBand(translation, rubberBandLimit)
            }
            if (translation < 0f && !canGoNext) {
                return -rubberBand(-translation, rubberBandLimit)
            }
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

    fun settleTranslation(
        direction: Direction?,
        pageWidth: Float,
    ): Float {
        val width = max(pageWidth, 1f)
        return when (direction) {
            Direction.Next -> -width
            Direction.Previous -> width
            null -> 0f
        }
    }

    private fun rubberBand(
        offset: Float,
        dimension: Float,
    ): Float {
        val width = max(dimension, 1f)
        return (1f - 1f / (offset * RUBBER_BAND_CONSTANT / width + 1f)) * width
    }
}
