package dev.opencode.mobile.ui.components

import kotlin.math.max

object SessionListScopePaging {
    const val SWITCH_THRESHOLD_DP = 28f
    const val SWITCH_DURATION_MS = 450
    const val LIST_CROSSFADE_DURATION_MS = 400
    const val RUBBER_BAND_LIMIT_DP = 52f
    const val RUBBER_BAND_CONSTANT = 0.50f
    const val RUBBER_BAND_RETURN_DURATION_MS = 180
    const val EDGE_FADE_WIDTH_DP = 16f
    const val EDGE_FADE_DURATION_MS = 200

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

    fun commitDirection(
        visualOffset: Float,
        canGoPrevious: Boolean,
        canGoNext: Boolean,
        switchThreshold: Float = SWITCH_THRESHOLD_DP,
    ): Direction? {
        if (canGoNext && visualOffset <= -switchThreshold) {
            return Direction.Next
        }
        if (canGoPrevious && visualOffset >= switchThreshold) {
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
