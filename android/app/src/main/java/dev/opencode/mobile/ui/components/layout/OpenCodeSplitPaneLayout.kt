package dev.opencode.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Screens that render as a leading list plus trailing detail on wide windows. */
enum class OpenCodeSplitPaneScreen {
    Home,
    Settings,
    Changes,
    Terminals,
    ;

    /** Sidebar panes use a fixed 280–480dp leading width. Content panes keep both columns at least 500dp. */
    val usesContentMetrics: Boolean
        get() =
            when (this) {
                Home, Settings -> false
                Changes, Terminals -> true
            }
}

/** Persisted leading-pane geometry for one [OpenCodeSplitPaneScreen]. */
data class OpenCodeSplitPaneState(
    val leadingWidth: Dp = OpenCodeSplitPaneLayout.DefaultLeadingWidth,
    val isCollapsed: Boolean = false,
)

/** Width range and snap thresholds for one split-pane role. */
data class OpenCodeSplitPaneBounds(
    val minLeading: Dp,
    val maxLeading: Dp,
    val defaultLeading: Dp,
    val collapseThreshold: Dp,
    val reopenThreshold: Dp,
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

    val DividerWidth = 1.dp
    val HandleGripWidth = 6.dp
    val HandleGripHeight = 40.dp
    val HandleTouchWidth = 28.dp
    val HandleTouchHeight = 72.dp

    /** Drags shorter than this count as a tap on the handle. */
    val HandleTapSlop = 4.dp

    const val ANIMATION_DURATION_MILLIS = 300

    /** Neither column of a Changes or Terminals split may settle narrower than this. */
    val MinContentColumn = 500.dp

    /** Remembered open width for a content split before the user drags. */
    val ContentDefaultLeadingWidth = 560.dp

    /** Smallest window that can show two content columns plus the divider. */
    val ContentSplitMinimumWidth = MinContentColumn * 2 + DividerWidth

    val sidebarBounds =
        OpenCodeSplitPaneBounds(
            minLeading = MinLeadingWidth,
            maxLeading = MaxLeadingWidth,
            defaultLeading = DefaultLeadingWidth,
            collapseThreshold = CollapseThreshold,
            reopenThreshold = ReopenThreshold,
        )

    fun usesSplitLayout(windowWidth: Dp): Boolean = windowWidth >= SplitMinWindowWidth

    fun usesContentSplit(containerWidth: Dp): Boolean = containerWidth >= ContentSplitMinimumWidth

    fun contentBounds(containerWidth: Dp): OpenCodeSplitPaneBounds {
        val maxLeading = (containerWidth - MinContentColumn - DividerWidth).coerceAtLeast(MinContentColumn)
        return OpenCodeSplitPaneBounds(
            minLeading = MinContentColumn,
            maxLeading = maxLeading,
            defaultLeading = ContentDefaultLeadingWidth.coerceIn(MinContentColumn, maxLeading),
            collapseThreshold = CollapseThreshold,
            reopenThreshold = ReopenThreshold,
        )
    }

    fun bounds(
        screen: OpenCodeSplitPaneScreen,
        containerWidth: Dp,
    ): OpenCodeSplitPaneBounds = if (screen.usesContentMetrics) contentBounds(containerWidth) else sidebarBounds

    /**
     * Home's persisted collapsed flag, overlaid while a nested content split is showing.
     * The overlay is not written back to preferences.
     */
    fun displayedCollapsed(
        persisted: Boolean,
        nestedContentSplitVisible: Boolean,
    ): Boolean = persisted || nestedContentSplitVisible

    fun clamp(width: Dp): Dp = clamp(width, sidebarBounds)

    fun clamp(
        width: Dp,
        bounds: OpenCodeSplitPaneBounds,
    ): Dp = width.coerceIn(bounds.minLeading, bounds.maxLeading)

    fun resolveDragEnd(
        startedCollapsed: Boolean,
        rememberedWidth: Dp,
        proposedWidth: Dp,
    ): OpenCodeSplitPaneState = resolveDragEnd(startedCollapsed, rememberedWidth, proposedWidth, sidebarBounds)

    fun resolveDragEnd(
        startedCollapsed: Boolean,
        rememberedWidth: Dp,
        proposedWidth: Dp,
        bounds: OpenCodeSplitPaneBounds,
    ): OpenCodeSplitPaneState {
        val remembered = clamp(rememberedWidth, bounds)
        return if (startedCollapsed) {
            OpenCodeSplitPaneState(
                leadingWidth = remembered,
                isCollapsed = proposedWidth <= bounds.reopenThreshold,
            )
        } else if (proposedWidth < bounds.collapseThreshold) {
            OpenCodeSplitPaneState(leadingWidth = remembered, isCollapsed = true)
        } else {
            OpenCodeSplitPaneState(leadingWidth = clamp(proposedWidth, bounds), isCollapsed = false)
        }
    }

    fun toggled(state: OpenCodeSplitPaneState): OpenCodeSplitPaneState = toggled(state, sidebarBounds)

    fun toggled(
        state: OpenCodeSplitPaneState,
        bounds: OpenCodeSplitPaneBounds,
    ): OpenCodeSplitPaneState = state.copy(leadingWidth = clamp(state.leadingWidth, bounds), isCollapsed = !state.isCollapsed)
}

@Composable
fun currentOpenCodeWindowWidth(): Dp {
    val containerSize = LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) { containerSize.width.toDp() }
}

@Composable
fun currentOpenCodeUsesSplitLayout(): Boolean = OpenCodeSplitPaneLayout.usesSplitLayout(currentOpenCodeWindowWidth())

@Composable
fun currentOpenCodeUsesContentSplit(): Boolean = OpenCodeSplitPaneLayout.usesContentSplit(currentOpenCodeWindowWidth())
