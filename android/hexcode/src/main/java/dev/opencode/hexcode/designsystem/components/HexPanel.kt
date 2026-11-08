package dev.opencode.hexcode.designsystem.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexMotion
import dev.opencode.hexcode.designsystem.HexTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A card (`.panel`): `panel` fill, `line` border, radius 12. [translucent] lets a camera view show through a little. */
@Composable
fun HexPanel(
    modifier: Modifier = Modifier,
    translucent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = HexTheme.colors
    val shape = RoundedCornerShape(HexTheme.radius.l)
    Column(
        modifier =
            modifier
                .clip(shape)
                .background(if (translucent) colors.panel.copy(alpha = 0.92f) else colors.panel)
                .border(1.dp, colors.line, shape),
        content = content,
    )
}

/** A button at one end of a [HexSheet]'s head. */
data class HexSheetAction(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * A [HexSheet]'s **request** head (`.rq-head`), for an approval or a question: the title on the left, an optional
 * [count] ("1 of 2"), and a round Hide button ([hideLabel], `chevD`) that calls [onHide].
 */
data class HexSheetRequest(
    val hideLabel: String,
    val onHide: () -> Unit,
    val count: String? = null,
)

/** How tall a [HexSheet] is. */
enum class HexSheetSize {
    /** As tall as its content, up to the screen. */
    Fit,

    /**
     * Half the screen whatever has loaded, as iOS's `.medium` detent, and dragged by its head (or its content, at the
     * top) up to full height, as `.large`, or down to close. It never sizes itself to its content, so it doesn't jump.
     */
    Medium,
}

/**
 * A bottom sheet (`.sheet`) over [scrim]-dimmed content: a floating `panel` card 8 dp in from the screen's sides and
 * foot (above three-button navigation's bar), radius 24 at the top and the phone's own screen corners at the foot
 * ([sheetBottomRadius]); the grab bar, and a head with the [title] and one action each side, or with a [request] the
 * request head. [amberEdge] marks an approval (`.perm-sh`): a 1 pt `amber` 60 % top edge with an `amber` 25 % line
 * above it. The content scrolls when it's taller than the screen; a [footer] (the sheet's buttons) stays under it.
 * [size] picks fitting the content or a fixed half-screen height; [loading] puts a small spinner in the middle of the
 * body in place of the content. Put it last in a full-screen `Box` (padded for the system bars or not: the scrim
 * still covers them); back and a tap on the scrim call [onDismiss].
 */
@Composable
fun BoxScope.HexSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    leading: HexSheetAction? = null,
    trailing: HexSheetAction? = null,
    request: HexSheetRequest? = null,
    amberEdge: Boolean = false,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    /** A head of the sheet's own in place of the centred title ([HexReader]'s). */
    head: (@Composable () -> Unit)? = null,
    size: HexSheetSize = HexSheetSize.Fit,
    loading: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = HexTheme.colors
    val motion = HexTheme.motion
    val reduced = HexMotion.reduced
    BackHandler(enabled = visible, onBack = onDismiss)
    // The screen's height, for a Medium sheet's detents, and how far the box's edges are from the screen's when the
    // screen pads it for the system bars: the scrim reaches over the bars, and the card floats from the screen's foot.
    var screen by remember { mutableIntStateOf(0) }
    var bleed by remember { mutableStateOf(SheetBleed()) }
    val density = LocalDensity.current
    val statusBar = WindowInsets.statusBars.getTop(density)
    val navigationBar = WindowInsets.navigationBars.getBottom(density)
    Box(
        Modifier.matchParentSize().onGloballyPositioned {
            screen = it.size.height
            bleed = sheetBleed(it, statusBar, navigationBar)
        },
    )
    AnimatedVisibility(visible, enter = fadeIn(motion.fade()), exit = fadeOut(motion.fade())) {
        Box(
            Modifier
                .bleed(bleed)
                .fillMaxSize()
                .background(colors.scrim)
                .semantics { hideFromAccessibility() }
                .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
        )
    }
    // With Reduce Motion it fades instead of sliding up (app-plan.md, Motion and Reduce Motion).
    AnimatedVisibility(
        visible,
        enter = if (reduced) fadeIn(motion.fade()) else slideInVertically(motion.sheet()) { it },
        exit = if (reduced) fadeOut(motion.fade()) else slideOutVertically(motion.sheet()) { it },
        modifier = Modifier.align(Alignment.BottomCenter).offset { IntOffset(0, bleed.bottom) },
    ) {
        val detents = if (size == HexSheetSize.Medium && screen > 0) rememberSheetDetents(screen, onDismiss) else null
        val radius = SheetTopRadius
        val bottomRadius = rememberSheetBottomRadius()
        val shape = RoundedCornerShape(radius, radius, bottomRadius, bottomRadius)
        val amber = colors.amber
        // The floating card: 8 dp in from the sides and the screen's foot, or from the top of three-button
        // navigation's bar; gesture navigation's handle sits over the card's foot, as OpenCode Mobile's sheet. A Medium
        // sheet's height counts from its box's foot, so the card's top rests where the detent says.
        val foot = with(density) { if (usesThreeButtonNavigation() && bleed.atFoot) navigationBar.toDp() else 0.dp }
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (detents != null) Modifier.height(with(density) { (detents.height + bleed.bottom).toDp() }) else Modifier)
                .padding(start = SheetInset, end = SheetInset, bottom = SheetInset + foot),
        ) {
            Column(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .then(if (detents != null) Modifier.fillMaxHeight() else Modifier)
                        .padding(top = if (amberEdge) 2.dp else 0.dp)
                        .drawWithContent {
                            drawContent()
                            if (amberEdge) amberTop(amber, radius.toPx())
                        }.clip(shape)
                        .background(colors.panel)
                        .border(1.dp, colors.line2, shape)
                        // Swallows taps on the sheet so they don't reach the scrim, without a click for TalkBack: the
                        // sheet's own buttons stay separate items.
                        .pointerInput(Unit) { detectTapGestures { } }
                        // TalkBack hears the sheet open by its title.
                        .semantics { paneTitle = title }
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 18.dp),
            ) {
                // The grab bar and the head drag a Medium sheet.
                Column(if (detents != null) detents.dragHandle else Modifier) {
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = if (request != null) 2.dp else 10.dp)
                            .size(width = 36.dp, height = 4.dp)
                            .background(colors.line2, CircleShape),
                    )
                    SheetHead(title, leading, trailing, request, head)
                }
                if (loading) {
                    Box(
                        Modifier
                            .weight(1f, fill = detents != null)
                            .fillMaxWidth()
                            .heightIn(min = 96.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        HexSpinner(tint = colors.text3)
                    }
                } else {
                    Column(
                        Modifier
                            .weight(1f, fill = detents != null)
                            .fillMaxWidth()
                            .then(if (detents != null) Modifier.nestedScroll(detents.scroll) else Modifier)
                            .verticalScroll(rememberScrollState())
                            .padding(top = if (request != null) 4.dp else 12.dp),
                        content = content,
                    )
                }
                if (footer != null) Column(Modifier.padding(top = 16.dp), content = footer)
            }
        }
    }
}

