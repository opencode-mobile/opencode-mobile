package dev.opencode.mobile.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Release speed (dp per second) that counts as a flick. Shared with iOS. */
@Suppress("ktlint:standard:property-naming")
internal const val BottomSheetFlickVelocityDp = 900f

/** A sheet released below this fraction of its lowest stop closes. Shared with iOS. */
@Suppress("ktlint:standard:property-naming")
internal const val BottomSheetDismissFraction = 0.6f

private val BottomSheetSettleSpec = spring<Float>(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)

/** Resting heights of a sheet's column, lowest first. One stop when [low] equals [high]. */
internal data class BottomSheetStops(
    val low: Dp,
    val high: Dp,
)

/**
 * Stops for a sheet of [height] in [layoutMode], as iOS's `.medium` and `.large` detents. A
 * scrollable sheet rests at [halfHeight] and expands to [fullHeight]; a FitAllContent sheet that
 * scrolls already sits at [fullHeight]. A compact sheet has one stop, its [fittedHeight], so it
 * keeps fitting its content. A measuring sheet has none yet.
 */
internal fun openCodeBottomSheetStops(
    height: OpenCodeBottomSheetHeight,
    layoutMode: BottomSheetLayoutMode,
    fittedHeight: Dp,
    halfHeight: Dp,
    fullHeight: Dp,
): BottomSheetStops? =
    when (layoutMode) {
        BottomSheetLayoutMode.Measuring -> null
        BottomSheetLayoutMode.Compact -> BottomSheetStops(fittedHeight, fittedHeight)
        BottomSheetLayoutMode.Scrollable ->
            if (height == OpenCodeBottomSheetHeight.FitAllContent) {
                BottomSheetStops(fullHeight, fullHeight)
            } else {
                BottomSheetStops(halfHeight, maxOf(halfHeight, fullHeight))
            }
    }

/**
 * Where a released sheet of [extent] settles, or null when it closes. [velocity] is positive
 * downward. A flick up expands to [high]; a flick down drops from above [low] to [low], and from
 * [low] closes. Otherwise the sheet closes below [BottomSheetDismissFraction] of [low] and
 * settles on the nearer stop.
 */
internal fun openCodeBottomSheetSettleTarget(
    extent: Float,
    velocity: Float,
    low: Float,
    high: Float,
    flickVelocity: Float,
): Float? =
    when {
        velocity > flickVelocity -> if (extent > low + 1f) low else null
        velocity < -flickVelocity -> high
        extent < low * BottomSheetDismissFraction -> null
        extent < (low + high) / 2f -> low
        else -> high
    }

/**
 * A sheet's height while open: one value, [extent], the visible height of its column in px.
 * Between the stops the column takes that height ([layoutHeight]); below the lowest stop the
 * column keeps it and slides down by [offset]. The header and the body's nested scroll drag it;
 * let go, it settles on a stop or asks to close.
 */
@Stable
internal class BottomSheetDetents(
    private val scope: CoroutineScope,
) {
    var extent by mutableFloatStateOf(Float.NaN)
        private set
    var low by mutableFloatStateOf(0f)
        private set
    var high by mutableFloatStateOf(0f)
        private set

    /** Whether the sheet rests (or is settling) at its higher stop. */
    var restsHigh by mutableStateOf(false)
        private set

    /** Off while the sheet exits, so it takes no more gestures. */
    var enabled = true
    var flickVelocity = 0f
    var onDismiss: () -> Unit = {}

    private var presentation = Int.MIN_VALUE
    private var dragging = false
    private var settleJob: Job? = null

    val hasStops: Boolean get() = high > 0f
    val expandable: Boolean get() = high > low + 1f
    val layoutHeight: Float get() = if (extent.isNaN()) low else extent.coerceIn(low, high)
    val offset: Float get() = if (extent.isNaN()) 0f else (low - extent).coerceAtLeast(0f)

    /** The sheet opened or closed: drop the drag, the settle, and the higher stop. */
    fun reset() {
        settleJob?.cancel()
        dragging = false
        restsHigh = false
        extent = Float.NaN
    }

    /**
     * New stops from layout. The first ones of a [presentation] are taken at once, at the lower
     * stop; later ones (the body grew past compact, the window resized) animate to the stop the
     * sheet rests on, so it never jumps.
     */
    fun setStops(
        newLow: Float,
        newHigh: Float,
        presentation: Int,
    ) {
        if (presentation != this.presentation) {
            this.presentation = presentation
            low = newLow
            high = newHigh
            extent = newLow
            return
        }
        if (newLow == low && newHigh == high) return
        if (extent.isNaN()) extent = low
        low = newLow
        high = newHigh
        if (!expandable) restsHigh = false
        if (dragging) {
            extent = extent.coerceAtMost(newHigh)
        } else {
            animateTo(if (restsHigh) newHigh else newLow)
        }
    }

    /** Moves the sheet by a finger [delta] (down positive); returns the part it took. */
    fun drag(delta: Float): Float {
        if (!enabled || !hasStops) return 0f
        settleJob?.cancel()
        if (extent.isNaN()) extent = low
        dragging = true
        val before = extent
        extent = (extent - delta).coerceIn(0f, high)
        return before - extent
    }

    /** Lets go of a drag at [velocity] (down positive). */
    fun settle(velocity: Float) {
        if (!dragging) return
        dragging = false
        val target = openCodeBottomSheetSettleTarget(extent, velocity, low, high, flickVelocity)
        if (target == null) {
            onDismiss()
            return
        }
        restsHigh = expandable && target >= high
        animateTo(target)
    }

    /** Accessibility: rest at the higher stop. */
    fun expand() {
        if (!enabled || !expandable) return
        restsHigh = true
        animateTo(high)
    }

    /** Accessibility: rest at the lower stop. */
    fun collapse() {
        if (!enabled) return
        restsHigh = false
        animateTo(low)
    }

    private fun animateTo(target: Float) {
        settleJob?.cancel()
        val start = if (extent.isNaN()) low else extent
        // The system animator scale applies, so Remove animations settles at once.
        settleJob =
            scope.launch {
                animate(start, target, animationSpec = BottomSheetSettleSpec) { value, _ -> extent = value }
            }
    }

    /**
     * The body hands drags to the sheet: pushed up, the sheet grows to its higher stop before the
     * body scrolls; pulled down with the body at its top, the sheet shrinks and can close.
     */
    val nestedScroll =
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset =
                if (source == NestedScrollSource.UserInput && available.y < 0f && extentOrLow() < high) {
                    Offset(0f, drag(available.y))
                } else {
                    Offset.Zero
                }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset =
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    Offset(0f, drag(available.y))
                } else {
                    Offset.Zero
                }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!dragging) return Velocity.Zero
                if (extent >= high - 0.5f) {
                    // Grown all the way and the body took over: the body flings on.
                    dragging = false
                    restsHigh = expandable
                    return Velocity.Zero
                }
                settle(available.y)
                return available
            }
        }

    private fun extentOrLow(): Float = if (extent.isNaN()) low else extent
}
