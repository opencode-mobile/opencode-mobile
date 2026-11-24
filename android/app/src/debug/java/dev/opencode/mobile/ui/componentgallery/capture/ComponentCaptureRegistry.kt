package dev.opencode.mobile.ui.componentgallery.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor

/**
 * Native renders of the HTML design system gallery's states, one entry per state in
 * `scripts/component-gallery/captures/contract.json`. `ComponentCaptureTest` renders each
 * [ComponentCaptureState] inside [ComponentCaptureTile] and saves it as a PNG.
 *
 * Each category lives in its own `*Captures.kt` file; adding a component means adding a
 * [ComponentCaptureComponent] there and listing it in [ComponentCaptureRegistry.components].
 */
internal class ComponentCaptureComponent(
    /** The contract's component id, such as `filter-tabs`. */
    val id: String,
    val states: List<ComponentCaptureState>,
    /** Contract states Android cannot draw, with the reason recorded in the manifest. */
    val skipped: List<ComponentCaptureSkip> = emptyList(),
    /** Where the component sits in its full-width tile; the HTML gallery's `align`. */
    val alignment: Alignment = Alignment.TopStart,
    /** A fixed tile height for overlays and full-screen components; the HTML `cellHeight`. */
    val height: Dp? = null,
    /**
     * Set for components that draw in their own window (dialogs, popups, sheets): the tile sits where
     * the overlay docks on screen and the capture crops a screenshot of every window to the tile.
     */
    val overlay: CaptureOverlay? = null,
)

internal enum class CaptureOverlay {
    /** Popups anchored inside the tile. */
    Top,

    /** Dialogs and modals centered on the screen. */
    Center,

    /** Sheets that rise from the bottom edge. */
    Bottom,
}

internal class ComponentCaptureState(
    /** The contract's state id, such as `segmented-done`; extra reference tiles add `-2`, `-3`. */
    val id: String,
    /** Hold a touch down before capturing: the real pressed state. */
    val press: CapturePress? = null,
    /** Where the capture only approximates the contract's props. */
    val note: String? = null,
    /** Overrides the component's tile height for this state. */
    val height: Dp? = null,
    /** Overrides the component's overlay placement for this state, such as an opened popover. */
    val overlay: CaptureOverlay? = null,
    /**
     * For reference pages taller than a screen: the content is cut into pages this tall, captured as
     * [id], then `id-2`, `id-3` and so on. [CapturePaging] carries the page being drawn.
     */
    val pageHeight: Dp? = null,
    val content: @Composable () -> Unit,
) {
    fun height(component: ComponentCaptureComponent): Dp? = height ?: component.height

    fun overlay(component: ComponentCaptureComponent): CaptureOverlay? = overlay ?: component.overlay
}

/**
 * Where to hold the touch: on the node tagged [CAPTURE_PRESS_TAG] when the content has one, else the
 * tile. [fromStart] or [fromEnd] press that far in from the node's start or end edge instead of its center.
 */
internal class CapturePress(
    val fromStart: Dp? = null,
    val fromEnd: Dp? = null,
    val gesture: CaptureGesture = CaptureGesture.Hold,
)

internal enum class CaptureGesture {
    /** Touch down and hold through the capture. */
    Hold,

    /** A tap, released before the capture. */
    Tap,

    /** Touch down past the long-press timeout and hold through the capture. */
    LongPressHold,

    /** Swipe toward the start and release, leaving a revealed row action open. */
    SwipeOpen,

    /** Drag toward the start and hold, as a time reveal needs. */
    DragStartHold,

    /** Drag content down and release, scrolling a list back from its end. */
    ScrollBack,
}

internal const val CAPTURE_PRESS_TAG = "capture.press"

/** The page of a paged state being drawn, and the full content height the tile measured. */
internal object CapturePaging {
    var page by mutableIntStateOf(0)
    var contentHeightPx by mutableIntStateOf(0)

    fun pageId(
        stateId: String,
        page: Int,
    ): String = if (page == 0) stateId else "$stateId-${page + 1}"
}

internal class ComponentCaptureSkip(
    val id: String,
    val reason: String,
)

internal fun componentCaptureTag(
    componentId: String,
    stateId: String,
): String = "capture.$componentId.$stateId"

/** A screen-wide container with 16 dp padding on `background-base`; the capture is cropped to it. */
@Composable
internal fun ComponentCaptureTile(
    component: ComponentCaptureComponent,
    state: ComponentCaptureState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                // Docked composers pad for the navigation bar and keyboard; a tile has neither.
                .consumeWindowInsets(WindowInsets.safeDrawing)
                .fillMaxWidth()
                .then(state.height(component)?.let { Modifier.height(it) } ?: Modifier)
                .testTag(componentCaptureTag(component.id, state.id))
                .background(openCodeColor("background-base"))
                .padding(16.dp),
        contentAlignment = component.alignment,
    ) {
        // The content color the app's route Surface gives every screen, for text that does not set one.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            val pageHeight = state.pageHeight
            if (pageHeight == null) {
                state.content()
            } else {
                PagedContent(pageHeight = pageHeight, content = state.content)
            }
        }
    }
}

/** Shows one [pageHeight] slice of content laid out at its full, unbounded height. */
@Composable
private fun PagedContent(
    pageHeight: Dp,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val pagePx = with(density) { pageHeight.roundToPx() }
    val page = CapturePaging.page
    val measured = CapturePaging.contentHeightPx
    val visiblePx = if (measured > 0) (measured - page * pagePx).coerceIn(1, pagePx) else pagePx
    Box(
        Modifier
            .fillMaxWidth()
            .height(with(density) { visiblePx.toDp() })
            .clipToBounds(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .offset { IntOffset(0, -page * pagePx) }
                .onSizeChanged { CapturePaging.contentHeightPx = it.height },
        ) {
            content()
        }
    }
}

internal object ComponentCaptureRegistry {
    val components: List<ComponentCaptureComponent> by lazy {
        controlsCaptures() + listsCaptures() + navigationCaptures() + chatCaptures() +
            sessionCaptures() + diffsCaptures() + foundationsCaptures()
    }
}
