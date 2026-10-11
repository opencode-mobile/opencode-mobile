package dev.opencode.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Screens that render as a leading list plus trailing detail on wide windows. */
enum class OpenCodeSplitPaneScreen {
    Home,
    Settings,
}

/** Persisted leading-pane geometry for one [OpenCodeSplitPaneScreen]. */
data class OpenCodeSplitPaneState(
    val leadingWidth: Dp = OpenCodeSplitPaneLayout.DefaultLeadingWidth,
    val isCollapsed: Boolean = false,
)

/** Pure sizing and gesture rules for [OpenCodeSplitPane]. */
object OpenCodeSplitPaneLayout {
    val MinLeadingWidth = 280.dp
    val MaxLeadingWidth = 480.dp
    val DefaultLeadingWidth = 360.dp

    /** Releasing a drag on an open pane below this width snaps it closed. */
    val CollapseThreshold = 220.dp

    /** Dragging a collapsed pane past this width reopens it at the remembered width. */
    val ReopenThreshold = 120.dp

    /** Window container width at which Home and Settings switch to the split layout. */
    val SplitMinWindowWidth = 720.dp

    /**
     * Smallest screen width that marks a tablet-class display. Phones stay stacked even when a
     * landscape window is wider than [SplitMinWindowWidth]; unfolded foldables qualify.
     */
    val SplitMinSmallestScreenWidth = 600.dp

    val DividerWidth = 1.dp
    val HandleGripWidth = 6.dp
    val HandleGripHeight = 40.dp
    val HandleTouchWidth = 28.dp
    val HandleTouchHeight = 72.dp

    /** Drags shorter than this count as a tap on the handle. */
    val HandleTapSlop = 4.dp

    const val ANIMATION_DURATION_MILLIS = 300

    fun clamp(width: Dp): Dp = width.coerceIn(MinLeadingWidth, MaxLeadingWidth)

    /**
     * Split only on tablet-class displays ([smallestScreenWidth], from
     * `Configuration.smallestScreenWidthDp`) whose window is at least [SplitMinWindowWidth] wide.
     */
    fun usesSplitLayout(
        windowWidth: Dp,
        smallestScreenWidth: Dp,
    ): Boolean = smallestScreenWidth >= SplitMinSmallestScreenWidth && windowWidth >= SplitMinWindowWidth

    fun resolveDragEnd(
        startedCollapsed: Boolean,
        rememberedWidth: Dp,
        proposedWidth: Dp,
    ): OpenCodeSplitPaneState {
        val remembered = clamp(rememberedWidth)
        return if (startedCollapsed) {
            OpenCodeSplitPaneState(
                leadingWidth = remembered,
                isCollapsed = proposedWidth <= ReopenThreshold,
            )
        } else if (proposedWidth < CollapseThreshold) {
            OpenCodeSplitPaneState(leadingWidth = remembered, isCollapsed = true)
        } else {
            OpenCodeSplitPaneState(leadingWidth = clamp(proposedWidth), isCollapsed = false)
        }
    }

    fun toggled(state: OpenCodeSplitPaneState): OpenCodeSplitPaneState =
        state.copy(leadingWidth = clamp(state.leadingWidth), isCollapsed = !state.isCollapsed)
}

@Composable
fun currentOpenCodeUsesSplitLayout(): Boolean {
    val containerSize = LocalWindowInfo.current.containerSize
    val width = with(LocalDensity.current) { containerSize.width.toDp() }
    val smallestScreenWidth = LocalConfiguration.current.smallestScreenWidthDp.dp
    return OpenCodeSplitPaneLayout.usesSplitLayout(width, smallestScreenWidth)
}
