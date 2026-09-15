package dev.opencode.mobile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

private val ReorderableRowCornerRadius = 8.dp
private val ReorderableRowLiftedElevation = 8.dp
private val ReorderableRowFallbackHeight = 60.dp
private const val ReorderableRowPressedScale = 0.965f
private const val ReorderableRowLiftedScale = 1.03f

/**
 * Stacked rows that reorder with long-press and drag.
 *
 * Tap selects a row. Long-press lifts a movable row; dragging shifts the
 * neighbors live and release commits [onMove]. Releasing a lifted row without
 * moving it fires [onContextAction], so a row can keep one long-press action
 * alongside reordering. Rows that fail [canMove] never lift or get crossed.
 * Reordering is inactive until at least two rows can move or [onMove] is null.
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
    row: @Composable (T) -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val fallbackHeightPx = with(density) { ReorderableRowFallbackHeight.toPx() }
    val contextActionLimitPx = with(density) { ReorderableListMath.CONTEXT_ACTION_TRANSLATION_LIMIT_DP.dp.toPx() }

    val currentItems by rememberUpdatedState(items)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnContextAction by rememberUpdatedState(onContextAction)
    val currentCanMove by rememberUpdatedState(canMove)
    val currentSupportsContextAction by rememberUpdatedState(supportsContextAction)

    val rowHeights = remember { mutableStateMapOf<Any, Float>() }
    var pressedKey by remember { mutableStateOf<Any?>(null) }
    var draggedKey by remember { mutableStateOf<Any?>(null) }
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
                val liftsOnLongPress = movable || (onContextAction != null && supportsContextAction(item))
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
                    if (!movableNow) {
                        if (currentSupportsContextAction(liveItem)) currentOnContextAction?.invoke(liveItem)
                        return
                    }
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
                    } else if (abs(liftedTranslation) < contextActionLimitPx && currentSupportsContextAction(liveItem)) {
                        currentOnContextAction?.invoke(liveItem)
                    }
                }

                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .zIndex(if (isDragged || settleKey == itemKey) 1f else 0f)
                            .onSizeChanged { size -> rowHeights[itemKey] = size.height.toFloat() }
                            .graphicsLayer {
                                translationY = (if (isDragged) clampedTranslation else displacement.value) + settle.value
                                scaleX = scale
                                scaleY = scale
                            }.shadow(
                                elevation = ReorderableRowLiftedElevation * elevation,
                                shape = shape,
                                clip = false,
                                ambientColor = Color.Black.copy(alpha = 0.18f * elevation),
                                spotColor = Color.Black.copy(alpha = 0.18f * elevation),
                            ).background(surfaceRaised.copy(alpha = surfaceRaised.alpha * highlightAlpha), shape)
                            .semantics {
                                val actions = mutableListOf<CustomAccessibilityAction>()
                                if (onContextAction != null && supportsContextAction(item)) {
                                    actions +=
                                        CustomAccessibilityAction("Show actions") {
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
                            }.pointerInput(itemKey, liftsOnLongPress, movable) {
                                // One detector owns press, tap, long-press and drag. Stacking
                                // detectTapGestures under detectDragGesturesAfterLongPress lets
                                // the tap detector consume the release of a stationary long-press,
                                // which cancels the lift and fires a tap instead.
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    pressedKey = itemKey
                                    try {
                                        val longPress = if (liftsOnLongPress) awaitLongPressOrCancellation(down.id) else null
                                        if (longPress == null) {
                                            val up =
                                                if (liftsOnLongPress) {
                                                    currentEvent.changes.firstOrNull {
                                                        it.id == down.id && it.changedToUp() && !it.isOutOfBounds(size, extendedTouchPadding)
                                                    }
                                                } else {
                                                    waitForUpOrCancellation()
                                                }
                                            if (up != null) {
                                                up.consume()
                                                onSelect(item)
                                            }
                                            return@awaitEachGesture
                                        }
                                        draggedKey = itemKey
                                        translation = 0f
                                        announcedTarget = currentItems.indexOfFirst { key(it) == itemKey }
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val completed =
                                            drag(longPress.id) { change ->
                                                val delta = change.positionChange().y
                                                change.consume()
                                                if (!movable) return@drag
                                                translation += delta
                                                val itemsNow = currentItems
                                                val from = itemsNow.indexOfFirst { key(it) == itemKey }
                                                if (from < 0) return@drag
                                                val heightsNow = itemsNow.map { rowHeights[key(it)] ?: fallbackHeightPx }
                                                val canMoveNow: (Int) -> Boolean = { candidate -> currentCanMove(itemsNow[candidate]) }
                                                val target =
                                                    ReorderableListMath.targetIndex(
                                                        from,
                                                        ReorderableListMath.clampedTranslation(translation, from, heightsNow, canMoveNow),
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
                                    } catch (cancelled: CancellationException) {
                                        if (draggedKey == itemKey) finishLift(commit = false)
                                        throw cancelled
                                    } finally {
                                        pressedKey = null
                                    }
                                }
                            },
                ) {
                    row(item)
                }
            }
        }
    }
}

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
