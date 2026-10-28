package dev.opencode.mobile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.OpenCodeRadius
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

private val ReorderableRowCornerRadius = OpenCodeRadius.small
private val ReorderableRowLiftedElevation = 8.dp
private val ReorderableRowFallbackHeight = 60.dp
private const val ReorderableRowPressedScale = 0.965f
private const val ReorderableRowLiftedScale = 1.03f

/** What a press turned out to be, decided on the first movement that settles it. */
private enum class RowGesturePhase {
    Undecided,
    Tap,
    Swipe,
    Lift,
    Cancelled,
}

/**
 * Stacked rows that reorder with long-press and drag, and expose one secondary
 * row action behind a horizontal swipe.
 *
 * The two gestures own separate axes, so neither can take the other's input.
 * Tap selects a row. Long-press lifts a movable row; dragging vertically shifts
 * the neighbors live and release commits [onMove]. Swiping a row left reveals
 * [contextActionLabel]; tapping that fires [onContextAction]. A long-press never
 * fires the context action, and a swipe never reorders.
 *
 * Only one row stays open at a time, and the next press anywhere closes it.
 * Rows that fail [canMove] never lift or get crossed; rows that fail
 * [supportsContextAction] never reveal. Reordering is inactive until at least
 * two rows can move or [onMove] is null.
 *
 * A row may place [OpenCodeReorderableRowScope.DragHandle]: touching it lifts the
 * row straight away, without the long-press, for an explicit reorder mode.
 */