/**
 * How far a [HexSheet]'s box stops short of the screen's top and foot, in px, when that's the system bars' padding
 * (the screen pads its content for them); 0 when the box is elsewhere, as in the gallery. [atFoot]: the box reaches
 * the screen's foot, or the top of its navigation bar.
 */
private data class SheetBleed(
    val top: Int = 0,
    val bottom: Int = 0,
    val atFoot: Boolean = true,
)

private fun sheetBleed(
    box: LayoutCoordinates,
    statusBar: Int,
    navigationBar: Int,
): SheetBleed {
    val top = box.positionInRoot().y.roundToInt()
    val bottom = box.findRootCoordinates().size.height - (top + box.size.height)
    val atFoot = bottom in 0..navigationBar
    return SheetBleed(
        top = if (top in 0..statusBar) top else 0,
        bottom = if (atFoot) bottom else 0,
        atFoot = atFoot,
    )
}

/** Lays the scrim out over [bleed] as well as its box, so it dims the system bars too. */
private fun Modifier.bleed(bleed: SheetBleed): Modifier =
    layout { measurable, constraints ->
        val height = constraints.maxHeight + bleed.top + bleed.bottom
        val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, -bleed.top) }
    }

/** How far a [HexSheet] sits in from the screen's sides and foot. */
private val SheetInset = 8.dp

