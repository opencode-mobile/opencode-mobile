package dev.opencode.mobile.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.core.view.ViewCompat
import dev.opencode.mobile.R
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val BottomSheetInset = 8.dp
private val BottomSheetDisplayCornerRadiusFallback = 55.dp
private val BottomSheetMinCornerRadius = 16.dp
private val BottomSheetCornerRadiusTop = 24.dp

@Suppress("ktlint:standard:property-naming")
private const val BottomSheetEnterDurationMillis = 320

@Suppress("ktlint:standard:property-naming")
private const val BottomSheetExitDurationMillis = 280

/** Enter curve, shared with iOS: decelerate onto the resting position. */
private val BottomSheetEnterEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/** Exit curve, shared with iOS: accelerate off the bottom edge. */
private val BottomSheetExitEasing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

@Suppress("ktlint:standard:property-naming")
private const val BottomSheetScrimAlpha = 0.32f

private val BottomSheetDragDismissThreshold = 96.dp

/** Downward release speed (dp per second) that dismisses a header drag of any length. */
@Suppress("ktlint:standard:property-naming")
private const val BottomSheetDragDismissVelocityDp = 1_200f
private val BottomSheetHeaderHorizontalPadding = OpenCodeLayout.SheetContentHorizontalInset
private val BottomSheetHeaderTopPadding = 12.dp
private val BottomSheetHeaderBottomPadding = 8.dp
private val BottomSheetContentBottomPadding = 20.dp
private val BottomSheetFooterTopPadding = 8.dp
private val BottomSheetFooterHorizontalPadding = OpenCodeLayout.SheetContentHorizontalInset
private val BottomSheetEntryMinHeight = 48.dp
private val BottomSheetEntryHorizontalPadding = OpenCodeLayout.SheetContentHorizontalInset
private val BottomSheetEntryVerticalPadding = 12.dp
private val BottomSheetEntryDecoratorSize = 24.dp
private val BottomSheetEntryIconSize = 20.dp

@Suppress("ktlint:standard:property-naming")
private const val BottomSheetEntryMaxLines = 2

@Suppress("ktlint:standard:property-naming")
private const val BottomSheetScrollableHeightFraction = 0.50f

/** Tallest FitContent sheet that still wraps its content, shared with iOS. */
internal val BottomSheetCompactMaxHeight = 420.dp
private val BottomSheetEstimatedHeaderHeight =
    BottomSheetHeaderTopPadding + BottomSheetHeaderBottomPadding + 40.dp

internal enum class BottomSheetLayoutMode {
    Measuring,
    Compact,
    Scrollable,
}

/** Resting height of an [OpenCodeBottomSheet]. */
enum class OpenCodeBottomSheetHeight {
    /**
     * Compact while the header, pinned slots, and body fit; otherwise the scrollable half height.
     * A compact sheet follows its content up and down. Once a sheet reaches half height it stays
     * there until it switches height.
     */
    FitContent,

    /** Fixed at the scrollable half height from the first frame. */
    Half,
}

/** How an [OpenCodeBottomSheet] treats the software keyboard. */
enum class OpenCodeBottomSheetKeyboard {
    /** Clears focus and hides the keyboard when the sheet appears or switches to this policy. */
    Dismiss,

    /** Leaves the keyboard up; the sheet keeps its resting height and rides above it. */
    Keep,

    /**
     * Leaves the keyboard up; while it is visible the sheet fills the space between the top
     * system inset and the keyboard, never shrinking below its resting height. Header and pinned
     * slots stay fixed; the body scrolls in the rest.
     */
    Expand,
}

/** Leading or trailing icon of an [OpenCodeBottomSheetEntry]. Decorative: the entry title is its label. */
sealed class BottomSheetEntryDecorator {
    data object None : BottomSheetEntryDecorator()

    data class Icon(
        val imageVector: ImageVector,
    ) : BottomSheetEntryDecorator()
}

data class OpenCodeBottomSheetHeaderAction(
    val imageVector: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit,
)

/**
 * Text action at the trailing end of an [OpenCodeBottomSheet] header, before Close (for
 * example Edit / Done). Disabled, it stays visible.
 */
data class OpenCodeBottomSheetHeaderTextAction(
    val title: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val testTag: String? = null,
)

/**
 * Modal bottom sheet with a header, an optional [pinnedTop] slot under it, a scrolling body
 * ([content]), and an optional [pinnedBottom] footer.
 *
 * The sheet is controlled: it shows while [visible] is true. A dismiss gesture (scrim, Close,
 * back, header drag) calls [onDismissRequest] at once, and the sheet plays its exit motion when
 * the caller sets [visible] to false. A caller can open another sheet or navigate straight from
 * [onDismissRequest]. Keep the sheet in composition and drive [visible]; removing it skips the
 * exit motion. While it exits, the sheet keeps showing the last content it had while visible.
 *
 * [height] sets the resting height and [keyboard] the software keyboard policy; the two are
 * independent. [pinnedBottom] is never scrolled and stays above the keyboard and the navigation
 * bar. The sheet gives it its top spacing, horizontal inset, and bottom clearance, so callers do
 * not pad it. Pass null (the default) for no footer. Free-form body and [pinnedTop] content takes
 * the standard inset from [openCodeBottomSheetContentInset]; entries and list rows carry their own.
 *
 * [scrollTargetKey] scrolls the body so the row marked with the same key through
 * [openCodeBottomSheetScrollTarget] sits centered once the sheet is scrollable.
 */