@Composable
fun <T> OpenCodeReorderableList(
    items: List<T>,
    key: (T) -> Any,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    canMove: (T) -> Boolean = { true },
    onMove: ((from: Int, to: Int) -> Unit)? = null,
    onContextAction: ((T) -> Unit)? = null,
    supportsContextAction: (T) -> Boolean = { true },
    contextActionLabel: String = "Remove",
    row: @Composable OpenCodeReorderableRowScope.(T) -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val fallbackHeightPx = with(density) { ReorderableRowFallbackHeight.toPx() }
    val revealWidthDp = ReorderableListMath.SWIPE_REVEAL_WIDTH_DP.dp
    val revealWidthPx = with(density) { revealWidthDp.toPx() }
    val revealOpenThresholdPx = with(density) { ReorderableListMath.SWIPE_OPEN_THRESHOLD_DP.dp.toPx() }

    val currentItems by rememberUpdatedState(items)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentCanMove by rememberUpdatedState(canMove)

    val rowHeights = remember { mutableStateMapOf<Any, Float>() }
    var pressedKey by remember { mutableStateOf<Any?>(null) }
    var draggedKey by remember { mutableStateOf<Any?>(null) }
    var openKey by remember { mutableStateOf<Any?>(null) }
    var translation by remember { mutableFloatStateOf(0f) }
    var announcedTarget by remember { mutableIntStateOf(-1) }
    var settleKey by remember { mutableStateOf<Any?>(null) }
    var settleFrom by remember { mutableFloatStateOf(0f) }

    val reorderEnabled = onMove != null && items.count(canMove) >= 2
    val heights = items.map { rowHeights[key(it)] ?: fallbackHeightPx }
    val canMoveAt: (Int) -> Boolean = { index -> canMove(items[index]) }
    val draggedIndex = draggedKey?.let { dragged -> items.indexOfFirst { key(it) == dragged } } ?: -1
    val clampedTranslation =
        if (draggedIndex >= 0) {
            ReorderableListMath.clampedTranslation(translation, draggedIndex, heights, canMoveAt)
        } else {
            0f
        }
    val targetIndex =
        if (draggedIndex >= 0) {
            ReorderableListMath.targetIndex(draggedIndex, clampedTranslation, heights, canMoveAt)
        } else {
            -1
        }

    Column(modifier = modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            val itemKey = key(item)
            key(itemKey) {
                val isDragged = draggedKey == itemKey
                val isPressed = pressedKey == itemKey
                val movable = reorderEnabled && canMove(item)
                val canReveal = onContextAction != null && supportsContextAction(item)
                // Live reveal offset in px, 0 = closed. The swipe writes it directly
                // while the finger is down; once released, [openKey] decides the
                // target and the effect below settles there. The gesture cannot run
                // the animation itself: AwaitPointerEventScope restricts suspension
                // to its own members, so `animate` is unreachable from inside it.
                val revealState = remember { mutableFloatStateOf(0f) }
                var revealPx by revealState
                // Gates the strip out of the tree (and out of the semantics a
                // screen reader walks) while the row is shut. Derived so the swipe
                // only recomposes on the open/shut flip, not on every frame.
                val revealing by remember { derivedStateOf { revealState.floatValue > 0f } }
                var swiping by remember { mutableStateOf(false) }
                val revealTarget = if (openKey == itemKey) revealWidthPx else 0f
                LaunchedEffect(revealTarget, swiping) {
                    if (!swiping && revealPx != revealTarget) {
                        animate(
                            revealPx,
                            revealTarget,
                            animationSpec = spring(dampingRatio = 0.85f),
                        ) { value, _ -> revealPx = value }
                    }
                }
                val displacementTarget =
                    when {
                        isDragged || draggedIndex < 0 -> 0f
                        else -> ReorderableListMath.displacement(index, draggedIndex, targetIndex, heights[draggedIndex])
                    }
                val displacement = remember { Animatable(0f) }
                LaunchedEffect(displacementTarget, draggedIndex >= 0) {
                    if (draggedIndex >= 0) {
                        displacement.animateTo(
                            displacementTarget,
                            spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
                        )
                    } else {
                        // Rows already sit at their displaced spot once the parent
                        // commits the order; snapping avoids a bounce back.
                        displacement.snapTo(0f)
                    }
                }
                val settle = remember { Animatable(0f) }
                LaunchedEffect(settleKey, settleFrom) {
                    if (settleKey == itemKey) {
                        settle.snapTo(settleFrom)
                        settle.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
                        settleKey = null
                    }
                }
                val scale by animateFloatAsState(
                    targetValue =
                        when {
                            isDragged -> ReorderableRowLiftedScale
                            isPressed -> ReorderableRowPressedScale
                            else -> 1f
                        },
                    animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium),
                    label = "reorderableRowScale",
                )
                val highlightAlpha by animateFloatAsState(
                    targetValue =
                        when {
                            isDragged -> 1f
                            isPressed -> 0.92f
                            else -> 0f
                        },
                    label = "reorderableRowHighlight",
                )
                val elevation by animateFloatAsState(
                    targetValue = if (isDragged) 1f else 0f,
                    label = "reorderableRowElevation",
                )
                val surfaceRaised = openCodeColor("surface-raised-base")
                val shape = RoundedCornerShape(ReorderableRowCornerRadius)

                val previousMovable = if (movable && index > 0 && canMove(items[index - 1])) index - 1 else null
                val nextMovable = if (movable && index < items.lastIndex && canMove(items[index + 1])) index + 1 else null

                // A lift only ever commits a move. The context action lives on the
                // swipe axis, so there is nothing to arbitrate here.
                fun finishLift(commit: Boolean) {
                    val from = currentItems.indexOfFirst { key(it) == itemKey }
                    val liftedTranslation = translation
                    draggedKey = null
                    translation = 0f
                    announcedTarget = -1
                    if (!commit || from < 0) return
                    val itemsNow = currentItems
                    val liveItem = itemsNow[from]
                    val heightsNow = itemsNow.map { rowHeights[key(it)] ?: fallbackHeightPx }
                    val canMoveNow: (Int) -> Boolean = { candidate -> currentCanMove(itemsNow[candidate]) }
                    val movableNow = currentOnMove != null && itemsNow.count(currentCanMove) >= 2 && currentCanMove(liveItem)
                    if (!movableNow) return
                    val clamped = ReorderableListMath.clampedTranslation(liftedTranslation, from, heightsNow, canMoveNow)
                    val target = ReorderableListMath.targetIndex(from, clamped, heightsNow, canMoveNow)
                    if (target != from) {
                        val slotDelta =
                            if (target > from) {
                                (from + 1..target).sumOf { heightsNow[it].toDouble() }.toFloat()
                            } else {
                                -(target until from).sumOf { heightsNow[it].toDouble() }.toFloat()
                            }
                        settleFrom = clamped - slotDelta
                        settleKey = itemKey
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentOnMove?.invoke(from, target)
                    }
                }

                // Lifts the row under [pointerId] and drags it until release; shared by the
                // long-press lift and the drag handle.
                suspend fun AwaitPointerEventScope.liftAndDrag(pointerId: PointerId) {
                    draggedKey = itemKey
                    translation = 0f
                    announcedTarget = currentItems.indexOfFirst { key(it) == itemKey }
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val completed =
                        drag(pointerId) { change ->
                            val delta = change.positionChange().y
                            change.consume()
                            translation += delta
                            val itemsNow = currentItems
                            val from = itemsNow.indexOfFirst { key(it) == itemKey }
                            if (from < 0) return@drag
                            val heightsNow = itemsNow.map { rowHeights[key(it)] ?: fallbackHeightPx }
                            val canMoveNow: (Int) -> Boolean =
                                { candidate -> currentCanMove(itemsNow[candidate]) }
                            val target =
                                ReorderableListMath.targetIndex(
                                    from,
                                    ReorderableListMath.clampedTranslation(
                                        translation,
                                        from,
                                        heightsNow,
                                        canMoveNow,
                                    ),
                                    heightsNow,
                                    canMoveNow,
                                )
                            if (target != announcedTarget) {
                                announcedTarget = target
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    if (completed) {
                        currentEvent.changes.forEach { if (it.changedToUp()) it.consume() }
                    }
                    finishLift(commit = completed)
                }

                val rowScope =
                    remember(itemKey) { ReorderableRowScopeImpl() }.also { scope ->
                        scope.isMovable = movable
                        scope.lift = { pointerId ->
                            try {
                                liftAndDrag(pointerId)
                            } catch (cancelled: CancellationException) {
                                if (draggedKey == itemKey) finishLift(commit = false)
                                throw cancelled
                            }
                        }
                    }

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .zIndex(if (isDragged || settleKey == itemKey) 1f else 0f)
                            .onSizeChanged { size -> rowHeights[itemKey] = size.height.toFloat() }
                            .graphicsLayer {
                                translationY = (if (isDragged) clampedTranslation else displacement.value) + settle.value
                                scaleX = scale
                                scaleY = scale
                            },
                ) {
                    if (canReveal && revealing) {
                        // The strip is laid out at full width against the trailing edge
                        // and slid in from off-screen, so the only part on show is the
                        // gap the row itself has vacated. Clipping is scoped to this
                        // wrapper so it never crops the lifted row's shadow.
                        Box(modifier = Modifier.matchParentSize().clipToBounds()) {
                            Box(
                                modifier =
                                    Modifier
                                        .align(Alignment.CenterEnd)
                                        .width(revealWidthDp)
                                        .fillMaxHeight()
                                        .graphicsLayer { translationX = revealWidthPx - revealPx }
                                        .background(MaterialTheme.colorScheme.error, shape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = contextActionLabel,
                                    color = MaterialTheme.colorScheme.onError,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .graphicsLayer { translationX = -revealPx }
                                .shadow(
                                    elevation = ReorderableRowLiftedElevation * elevation,
                                    shape = shape,
                                    clip = false,
                                    ambientColor = Color.Black.copy(alpha = 0.18f * elevation),
                                    spotColor = Color.Black.copy(alpha = 0.18f * elevation),
                                ).background(surfaceRaised.copy(alpha = surfaceRaised.alpha * highlightAlpha), shape)
                                .semantics(mergeDescendants = true) {
                                    role = Role.Button
                                    onClick(label = null) {
                                        onSelect(item)
                                        true
                                    }
                                    val actions = mutableListOf<CustomAccessibilityAction>()
                                    if (onContextAction != null && supportsContextAction(item)) {
                                        // With no menu behind it any more, the action names
                                        // itself rather than promising a list of choices.
                                        actions +=
                                            CustomAccessibilityAction(contextActionLabel) {
                                                onContextAction(item)
                                                true
                                            }
                                    }
                                    if (previousMovable != null) {
                                        actions +=
                                            CustomAccessibilityAction("Move up") {
                                                onMove?.invoke(index, previousMovable)
                                                true
                                            }
                                    }
                                    if (nextMovable != null) {
                                        actions +=
                                            CustomAccessibilityAction("Move down") {
                                                onMove?.invoke(index, nextMovable)
                                                true
                                            }
                                    }
                                    if (actions.isNotEmpty()) customActions = actions
                                }.pointerInput(itemKey, movable, canReveal) {
                                    // One detector owns press, tap, swipe and lift, so the
                                    // three arbitrate on the first decisive movement rather
                                    // than racing a clock. Horizontal past slop is a swipe;
                                    // the long-press timeout with no movement is a lift.
                                    //
                                    // The tap waits for its own up on Main and consumes it:
                                    // a parent clickable (the bottom sheet) sees Main after
                                    // us, so reading currentEvent later would find an
                                    // already-consumed up and drop the tap.
                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        val wasOpen = openKey == itemKey
                                        if (openKey != null && !wasOpen) {
                                            // A press on any other row just dismisses the
                                            // open one; it never also selects or lifts.
                                            openKey = null
                                            waitForUpOrCancellation()?.consume()
                                            return@awaitEachGesture
                                        }
                                        pressedKey = itemKey
                                        try {
                                            var phase = RowGesturePhase.Undecided
                                            var up: PointerInputChange? = null
                                            val decide: suspend AwaitPointerEventScope.() -> Unit = {
                                                while (phase == RowGesturePhase.Undecided) {
                                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                                    val change = event.changes.firstOrNull { it.id == down.id }
                                                    if (change == null) {
                                                        phase = RowGesturePhase.Cancelled
                                                        break
                                                    }
                                                    if (change.changedToUp()) {
                                                        up = change
                                                        phase = RowGesturePhase.Tap
                                                        break
                                                    }
                                                    if (
                                                        event.changes.any { it.isConsumed } ||
                                                        change.isOutOfBounds(size, extendedTouchPadding)
                                                    ) {
                                                        phase = RowGesturePhase.Cancelled
                                                        break
                                                    }
                                                    val dx = change.position.x - down.position.x
                                                    val dy = change.position.y - down.position.y
                                                    if (canReveal && abs(dx) > viewConfiguration.touchSlop && abs(dx) > abs(dy)) {
                                                        phase = RowGesturePhase.Swipe
                                                        break
                                                    }
                                                    if (abs(dy) > viewConfiguration.touchSlop) {
                                                        // Vertical before the lift belongs to the scroller.
                                                        phase = RowGesturePhase.Cancelled
                                                        break
                                                    }
                                                    if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) {
                                                        phase = RowGesturePhase.Cancelled
                                                        break
                                                    }
                                                }
                                            }
                                            if (movable && !wasOpen) {
                                                try {
                                                    withTimeout(viewConfiguration.longPressTimeoutMillis) { decide() }
                                                } catch (_: PointerEventTimeoutCancellationException) {
                                                    if (phase == RowGesturePhase.Undecided) phase = RowGesturePhase.Lift
                                                }
                                            } else {
                                                decide()
                                            }

                                            when (phase) {
                                                RowGesturePhase.Tap -> {
                                                    up?.consume()
                                                    if (wasOpen) openKey = null else onSelect(item)
                                                }

                                                RowGesturePhase.Swipe -> {
                                                    swiping = true
                                                    var offset = revealPx
                                                    val finished =
                                                        horizontalDrag(down.id) { change ->
                                                            offset =
                                                                (offset - change.positionChange().x)
                                                                    .coerceIn(0f, revealWidthPx)
                                                            revealPx = offset
                                                            change.consume()
                                                        }
                                                    val shouldOpen = finished && offset >= revealOpenThresholdPx
                                                    if (shouldOpen && openKey != itemKey) {
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    }
                                                    openKey = if (shouldOpen) itemKey else null
                                                    // Releasing hands the row to the settle effect.
                                                    swiping = false
                                                }

                                                RowGesturePhase.Lift -> liftAndDrag(down.id)

                                                RowGesturePhase.Undecided, RowGesturePhase.Cancelled -> Unit
                                            }
                                        } catch (cancelled: CancellationException) {
                                            if (draggedKey == itemKey) finishLift(commit = false)
                                            throw cancelled
                                        } finally {
                                            pressedKey = null
                                        }
                                    }
                                },
                    ) {
                        rowScope.row(item)
                    }
                    if (canReveal && openKey == itemKey) {
                        // The row's pointerInput covers its layout frame; graphicsLayer
                        // only slides the pixels. This target sits above that frame in
                        // the revealed gap, or Remove never receives the tap.
                        Box(modifier = Modifier.matchParentSize()) {
                            Box(
                                modifier =
                                    Modifier
                                        .align(Alignment.CenterEnd)
                                        .width(revealWidthDp)
                                        .fillMaxHeight()
                                        .clickable {
                                            openKey = null
                                            onContextAction?.invoke(item)
                                        },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Content scope of an [OpenCodeReorderableList] row. */
interface OpenCodeReorderableRowScope {
    /** Whether this row can be reordered right now. */
    val isMovable: Boolean

    /**
     * Grip that lifts the row as soon as it is touched and drags it while held. Shown dimmed
     * and inert while the row cannot move. Decorative for accessibility: the row carries
     * Move up / Move down actions.
     */
    @Composable
    fun DragHandle(modifier: Modifier)
}

private class ReorderableRowScopeImpl : OpenCodeReorderableRowScope {
    override var isMovable: Boolean by mutableStateOf(false)
    var lift: suspend AwaitPointerEventScope.(PointerId) -> Unit = {}

    @Composable
    override fun DragHandle(modifier: Modifier) {
        val color = openCodeColor("icon-base")
        val movable = isMovable
        Box(
            modifier =
                modifier
                    .size(ReorderableDragHandleTouchSize)
                    .clearAndSetSemantics {}
                    .pointerInput(movable) {
                        if (!movable) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            lift(down.id)
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(ReorderableDragHandleGlyphSize).alpha(if (movable) 1f else 0.4f)) {
                val stroke = 1.5.dp.toPx()
                val inset = size.width * 0.15f
                listOf(0.3f, 0.5f, 0.7f).forEach { fraction ->
                    val y = size.height * fraction
                    drawLine(
                        color = color,
                        start = Offset(inset, y),
                        end = Offset(size.width - inset, y),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

private val ReorderableDragHandleTouchSize = 40.dp
private val ReorderableDragHandleGlyphSize = 20.dp

private data class ReorderablePreviewProject(
    val id: String,
    val title: String,
    val pinned: Boolean,
)

@Preview(showBackground = true)
@Composable
private fun OpenCodeReorderableListPreview() {
    var items by remember {
        mutableStateOf(
            listOf(
                ReorderablePreviewProject("all", "All projects", pinned = true),
                ReorderablePreviewProject("a", "opencode-mobile", pinned = false),
                ReorderablePreviewProject("b", "playground", pinned = false),
                ReorderablePreviewProject("c", "website", pinned = false),
            ),
        )
    }
    OpenCodeTheme(darkTheme = true) {
        OpenCodeReorderableList(
            items = items,
            key = { it.id },
            onSelect = {},
            canMove = { !it.pinned },
            onMove = { from, to -> items = ReorderableListMath.move(items, from, to) },
            onContextAction = {},
            supportsContextAction = { !it.pinned },
            modifier = Modifier.padding(16.dp),
        ) { item ->
            OpenCodeListItem(
                title = item.title,
                subtitle = if (item.pinned) "3 projects" else "~/Projects/${item.title}",
                startDecorator =
                    if (item.pinned) {
                        StartDecorator.IconCircle(OpenCodeIcons.Widgets, null)
                    } else {
                        StartDecorator.Letter(item.title.first(), LetterAccent.Violet)
                    },
            )
        }
    }
}
