package dev.opencode.mobile.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalDragOrCancellation
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.domain.preferences.SplitPanePreferences
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import kotlin.math.abs

/**
 * Handed to the trailing pane so its top bar can show the sidebar toggle in the leading slot.
 */
data class OpenCodeSplitPaneDetailControl(
    val isCollapsed: Boolean,
    val toggle: () -> Unit,
)

/**
 * Top bar leading affordance for a screen that can be the trailing pane of [OpenCodeSplitPane]:
 * the sidebar toggle when [control] is present, otherwise the regular back button.
 */
fun openCodeDetailLeading(
    control: OpenCodeSplitPaneDetailControl?,
    onBack: () -> Unit,
): OpenCodeTopBarLeading =
    if (control != null) {
        OpenCodeTopBarLeading.CustomIcon(
            imageVector = OpenCodeIcons.Sidebar,
            contentDescription = if (control.isCollapsed) "Show sidebar" else "Hide sidebar",
            onClick = control.toggle,
        )
    } else {
        OpenCodeTopBarLeading.Back(onClick = onBack)
    }

/**
 * Leading list plus trailing detail for wide windows. Width and collapsed state persist per
 * [screen] in [SplitPanePreferences].
 */
@Composable
fun OpenCodeSplitPane(
    screen: OpenCodeSplitPaneScreen,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    trailing: @Composable (OpenCodeSplitPaneDetailControl) -> Unit,
) {
    val analyticsScreenId =
        when (screen) {
            OpenCodeSplitPaneScreen.Home -> ProductAnalytics.Screen.HOME
            OpenCodeSplitPaneScreen.Settings -> ProductAnalytics.Screen.SETTINGS
        }
    OpenCodeSplitPane(
        state = SplitPanePreferences.state(screen),
        onStateChange = { next ->
            if (next.isCollapsed != SplitPanePreferences.state(screen).isCollapsed) {
                ProductAnalytics.logUserAction(
                    screenId = analyticsScreenId,
                    actionId = ProductAnalytics.Action.SPLIT_PANE_TOGGLE_SIDEBAR,
                )
            }
            SplitPanePreferences.update(screen, next)
        },
        modifier = modifier,
        leading = leading,
        trailing = trailing,
    )
}

/** State-hoisted [OpenCodeSplitPane] for previews and the Component Gallery. */
@Composable
fun OpenCodeSplitPane(
    state: OpenCodeSplitPaneState,
    onStateChange: (OpenCodeSplitPaneState) -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    trailing: @Composable (OpenCodeSplitPaneDetailControl) -> Unit,
) {
    val currentState by rememberUpdatedState(state)
    val currentOnStateChange by rememberUpdatedState(onStateChange)
    var dragWidth by remember { mutableStateOf<Dp?>(null) }
    val liveDragWidth = dragWidth
    val targetWidth =
        when {
            liveDragWidth != null -> liveDragWidth.coerceIn(0.dp, OpenCodeSplitPaneLayout.MaxLeadingWidth)
            state.isCollapsed -> 0.dp
            else -> OpenCodeSplitPaneLayout.clamp(state.leadingWidth)
        }
    val animatedWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec =
            if (liveDragWidth != null) {
                snap()
            } else {
                tween(OpenCodeSplitPaneLayout.ANIMATION_DURATION_MILLIS)
            },
        label = "splitPaneLeadingWidth",
    )
    // The leading content keeps its own width and slides out under the clip instead of squishing.
    val leadingContentWidth = OpenCodeSplitPaneLayout.clamp(liveDragWidth ?: state.leadingWidth)
    val toggle = { currentOnStateChange(OpenCodeSplitPaneLayout.toggled(currentState)) }
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val coordinates = remember { SplitPaneCoordinates() }
    var grabOffset by remember { mutableStateOf(0.dp) }

    // Divider position in the container's leading-edge space, from the pointer's container position.
    fun dividerPosition(containerX: Float): Dp {
        val container = coordinates.container ?: return 0.dp
        val fromLeading = if (isRtl) container.size.width - containerX else containerX
        return with(density) { fromLeading.toDp() }
    }
    val control = OpenCodeSplitPaneDetailControl(isCollapsed = state.isCollapsed, toggle = toggle)

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates.container = it },
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (animatedWidth > 0.dp) {
                Box(
                    modifier =
                        Modifier
                            .width(animatedWidth)
                            .fillMaxHeight()
                            .clipToBounds(),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxHeight()
                                .wrapContentWidth(align = Alignment.End, unbounded = true)
                                .width(leadingContentWidth),
                    ) {
                        leading()
                    }
                }
                Box(
                    modifier =
                        Modifier
                            .width(OpenCodeSplitPaneLayout.DividerWidth)
                            .fillMaxHeight()
                            .background(openCodeColor("border-weak-base")),
                )
            }
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
            ) {
                trailing(control)
            }
        }

        if (!state.isCollapsed || liveDragWidth != null) {
            SplitPaneHandle(
                dividerOffset = animatedWidth,
                isDragging = liveDragWidth != null,
                onToggle = toggle,
                onHandlePositioned = { coordinates.handle = it },
                onDragStart = { handleLocal ->
                    val startWidth = OpenCodeSplitPaneLayout.clamp(currentState.leadingWidth)
                    val containerX = coordinates.toContainerX(handleLocal)
                    grabOffset = if (containerX != null) dividerPosition(containerX) - startWidth else 0.dp
                    dragWidth = startWidth
                },
                onDrag = { handleLocal ->
                    coordinates.toContainerX(handleLocal)?.let { containerX ->
                        dragWidth = (dividerPosition(containerX) - grabOffset).coerceAtLeast(0.dp)
                    }
                },
                onDragEnd = { travelled ->
                    val proposed = dragWidth
                    dragWidth = null
                    if (proposed == null) return@SplitPaneHandle
                    val next =
                        if (travelled < OpenCodeSplitPaneLayout.HandleTapSlop) {
                            OpenCodeSplitPaneLayout.toggled(currentState)
                        } else {
                            OpenCodeSplitPaneLayout.resolveDragEnd(
                                startedCollapsed = currentState.isCollapsed,
                                rememberedWidth = currentState.leadingWidth,
                                proposedWidth = proposed,
                            )
                        }
                    if (next != currentState) currentOnStateChange(next)
                },
                onDragCancel = { dragWidth = null },
            )
        }
    }
}