@Composable
fun OpenCodeBottomSheet(
    visible: Boolean,
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    headerLeadingAction: OpenCodeBottomSheetHeaderAction? = null,
    headerTrailingAction: OpenCodeBottomSheetHeaderTextAction? = null,
    height: OpenCodeBottomSheetHeight = OpenCodeBottomSheetHeight.FitContent,
    keyboard: OpenCodeBottomSheetKeyboard = OpenCodeBottomSheetKeyboard.Dismiss,
    scrollTargetKey: Any? = null,
    pinnedTop: @Composable ColumnScope.() -> Unit = {},
    pinnedBottom: (@Composable ColumnScope.() -> Unit)? = null,
    /** Replaces the header's [title] text (a live status, say); [title] stays the pane title. */
    titleContent: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var shouldRender by remember { mutableStateOf(visible) }
    var sheetVisible by remember { mutableStateOf(false) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val dragDismissThresholdPx = with(density) { BottomSheetDragDismissThreshold.toPx() }
    val dragDismissVelocityPx = with(density) { BottomSheetDragDismissVelocityDp.dp.toPx() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)

    // The last slots seen while visible, so the exit motion does not show a body that already
    // reacted to the caller clearing its state (a null target, an empty list).
    val shown = remember { BottomSheetShownSlots() }
    if (visible || !shown.isSet) {
        shown.update(title, headerLeadingAction, headerTrailingAction, height, keyboard, scrollTargetKey, pinnedTop, pinnedBottom, content, titleContent)
    }

    LaunchedEffect(visible, keyboard) {
        if (visible && keyboard == OpenCodeBottomSheetKeyboard.Dismiss) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }

    LaunchedEffect(visible) {
        if (visible) {
            dragOffsetPx = 0f
            shouldRender = true
            sheetVisible = true
        } else if (shouldRender) {
            sheetVisible = false
            delay(BottomSheetExitDurationMillis.toLong())
            dragOffsetPx = 0f
            shouldRender = false
        }
    }

    // The sheet is an overlay in the activity window, so window-level key handling must be told it is up.
    OpenCodeModalSurfaceEffect(shown = visible || shouldRender)

    if (!shouldRender) return

    // Only a visible sheet asks to close; one that is already exiting ignores further gestures.
    val requestDismiss: () -> Unit = {
        if (sheetVisible) currentOnDismissRequest()
    }

    val dragState =
        rememberDraggableState { delta ->
            dragOffsetPx = (dragOffsetPx + delta).coerceAtLeast(0f)
        }

    val scrimAlpha by animateFloatAsState(
        targetValue = if (sheetVisible) BottomSheetScrimAlpha else 0f,
        animationSpec =
            if (sheetVisible) {
                tween(BottomSheetEnterDurationMillis, easing = BottomSheetEnterEasing)
            } else {
                tween(BottomSheetExitDurationMillis, easing = BottomSheetExitEasing)
            },
        label = "BottomSheetScrimAlpha",
    )

    val displayCornerRadius = rememberBottomSheetCornerRadius()
    val threeButtonNav = usesThreeButtonNavigation()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val sheetBottomInset =
        if (threeButtonNav) {
            navBottom + BottomSheetInset
        } else {
            BottomSheetInset
        }
    val imeInsets = WindowInsets.ime
    val shownKeyboard = shown.keyboard

    // Hardware Escape closes the sheet like back. The sheet takes key focus so Escape reaches
    // it, except when it keeps the keyboard: then a field (in the sheet or behind it) holds focus.
    val keyFocus = remember { FocusRequester() }
    LaunchedEffect(sheetVisible, shownKeyboard) {
        if (sheetVisible && shownKeyboard == OpenCodeBottomSheetKeyboard.Dismiss) {
            runCatching { keyFocus.requestFocus() }
        }
    }

    BackHandler(enabled = sheetVisible, onBack = requestDismiss)
    BottomSheetWindowOverlay(paneTitle = shown.title) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                    .semantics { dialog() }
                    .onPreviewKeyEvent { event ->
                        if (event.key != Key.Escape) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyUp) requestDismiss()
                        true
                    },
        ) {
            // The scrim sits behind the sheet as a sibling, so taps on the sheet never reach it.
            // It is hidden from accessibility: Close, back, and escape dismiss for assistive input.
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha))
                        .clearAndSetSemantics {}
                        .pointerInput(Unit) { detectTapGestures { requestDismiss() } },
            )
            val usesFloatingCard = maxWidth >= OpenCodeLayout.CompactMaxWidth
            val sheetCornerRadius =
                if (usesFloatingCard) {
                    BottomSheetCornerRadiusTop
                } else {
                    displayCornerRadius
                }
            AnimatedVisibility(
                visible = sheetVisible,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .then(
                            when (shownKeyboard) {
                                // Lift by the raw keyboard height so the sheet rests one inset above it.
                                OpenCodeBottomSheetKeyboard.Expand ->
                                    Modifier.bottomSheetKeyboardLift {
                                        openCodeBottomSheetKeyboardLift(
                                            imeHeight = imeInsets.getBottom(this).toDp(),
                                            navigationBarHeight = navBottom,
                                            threeButtonNavigation = threeButtonNav,
                                        )
                                    }
                                OpenCodeBottomSheetKeyboard.Keep -> Modifier.imePadding()
                                OpenCodeBottomSheetKeyboard.Dismiss -> Modifier
                            },
                        ),
                enter =
                    slideInVertically(
                        animationSpec = tween(BottomSheetEnterDurationMillis, easing = BottomSheetEnterEasing),
                        initialOffsetY = { it },
                    ) + fadeIn(animationSpec = tween(BottomSheetEnterDurationMillis, easing = BottomSheetEnterEasing)),
                exit =
                    slideOutVertically(
                        animationSpec = tween(BottomSheetExitDurationMillis, easing = BottomSheetExitEasing),
                        targetOffsetY = { it },
                    ) + fadeOut(animationSpec = tween(BottomSheetExitDurationMillis, easing = BottomSheetExitEasing)),
            ) {
                Surface(
                    modifier =
                        Modifier
                            .widthIn(max = if (usesFloatingCard) OpenCodeLayout.SheetMaxWidth else Dp.Unspecified)
                            .fillMaxWidth()
                            .padding(start = BottomSheetInset, end = BottomSheetInset, bottom = sheetBottomInset)
                            .then(modifier)
                            .offset { IntOffset(x = 0, y = dragOffsetPx.roundToInt()) }
                            .focusRequester(keyFocus)
                            .focusTarget(),
                    shape =
                        RoundedCornerShape(
                            topStart = BottomSheetCornerRadiusTop,
                            topEnd = BottomSheetCornerRadiusTop,
                            bottomEnd = sheetCornerRadius,
                            bottomStart = sheetCornerRadius,
                        ),
                    color = openCodeColor("surface-base"),
                    contentColor = openCodeColor("text-strong"),
                ) {
                    BottomSheetContent(
                        title = shown.title,
                        titleContent = shown.titleContent,
                        headerLeadingAction = shown.headerLeadingAction,
                        headerTrailingAction = shown.headerTrailingAction,
                        height = shown.height,
                        keyboard = shownKeyboard,
                        scrollTargetKey = shown.scrollTargetKey,
                        threeButtonNavigation = threeButtonNav,
                        windowHeight = maxHeight,
                        onDismissRequest = requestDismiss,
                        headerDragModifier =
                            Modifier.draggable(
                                orientation = Orientation.Vertical,
                                state = dragState,
                                enabled = sheetVisible,
                                onDragStopped = { velocity ->
                                    if (
                                        openCodeBottomSheetDragDismisses(
                                            offset = dragOffsetPx,
                                            velocity = velocity,
                                            distanceThreshold = dragDismissThresholdPx,
                                            velocityThreshold = dragDismissVelocityPx,
                                        )
                                    ) {
                                        // Exit starts from the dragged offset once the caller hides the sheet.
                                        requestDismiss()
                                    } else {
                                        animate(dragOffsetPx, 0f, animationSpec = BottomSheetDragSettleSpec) { value, _ ->
                                            dragOffsetPx = value
                                        }
                                    }
                                },
                            ),
                        pinnedTop = shown.pinnedTop,
                        pinnedBottom = shown.pinnedBottom,
                        content = shown.content,
                    )
                }
            }
        }
    }
}