/** A [HexSheet]'s top corners. */
private val SheetTopRadius = 24.dp

/** A phone's screen corner when it doesn't report one, as OpenCode Mobile's sheet. */
private val DisplayCornerRadiusFallback = 55.dp

/** The smallest a [HexSheet]'s foot corners get, on a phone with square or slightly rounded corners. */
private val SheetMinBottomRadius = 16.dp

/**
 * A [HexSheet]'s foot corners, concentric with the phone's screen corners: the [display] corner radius (or 55 dp when
 * the phone reports none) less the 8 dp inset, at least 16 dp.
 */
internal fun sheetBottomRadius(display: Dp?): Dp =
    ((display ?: DisplayCornerRadiusFallback) - SheetInset).coerceAtLeast(SheetMinBottomRadius)

@Composable
private fun rememberSheetBottomRadius(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    return remember(view, density) {
        sheetBottomRadius(displayBottomCornerRadiusPx(view)?.let { with(density) { it.toDp() } })
    }
}

/** The screen's bottom corner radius in px, from API 31's rounded corners; null before then or when it reports none. */
private fun displayBottomCornerRadiusPx(view: View): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val insets =
        view.context
            .findActivity()
            ?.windowManager
            ?.currentWindowMetrics
            ?.windowInsets ?: view.rootWindowInsets ?: return null
    val left = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius
    val right = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius
    return listOfNotNull(left, right).maxOrNull()?.takeIf { it > 0 }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/** Three-button navigation reserves a tappable bar at the foot; gesture navigation's tappable inset is zero. */
@Composable
private fun usesThreeButtonNavigation(): Boolean = WindowInsets.tappableElement.getBottom(LocalDensity.current) > 0

/** The sheet's head: [head], the [request] head, or the centred [title] with an action each side. */
@Composable
private fun SheetHead(
    title: String,
    leading: HexSheetAction?,
    trailing: HexSheetAction?,
    request: HexSheetRequest?,
    head: (@Composable () -> Unit)?,
) {
    val colors = HexTheme.colors
    if (head != null) {
        head()
    } else if (request != null) {
        RequestHead(title, request)
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(32.dp),
        ) {
            Box(Modifier.widthIn(min = 70.dp)) {
                leading?.let { HexButton(onClick = it.onClick, text = it.label) }
            }
            Text(
                title,
                style = HexTheme.type.bodyStrong,
                color = colors.text1,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Box(Modifier.widthIn(min = 70.dp), contentAlignment = Alignment.CenterEnd) {
                trailing?.let { HexButton(onClick = it.onClick, text = it.label) }
            }
        }
    }
}

/**
 * A Medium sheet's height in px, and what drags it: [dragHandle] for the grab bar and head, [scroll] for the content,
 * which grows the sheet before it scrolls and shrinks it once scrolled back to the top.
 */
private class SheetDetents(
    val height: Float,
    val dragHandle: Modifier,
    val scroll: NestedScrollConnection,
)

/**
 * Half of [screen] (iOS's `.medium`), or up to the screen less the status bar and 10 dp (`.large`). Let go, it settles
 * on the nearer one, or the one a quick flick points at; below 60 % of half, or flicked down from half, it closes. With
 * Reduce Motion it settles at once.
 */