@Composable
private fun SplitPaneHandle(
    dividerOffset: Dp,
    isDragging: Boolean,
    onToggle: () -> Unit,
    onHandlePositioned: (LayoutCoordinates) -> Unit,
    onDragStart: (handleLocal: Offset) -> Unit,
    onDrag: (handleLocal: Offset) -> Unit,
    onDragEnd: (travelled: Dp) -> Unit,
    onDragCancel: () -> Unit,
) {
    val currentOnToggle by rememberUpdatedState(onToggle)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    val touchWidth = OpenCodeSplitPaneLayout.HandleTouchWidth
    val dividerCenter = dividerOffset + OpenCodeSplitPaneLayout.DividerWidth / 2

    Box(
        modifier =
            Modifier
                .fillMaxHeight()
                .wrapContentWidth(align = Alignment.Start),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier =
                Modifier
                    .offset { IntOffset((dividerCenter - touchWidth / 2).roundToPx(), 0) }
                    .size(width = touchWidth, height = OpenCodeSplitPaneLayout.HandleTouchHeight)
                    .onGloballyPositioned(onHandlePositioned)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Resize sidebar"
                        onClick(label = "Hide sidebar") {
                            currentOnToggle()
                            true
                        }
                    }.pointerInput(Unit) {
                        // Positions are mapped to the stationary container because this handle
                        // moves with the divider while it is dragged.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var travelled = 0f
                            val slopChange =
                                awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                                    change.consume()
                                    travelled += abs(overSlop)
                                } ?: return@awaitEachGesture
                            travelled += abs(slopChange.position.x - down.position.x)
                            currentOnDragStart(down.position)
                            currentOnDrag(slopChange.position)
                            // The up event can carry the final movement, so it updates the width too.
                            var completed = false
                            while (true) {
                                val change = awaitHorizontalDragOrCancellation(slopChange.id) ?: break
                                travelled += abs(change.positionChangeIgnoreConsumed().x)
                                currentOnDrag(change.position)
                                change.consume()
                                if (change.changedToUpIgnoreConsumed()) {
                                    completed = true
                                    break
                                }
                            }
                            if (completed) {
                                currentOnDragEnd(travelled.toDp())
                            } else {
                                currentOnDragCancel()
                            }
                        }
                    }.pointerInput(Unit) {
                        detectTapGestures(onTap = { currentOnToggle() })
                    },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(
                            width = OpenCodeSplitPaneLayout.HandleGripWidth,
                            height = OpenCodeSplitPaneLayout.HandleGripHeight,
                        ).clip(RoundedCornerShape(OpenCodeSplitPaneLayout.HandleGripWidth / 2))
                        .background(
                            if (isDragging) openCodeColor("text-interactive-base") else openCodeColor("text-weak"),
                        ),
            )
        }
    }
}

/** Layout coordinates for mapping handle-local pointer positions into the split container. */
private class SplitPaneCoordinates {
    var container: LayoutCoordinates? = null
    var handle: LayoutCoordinates? = null

    fun toContainerX(handleLocal: Offset): Float? {
        val container = container?.takeIf { it.isAttached } ?: return null
        val handle = handle?.takeIf { it.isAttached } ?: return null
        return container.localPositionOf(handle, handleLocal).x
    }
}