private val BottomSheetDragSettleSpec = spring<Float>(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)

/** Whether a released header drag dismisses: past the distance, or flung down fast enough. */
internal fun openCodeBottomSheetDragDismisses(
    offset: Float,
    velocity: Float,
    distanceThreshold: Float,
    velocityThreshold: Float,
): Boolean = offset >= distanceThreshold || velocity >= velocityThreshold

/** Slots of an [OpenCodeBottomSheet] as last seen while it was visible. */
private class BottomSheetShownSlots {
    var isSet = false
        private set
    var title: String = ""
        private set
    var headerLeadingAction: OpenCodeBottomSheetHeaderAction? = null
        private set
    var headerTrailingAction: OpenCodeBottomSheetHeaderTextAction? = null
        private set
    var height: OpenCodeBottomSheetHeight = OpenCodeBottomSheetHeight.FitContent
        private set
    var keyboard: OpenCodeBottomSheetKeyboard = OpenCodeBottomSheetKeyboard.Dismiss
        private set
    var scrollTargetKey: Any? = null
        private set
    var pinnedTop: @Composable ColumnScope.() -> Unit = {}
        private set
    var pinnedBottom: (@Composable ColumnScope.() -> Unit)? = null
        private set
    var content: @Composable ColumnScope.() -> Unit = {}
        private set
    var titleContent: (@Composable () -> Unit)? = null
        private set