@Composable
private fun rememberSheetDetents(
    screen: Int,
    onDismiss: () -> Unit,
): SheetDetents {
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.getTop(density) + with(density) { 10.dp.toPx() }
    val medium = screen / 2f
    val large = (screen - top).coerceAtLeast(medium)
    var height by remember { mutableFloatStateOf(medium) }
    val scope = rememberCoroutineScope()
    val spec = if (HexMotion.reduced) snap() else HexTheme.motion.sheet<Float>()
    val dismiss by rememberUpdatedState(onDismiss)
    val settle: (Float) -> Unit = { velocity ->
        val flick = with(density) { 900.dp.toPx() }
        val target =
            when {
                velocity > flick -> if (height > medium + 1f) medium else null
                velocity < -flick -> large
                height < medium * 0.6f -> null
                height < (medium + large) / 2f -> medium
                else -> large
            }
        if (target == null) {
            dismiss()
        } else {
            scope.launch { animate(height, target, animationSpec = spec) { value, _ -> height = value } }
        }
    }
    val grow = { delta: Float ->
        val before = height
        height = (height - delta).coerceIn(0f, large)
        before - height
    }
    val drag = rememberDraggableState { grow(it) }
    val scroll =
        remember(large) {
            object : NestedScrollConnection {
                // Pushed up: the sheet grows to full height before its content scrolls.
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset =
                    if (source == NestedScrollSource.UserInput && available.y < 0 && height < large) {
                        Offset(0f, -grow(available.y))
                    } else {
                        Offset.Zero
                    }

                // Pulled down with the content at its top: the sheet shrinks.
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset =
                    if (source == NestedScrollSource.UserInput && available.y > 0) {
                        Offset(0f, -grow(available.y))
                    } else {
                        Offset.Zero
                    }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (height == medium || height == large) return Velocity.Zero
                    settle(available.y)
                    return available
                }
            }
        }
    val handle =
        Modifier.draggable(
            drag,
            Orientation.Vertical,
            onDragStopped = { velocity -> settle(velocity) },
        )
    return SheetDetents(height, handle, scroll)
}

/** The request head (`.rq-head`): the title (`headline`), the count (`mono`, `text3`), and the 36 pt round Hide. */
@Composable
private fun RequestHead(
    title: String,
    request: HexSheetRequest,
) {
    val colors = HexTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = HexTheme.size.touch),
    ) {
        Text(
            title,
            style = HexTheme.type.headline,
            color = colors.text1,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        request.count?.let { Text(it, style = HexTheme.type.mono, color = colors.text3, maxLines = 1) }
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.tint(5f))
                    .clickable(role = Role.Button, onClick = request.onHide)
                    .semantics { contentDescription = request.hideLabel },
        ) {
            Icon(HexIcons.chevD, null, tint = colors.text2, modifier = Modifier.size(HexTheme.size.icon))
        }
    }
}

/** An approval sheet's top edge: `amber` 60 % along the rounded top, and an `amber` 25 % line 1 pt above it. */
private fun DrawScope.amberTop(
    amber: Color,
    radius: Float,
) {
    val stroke = 1.dp.toPx()

    fun edge(inset: Float): Path =
        Path().apply {
            val r = radius - inset
            moveTo(inset, radius)
            arcTo(Rect(inset, inset, inset + 2 * r, inset + 2 * r), 180f, 90f, false)
            lineTo(size.width - radius, inset)
            arcTo(Rect(size.width - inset - 2 * r, inset, size.width - inset, inset + 2 * r), 270f, 90f, false)
        }
    drawPath(edge(-stroke / 2f), amber.copy(alpha = 0.25f), style = Stroke(stroke))
    drawPath(edge(stroke / 2f), amber.copy(alpha = 0.6f), style = Stroke(stroke))
}
