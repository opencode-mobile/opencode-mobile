package dev.opencode.mobile.ui.components

import androidx.compose.ui.unit.dp

/** How close to the end of a sheet body still counts as parked at the bottom. */
internal val BottomSheetParkedThreshold = 24.dp

/** What a following sheet body should do after its content changes size. */
internal enum class BottomSheetFollowAction {
    None,

    /** Scroll to the end of the body. */
    Bottom,

    /** Scroll the marked row back into view; the body has not been moved since it was asked for. */
    Target,
}

/**
 * Whether a sheet body keeps its newest rows in view (#879). A body that follows
 * stays at its bottom as it grows while the reader is parked there; scrolling
 * away stops it and coming back resumes it. A scroll target on a pane wins: the
 * body only follows again once the reader is back at the bottom.
 *
 * Mirrors the iOS `OpenCodeBottomSheetFollow`.
 */
internal class BottomSheetFollow {
    var parked: Boolean = false
        private set
    private var targetPending = false

    /** A new pane: start at its bottom when it [follows], unless it scrolls to a target. */
    fun paneChanged(
        follows: Boolean,
        hasTarget: Boolean,
    ) {
        targetPending = hasTarget
        parked = follows && !hasTarget
    }

    /** The pane scrolled to its target row, so it is no longer parked. */
    fun targetScrolled() {
        targetPending = true
        parked = false
    }

    fun contentChanged(follows: Boolean): BottomSheetFollowAction =
        when {
            targetPending -> BottomSheetFollowAction.Target
            follows && parked -> BottomSheetFollowAction.Bottom
            else -> BottomSheetFollowAction.None
        }

    /** The body moved (the reader, or a scroll this asked for); [distanceFromBottom] is what is left below. */
    fun offsetChanged(
        distanceFromBottom: Float,
        threshold: Float,
    ) {
        targetPending = false
        parked = distanceFromBottom <= threshold
    }
}