    fun update(
        title: String,
        headerLeadingAction: OpenCodeBottomSheetHeaderAction?,
        headerTrailingAction: OpenCodeBottomSheetHeaderTextAction?,
        height: OpenCodeBottomSheetHeight,
        keyboard: OpenCodeBottomSheetKeyboard,
        scrollTargetKey: Any?,
        pinnedTop: @Composable ColumnScope.() -> Unit,
        pinnedBottom: (@Composable ColumnScope.() -> Unit)?,
        content: @Composable ColumnScope.() -> Unit,
        titleContent: (@Composable () -> Unit)? = null,
    ) {
        isSet = true
        this.titleContent = titleContent
        this.title = title
        this.headerLeadingAction = headerLeadingAction
        this.headerTrailingAction = headerTrailingAction
        this.height = height
        this.keyboard = keyboard
        this.scrollTargetKey = scrollTargetKey
        this.pinnedTop = pinnedTop
        this.pinnedBottom = pinnedBottom
        this.content = content
    }
}

/**
 * Standard horizontal inset for free-form sheet content (text, fields, filters) in the body or
 * [OpenCodeBottomSheet]'s pinned top, matching the header, entries, and footer. Entries and list
 * rows lay out their own inset and do not take it.
 */
fun Modifier.openCodeBottomSheetContentInset(): Modifier = padding(horizontal = OpenCodeLayout.SheetContentHorizontalInset)

/**
 * Marks this row as the one [OpenCodeBottomSheet]'s `scrollTargetKey` scrolls to. The sheet
 * centers it once when the body becomes scrollable, and again when the key changes.
 */
fun Modifier.openCodeBottomSheetScrollTarget(key: Any): Modifier =
    composed {
        val registry = LocalBottomSheetScrollTargets.current
        if (registry == null) {
            this
        } else {
            onGloballyPositioned { coordinates -> registry.onTargetPositioned(key, coordinates) }
        }
    }

private val LocalBottomSheetScrollTargets = compositionLocalOf<BottomSheetScrollTargets?> { null }

/** Scrolls a sheet body so the row for [targetKey] is centered, once per key. */
private class BottomSheetScrollTargets(
    private val scrollState: ScrollState,
    private val scope: CoroutineScope,
) {
    var targetKey: Any? = null
    var scrollable: Boolean = false
    var bodyCoordinates: LayoutCoordinates? = null
    var viewportHeightPx: Int = 0
    private var scrolledKey: Any? = null

    fun onTargetPositioned(
        key: Any,
        coordinates: LayoutCoordinates,
    ) {
        val body = bodyCoordinates ?: return
        if (key != targetKey || key == scrolledKey || !scrollable) return
        if (!body.isAttached || !coordinates.isAttached || viewportHeightPx <= 0) return
        val top = body.localPositionOf(coordinates, Offset.Zero).y
        val target = openCodeBottomSheetCenteredScroll(top, coordinates.size.height.toFloat(), viewportHeightPx.toFloat())
        scrolledKey = key
        scope.launch { scrollState.scrollTo(target.roundToInt().coerceIn(0, scrollState.maxValue)) }
    }
}

/** Scroll offset that centers a row starting at [rowTop] with [rowHeight] in [viewportHeight]. */
internal fun openCodeBottomSheetCenteredScroll(
    rowTop: Float,
    rowHeight: Float,
    viewportHeight: Float,
): Float = (rowTop + rowHeight / 2f - viewportHeight / 2f).coerceAtLeast(0f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BottomSheetContent(
    title: String,
    titleContent: (@Composable () -> Unit)?,
    headerLeadingAction: OpenCodeBottomSheetHeaderAction?,
    headerTrailingAction: OpenCodeBottomSheetHeaderTextAction?,
    height: OpenCodeBottomSheetHeight,
    keyboard: OpenCodeBottomSheetKeyboard,
    scrollTargetKey: Any?,
    threeButtonNavigation: Boolean,
    windowHeight: Dp,
    onDismissRequest: () -> Unit,
    headerDragModifier: Modifier,
    pinnedTop: @Composable ColumnScope.() -> Unit,
    pinnedBottom: (@Composable ColumnScope.() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val maxSheetHeight = openCodeBottomSheetHalfHeight(windowHeight)
    var contentHeight by remember { mutableStateOf(0.dp) }
    var headerHeight by remember { mutableStateOf(0.dp) }
    var pinnedTopHeight by remember { mutableStateOf(0.dp) }
    var measuredPinnedBottomHeight by remember { mutableStateOf(0.dp) }
    val pinnedBottomHeight = if (pinnedBottom == null) 0.dp else measuredPinnedBottomHeight
    var committedToScrollable by remember { mutableStateOf(height == OpenCodeBottomSheetHeight.Half) }
    var previousHeight by remember { mutableStateOf(height) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val scrollTargets = remember(scrollState) { BottomSheetScrollTargets(scrollState, scope) }

    if (height != previousHeight) {
        previousHeight = height
        // Half locks immediately so the first frame does not expand unconstrained under
        // Compact; FitContent measures again from the live body height.
        committedToScrollable = height == OpenCodeBottomSheetHeight.Half
    }

    val layoutMode =
        remember(height, committedToScrollable, contentHeight, headerHeight, pinnedTopHeight, pinnedBottomHeight) {
            openCodeBottomSheetLayoutMode(
                height = height,
                committedToScrollable = committedToScrollable,
                headerHeight = headerHeight,
                pinnedTopHeight = pinnedTopHeight,
                contentHeight = contentHeight,
                pinnedBottomHeight = pinnedBottomHeight,
            )
        }

    LaunchedEffect(layoutMode) {
        if (layoutMode == BottomSheetLayoutMode.Scrollable) {
            committedToScrollable = true
        }
    }

    scrollTargets.targetKey = scrollTargetKey
    scrollTargets.scrollable = layoutMode == BottomSheetLayoutMode.Scrollable

    val resolvedHeaderHeight = openCodeBottomSheetResolvedHeaderHeight(headerHeight)
    val scrollBodyMaxHeight =
        (maxSheetHeight - resolvedHeaderHeight - pinnedTopHeight - BottomSheetContentBottomPadding)
            .coerceAtLeast(BottomSheetEntryMinHeight)

    val navigationBarsPaddingModifier = if (threeButtonNavigation) Modifier else Modifier.navigationBarsPadding()

    // A measuring sheet has no resting height yet, so it does not expand until it settles.
    val restingSheetHeight =
        when (layoutMode) {
            BottomSheetLayoutMode.Measuring -> null
            BottomSheetLayoutMode.Compact ->
                openCodeBottomSheetMeasuredHeight(headerHeight, pinnedTopHeight, contentHeight, pinnedBottomHeight)
            BottomSheetLayoutMode.Scrollable -> maxSheetHeight
        }
    val expandsWithKeyboard = keyboard == OpenCodeBottomSheetKeyboard.Expand && restingSheetHeight != null
    val imeInsets = WindowInsets.ime
    val imeSourceInsets = WindowInsets.imeAnimationSource
    val imeTargetInsets = WindowInsets.imeAnimationTarget
    val topInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)

    // Insets are read only from layout, so keyboard frames relayout the sheet without
    // recomposing its content, and nothing measured here feeds back into the insets.
    val keyboardSheetHeight: Density.() -> Dp = {
        val imeBottom = imeInsets.getBottom(this)
        val settledImeBottom =
            maxOf(imeBottom, imeSourceInsets.getBottom(this), imeTargetInsets.getBottom(this))
        openCodeBottomSheetKeyboardHeight(
            restingHeight = restingSheetHeight ?: maxSheetHeight,
            availableHeight =
                windowHeight - topInsets.getTop(this).toDp() - BottomSheetKeyboardChromeHeight,
            imeHeight = imeBottom.toDp(),
            settledImeHeight = settledImeBottom.toDp(),
            keyboard = keyboard,
        )
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = BottomSheetContentBottomPadding)
                .then(
                    when (layoutMode) {
                        // Cap Compact so a short body that just grew cannot stretch the sheet
                        // past the compact limit for the frame before it turns scrollable.
                        BottomSheetLayoutMode.Compact ->
                            if (expandsWithKeyboard) {
                                Modifier.bottomSheetFixedHeight(keyboardSheetHeight)
                            } else {
                                Modifier
                                    .heightIn(max = BottomSheetCompactMaxHeight)
                                    .wrapContentHeight()
                            }
                        BottomSheetLayoutMode.Measuring -> Modifier.heightIn(max = maxSheetHeight)
                        BottomSheetLayoutMode.Scrollable ->
                            if (expandsWithKeyboard) {
                                Modifier.bottomSheetFixedHeight(keyboardSheetHeight)
                            } else {
                                Modifier.height(maxSheetHeight)
                            }
                    },
                ),
    ) {
        BottomSheetHeader(
            title = title,
            titleContent = titleContent,
            leadingAction = headerLeadingAction,
            trailingAction = headerTrailingAction,
            onDismissRequest = onDismissRequest,
            modifier =
                headerDragModifier.onSizeChanged {
                    headerHeight = with(density) { it.height.toDp() }
                },
        )

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged {
                        pinnedTopHeight = with(density) { it.height.toDp() }
                    },
        ) {
            pinnedTop()
        }

        val bodyHeightModifier =
            if (pinnedBottom != null) {
                // The footer sits under the body: a fixed-height sheet gives the body the
                // rest of its height, and a sizing sheet lets it shrink to keep the footer.
                Modifier.weight(
                    1f,
                    fill = layoutMode == BottomSheetLayoutMode.Scrollable || expandsWithKeyboard,
                )
            } else {
                when (layoutMode) {
                    BottomSheetLayoutMode.Compact -> Modifier.weight(1f, fill = false)
                    BottomSheetLayoutMode.Measuring,
                    BottomSheetLayoutMode.Scrollable,
                    -> {
                        val scrollBodyHeightModifier =
                            if (expandsWithKeyboard) {
                                Modifier.bottomSheetMaxHeight {
                                    keyboardSheetHeight() - resolvedHeaderHeight - pinnedTopHeight -
                                        BottomSheetContentBottomPadding
                                }
                            } else {
                                Modifier.heightIn(max = scrollBodyMaxHeight)
                            }
                        scrollBodyHeightModifier.then(navigationBarsPaddingModifier)
                    }
                }
            }

        // The body is composed once in every mode. It always sits in a scroll container, which
        // measures it at its natural height, so that height picks the layout mode directly.
        CompositionLocalProvider(LocalBottomSheetScrollTargets provides scrollTargets) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(bodyHeightModifier)
                        .onSizeChanged { scrollTargets.viewportHeightPx = it.height }
                        .verticalScroll(scrollState, enabled = layoutMode != BottomSheetLayoutMode.Measuring),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { scrollTargets.bodyCoordinates = it }
                            .onSizeChanged { size ->
                                val measured = with(density) { size.height.toDp() }
                                if (contentHeight != measured) {
                                    contentHeight = measured
                                }
                            },
                ) {
                    content()
                }
            }
        }

        if (pinnedBottom != null) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged {
                            measuredPinnedBottomHeight = with(density) { it.height.toDp() }
                        }.padding(
                            start = BottomSheetFooterHorizontalPadding,
                            end = BottomSheetFooterHorizontalPadding,
                            top = BottomSheetFooterTopPadding,
                        ).then(navigationBarsPaddingModifier),
            ) {
                pinnedBottom()
            }
        }
    }
}

@Composable
private fun BottomSheetHeader(
    title: String,
    titleContent: (@Composable () -> Unit)?,
    leadingAction: OpenCodeBottomSheetHeaderAction?,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    trailingAction: OpenCodeBottomSheetHeaderTextAction? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    start = BottomSheetHeaderHorizontalPadding,
                    end = BottomSheetHeaderHorizontalPadding,
                    top = BottomSheetHeaderTopPadding,
                    bottom = BottomSheetHeaderBottomPadding,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leadingAction?.let { action ->
            OpenCodeButton(
                variant = OpenCodeButtonVariant.IconCircle(action.imageVector, action.contentDescription),
                contrast = OpenCodeButtonContrast.Low,
                onClick = action.onClick,
            )
        }
        if (titleContent != null) {
            Box(modifier = Modifier.weight(1f).semantics { heading() }) { titleContent() }
        } else {
            Text(
                text = title,
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                color = openCodeColor("text-strong"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailingAction?.let { action ->
            OpenCodeButton(
                variant = OpenCodeButtonVariant.CompactText(action.title),
                contrast = OpenCodeButtonContrast.Low,
                enabled = action.enabled,
                onClick = action.onClick,
                modifier = action.testTag?.let { Modifier.testTag(it) } ?: Modifier,
            )
        }
        OpenCodeButton(
            variant =
                OpenCodeButtonVariant.IconCircle(
                    OpenCodeIcons.Close,
                    stringResource(R.string.bottom_sheet_close),
                ),
            contrast = OpenCodeButtonContrast.Low,
            onClick = onDismissRequest,
        )
    }
}

/** Scrollable half height: half the window, less the gap above the sheet. */
internal fun openCodeBottomSheetHalfHeight(windowHeight: Dp): Dp = windowHeight * BottomSheetScrollableHeightFraction - BottomSheetInset

/** The measured header height, or an estimate before the header has laid out. */
internal fun openCodeBottomSheetResolvedHeaderHeight(headerHeight: Dp): Dp =
    if (headerHeight > 0.dp) headerHeight else BottomSheetEstimatedHeaderHeight

/** Natural sheet column height: header, pinned top, body, and footer (with its spacing). */
internal fun openCodeBottomSheetMeasuredHeight(
    headerHeight: Dp,
    pinnedTopHeight: Dp,
    contentHeight: Dp,
    pinnedBottomHeight: Dp,
): Dp = openCodeBottomSheetResolvedHeaderHeight(headerHeight) + pinnedTopHeight + contentHeight + pinnedBottomHeight

/**
 * Layout for a sheet of [height]. [OpenCodeBottomSheetHeight.Half] and a FitContent sheet that
 * already reached half height are scrollable; otherwise FitContent stays compact while the
 * measured column and bottom padding fit [BottomSheetCompactMaxHeight].
 */
internal fun openCodeBottomSheetLayoutMode(
    height: OpenCodeBottomSheetHeight,
    committedToScrollable: Boolean,
    headerHeight: Dp,
    pinnedTopHeight: Dp,
    contentHeight: Dp,
    pinnedBottomHeight: Dp,
): BottomSheetLayoutMode {
    if (height == OpenCodeBottomSheetHeight.Half || committedToScrollable) return BottomSheetLayoutMode.Scrollable
    if (contentHeight == 0.dp) return BottomSheetLayoutMode.Measuring
    val total =
        openCodeBottomSheetMeasuredHeight(headerHeight, pinnedTopHeight, contentHeight, pinnedBottomHeight) +
            BottomSheetContentBottomPadding
    return if (total > BottomSheetCompactMaxHeight) BottomSheetLayoutMode.Scrollable else BottomSheetLayoutMode.Compact
}

/**
 * Sheet column height for [keyboard]. [availableHeight] is the column space above the window
 * bottom once the top inset, the top gap, the gap above the keyboard, and the sheet's bottom
 * padding are removed. While the keyboard moves, an expanding sheet follows its progress from
 * [restingHeight] to the space left above the settled keyboard, never below [restingHeight].
 */
internal fun openCodeBottomSheetKeyboardHeight(
    restingHeight: Dp,
    availableHeight: Dp,
    imeHeight: Dp,
    settledImeHeight: Dp,
    keyboard: OpenCodeBottomSheetKeyboard,
): Dp {
    if (keyboard != OpenCodeBottomSheetKeyboard.Expand) return restingHeight
    if (imeHeight <= 0.dp || settledImeHeight <= 0.dp) return restingHeight
    val progress = (imeHeight / settledImeHeight).coerceIn(0f, 1f)
    val expandedHeight = (availableHeight - settledImeHeight).coerceAtLeast(restingHeight)
    return restingHeight + (expandedHeight - restingHeight) * progress
}

/**
 * Bottom lift for an expanding sheet so it rests [BottomSheetInset] above the keyboard. The sheet
 * already pads by the navigation bar under three-button navigation; the keyboard inset includes it.
 */
internal fun openCodeBottomSheetKeyboardLift(
    imeHeight: Dp,
    navigationBarHeight: Dp,
    threeButtonNavigation: Boolean,
): Dp = (imeHeight - if (threeButtonNavigation) navigationBarHeight else 0.dp).coerceAtLeast(0.dp)

// Top gap, gap above the keyboard, and the sheet's own bottom padding.
private val BottomSheetKeyboardChromeHeight =
    BottomSheetInset + BottomSheetInset + BottomSheetContentBottomPadding

private fun Modifier.bottomSheetKeyboardLift(lift: Density.() -> Dp): Modifier =
    layout { measurable, constraints ->
        val liftPx = lift().roundToPx().coerceAtLeast(0)
        val placeable = measurable.measure(constraints.offset(vertical = -liftPx))
        layout(placeable.width, constraints.constrainHeight(placeable.height + liftPx)) {
            placeable.place(0, 0)
        }
    }

private fun Modifier.bottomSheetFixedHeight(height: Density.() -> Dp): Modifier =
    layout { measurable, constraints ->
        val heightPx = constraints.constrainHeight(height().roundToPx().coerceAtLeast(0))
        val placeable = measurable.measure(constraints.copy(minHeight = heightPx, maxHeight = heightPx))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

private fun Modifier.bottomSheetMaxHeight(maxHeight: Density.() -> Dp): Modifier =
    layout { measurable, constraints ->
        val maxPx =
            constraints.constrainHeight(maxHeight().coerceAtLeast(BottomSheetEntryMinHeight).roundToPx())
        val placeable =
            measurable.measure(
                constraints.copy(minHeight = minOf(constraints.minHeight, maxPx), maxHeight = maxPx),
            )
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

/**
 * Three-button navigation reserves a tappable bar at the bottom; gesture navigation leaves only
 * a gesture area there, so its tappable inset is zero.
 */
@Composable
private fun usesThreeButtonNavigation(): Boolean {
    val density = LocalDensity.current
    return WindowInsets.tappableElement.getBottom(density) > 0
}

@Composable
private fun rememberBottomSheetCornerRadius(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    val displayRadius =
        remember(view) { deviceBottomCornerRadiusPx(view.context) }?.let { px ->
            with(density) { px.toDp() }
        } ?: BottomSheetDisplayCornerRadiusFallback
    return (displayRadius - BottomSheetInset).coerceAtLeast(BottomSheetMinCornerRadius)
}

private fun deviceBottomCornerRadiusPx(context: Context): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val activity = context.findActivity()
    val insets =
        activity?.windowManager?.currentWindowMetrics?.windowInsets
            ?: activity?.window?.decorView?.rootWindowInsets
            ?: return null
    val leading = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius
    val trailing = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius
    return listOfNotNull(leading, trailing).maxOrNull()?.takeIf { it > 0 }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/** Window-level surface so a sheet or full-screen cover is not clipped to a split pane. */
@Composable
internal fun BottomSheetWindowOverlay(
    paneTitle: String,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() }
    val parentComposition = rememberCompositionContext()
    val currentContent = rememberUpdatedState(content)

    if (activity == null) {
        Box(modifier = Modifier.fillMaxSize()) { content() }
        return
    }

    DisposableEffect(activity, parentComposition) {
        val overlay =
            ComposeView(activity).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setParentCompositionContext(parentComposition)
                setContent { currentContent.value() }
                elevation = 8f
                isFocusable = true
                isFocusableInTouchMode = true
                fitsSystemWindows = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                // setAccessibilityPaneTitle is API 28. ViewCompat keeps the
                // announcement on 28+ and skips the missing method on 24–27.
                ViewCompat.setAccessibilityPaneTitle(this, paneTitle)
            }
        ViewCompat.setOnApplyWindowInsetsListener(overlay) { view, insets ->
            view.setPadding(0, 0, 0, 0)
            insets
        }
        val root = activity.window.decorView as ViewGroup
        val hiddenSiblings = hideDecorSiblingsFromAccessibility(root, overlay)
        root.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.requestFocus()
        onDispose {
            restoreDecorSiblingAccessibility(hiddenSiblings)
            // Removing the focused overlay makes Android hand focus to the first
            // focusable view; Compose forwards it to the first text field (the
            // Session Chat composer) and opens the keyboard. Block that handoff.
            val descendantFocusability = root.descendantFocusability
            root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            root.removeView(overlay)
            root.descendantFocusability = descendantFocusability
        }
    }
}

private fun hideDecorSiblingsFromAccessibility(
    root: ViewGroup,
    overlay: View,
): List<Pair<View, Int>> {
    val hidden = mutableListOf<Pair<View, Int>>()
    for (index in 0 until root.childCount) {
        val child = root.getChildAt(index)
        if (child !== overlay) {
            hidden += child to child.importantForAccessibility
            child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }
    return hidden
}

private fun restoreDecorSiblingAccessibility(hiddenSiblings: List<Pair<View, Int>>) {
    hiddenSiblings.forEach { (child, importance) ->
        child.importantForAccessibility = importance
    }
}

/**
 * Tappable row in a bottom sheet. [selected] marks the current choice in a picker: it shows a
 * check in the end slot (replacing [endDecorator]) and exposes the selected state to
 * accessibility. Titles wrap to two lines at large font scales.
 */
@Composable
fun OpenCodeBottomSheetEntry(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    selected: Boolean = false,
    startDecorator: BottomSheetEntryDecorator = BottomSheetEntryDecorator.None,
    endDecorator: BottomSheetEntryDecorator = BottomSheetEntryDecorator.None,
    /** Optional second line that explains the entry, for pickers whose options need a description. */
    subtitle: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (enabled && isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium),
        label = "bottomSheetEntryPressScale",
    )
    val contentColor =
        when {
            !enabled -> openCodeColor("text-weaker")
            destructive -> openCodeColor("error")
            else -> openCodeColor("text-strong")
        }
    val iconColor =
        when {
            !enabled -> openCodeColor("text-weaker")
            destructive -> openCodeColor("error")
            else -> openCodeColor("icon-strong-base")
        }
    val entryShape = RoundedCornerShape(10.dp)
    val resolvedEndDecorator = if (selected) BottomSheetEntryDecorator.Icon(OpenCodeIcons.Check) else endDecorator

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .scale(pressScale)
                .clip(entryShape)
                .background(
                    if (enabled && isPressed) {
                        openCodeColor("surface-raised-base").copy(alpha = 0.88f)
                    } else {
                        Color.Transparent
                    },
                ).defaultMinSize(minHeight = BottomSheetEntryMinHeight)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true),
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ).semantics { this.selected = selected }
                .padding(
                    horizontal = BottomSheetEntryHorizontalPadding,
                    vertical = BottomSheetEntryVerticalPadding,
                ).then(if (enabled) Modifier else Modifier.alpha(0.7f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BottomSheetEntryDecoration(decorator = startDecorator, tint = iconColor)
        if (subtitle.isNullOrEmpty()) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                maxLines = BottomSheetEntryMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor,
                    maxLines = BottomSheetEntryMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) openCodeColor("text-base") else openCodeColor("text-weaker"),
                    maxLines = BottomSheetEntryMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        BottomSheetEntryDecoration(decorator = resolvedEndDecorator, tint = iconColor)
    }
}

@Composable
private fun BottomSheetEntryDecoration(
    decorator: BottomSheetEntryDecorator,
    tint: Color,
) {
    when (decorator) {
        BottomSheetEntryDecorator.None -> Unit
        is BottomSheetEntryDecorator.Icon ->
            Box(modifier = Modifier.size(BottomSheetEntryDecoratorSize), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = decorator.imageVector,
                    contentDescription = null,
                    modifier = Modifier.size(BottomSheetEntryIconSize),
                    tint = tint,
                )
            }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeBottomSheetScrollablePreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeBottomSheet(visible = true, title = "Model", onDismissRequest = {}) {
            repeat(24) { index ->
                OpenCodeBottomSheetEntry(
                    title = "Model ${index + 1} · Provider",
                    selected = index == 2,
                    onClick = {},
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeBottomSheetPinnedBottomPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeBottomSheet(
            visible = true,
            title = "Add project",
            onDismissRequest = {},
            height = OpenCodeBottomSheetHeight.Half,
            pinnedBottom = {
                OpenCodeButton(variant = OpenCodeButtonVariant.FullWidthText("Add project"), onClick = {})
            },
        ) {
            repeat(12) { index ->
                OpenCodeBottomSheetEntry(title = "Folder ${index + 1}", onClick = {})
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeBottomSheetLightPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeBottomSheet(visible = true, title = "Session actions", onDismissRequest = {}) {
            OpenCodeBottomSheetEntry(
                title = "New session",
                startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Add),
                onClick = {},
            )
            OpenCodeBottomSheetEntry(
                title = "Open project",
                startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Folder),
                endDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.ChevronRight),
                onClick = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeBottomSheetDarkPreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeBottomSheet(visible = true, title = "Session actions", onDismissRequest = {}) {
            OpenCodeBottomSheetEntry(title = "Rename session", onClick = {})
            OpenCodeBottomSheetEntry(
                title = "Fork session",
                endDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.ChevronRight),
                onClick = {},
            )
            OpenCodeBottomSheetEntry(
                title = "Delete session",
                destructive = true,
                startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Close),
                onClick = {},
            )
        }
    }
}
