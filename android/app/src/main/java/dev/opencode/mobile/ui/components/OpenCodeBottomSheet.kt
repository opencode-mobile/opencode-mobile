package dev.opencode.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val BottomSheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
private const val BottomSheetEnterDurationMillis = 320
private const val BottomSheetExitDurationMillis = 280
private const val BottomSheetScrimAlpha = 0.32f
private val BottomSheetDragDismissThreshold = 96.dp
private const val BottomSheetDragDismissVelocity = 1_200f
private val BottomSheetHeaderHorizontalPadding = 20.dp
private val BottomSheetHeaderTopPadding = 12.dp
private val BottomSheetHeaderBottomPadding = 8.dp
private val BottomSheetContentBottomPadding = 20.dp
private val BottomSheetEntryMinHeight = 48.dp
private val BottomSheetEntryHorizontalPadding = 20.dp
private val BottomSheetEntryVerticalPadding = 12.dp
private val BottomSheetEntryDecoratorSize = 24.dp
private val BottomSheetEntryIconSize = 20.dp

sealed class BottomSheetEntryDecorator {
    data object None : BottomSheetEntryDecorator()

    data class Icon(
        val imageVector: ImageVector,
        val contentDescription: String? = null,
    ) : BottomSheetEntryDecorator()
}

@Composable
fun OpenCodeBottomSheet(
    visible: Boolean,
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    keyboardAware: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var shouldRender by remember { mutableStateOf(visible) }
    var sheetVisible by remember { mutableStateOf(false) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()
    val dragDismissThresholdPx = with(LocalDensity.current) { BottomSheetDragDismissThreshold.toPx() }

    LaunchedEffect(visible) {
        if (visible) {
            shouldRender = true
            dragOffsetPx = 0f
            sheetVisible = true
        } else if (shouldRender && sheetVisible) {
            sheetVisible = false
            delay(BottomSheetExitDurationMillis.toLong())
            dragOffsetPx = 0f
            shouldRender = false
        }
    }

    if (!shouldRender) return

    fun dismissWithAnimation() {
        coroutineScope.launch {
            if (sheetVisible) {
                sheetVisible = false
                delay(BottomSheetExitDurationMillis.toLong())
            }
            dragOffsetPx = 0f
            shouldRender = false
            onDismissRequest()
        }
    }

    val dragState =
        rememberDraggableState { delta ->
            dragOffsetPx = (dragOffsetPx + delta).coerceAtLeast(0f)
        }

    val scrimAlpha by animateFloatAsState(
        targetValue = if (sheetVisible) BottomSheetScrimAlpha else 0f,
        animationSpec = tween(BottomSheetExitDurationMillis),
        label = "BottomSheetScrimAlpha",
    )

    val scrimInteractionSource = remember { MutableInteractionSource() }
    val sheetInteractionSource = remember { MutableInteractionSource() }

    Popup(
        onDismissRequest = ::dismissWithAnimation,
        properties =
            PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha))
                    .clickable(
                        interactionSource = scrimInteractionSource,
                        indication = null,
                        onClick = ::dismissWithAnimation,
                    ),
        ) {
            AnimatedVisibility(
                visible = sheetVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter =
                    slideInVertically(
                        animationSpec = tween(BottomSheetEnterDurationMillis, easing = FastOutSlowInEasing),
                        initialOffsetY = { it },
                    ) + fadeIn(animationSpec = tween(BottomSheetEnterDurationMillis)),
                exit =
                    slideOutVertically(
                        animationSpec = tween(BottomSheetExitDurationMillis, easing = FastOutLinearInEasing),
                        targetOffsetY = { it },
                    ) + fadeOut(animationSpec = tween(BottomSheetExitDurationMillis)),
            ) {
                Surface(
                    modifier =
                        modifier
                            .fillMaxWidth()
                            .offset { IntOffset(x = 0, y = dragOffsetPx.roundToInt()) }
                            .draggable(
                                orientation = Orientation.Vertical,
                                state = dragState,
                                onDragStopped = { velocity ->
                                    if (dragOffsetPx >= dragDismissThresholdPx || velocity >= BottomSheetDragDismissVelocity) {
                                        dismissWithAnimation()
                                    } else {
                                        dragOffsetPx = 0f
                                    }
                                },
                            )
                            .clickable(
                                interactionSource = sheetInteractionSource,
                                indication = null,
                                onClick = {},
                            ),
                    shape = BottomSheetShape,
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    BottomSheetContent(
                        title = title,
                        keyboardAware = keyboardAware,
                        onDismissRequest = ::dismissWithAnimation,
                        content = content,
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomSheetContent(
    title: String,
    keyboardAware: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bodyModifier =
        Modifier
            .fillMaxWidth()
            .padding(bottom = BottomSheetContentBottomPadding)
            .navigationBarsPadding()
            .then(if (keyboardAware) Modifier.imePadding() else Modifier)

    Column(modifier = bodyModifier) {
        Row(
            modifier =
                Modifier
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
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            OpenCodeButton(
                variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Close, "Close"),
                contrast = OpenCodeButtonContrast.Low,
                onClick = onDismissRequest,
            )
        }

        content()
    }
}

@Composable
fun OpenCodeBottomSheetEntry(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    startDecorator: BottomSheetEntryDecorator = BottomSheetEntryDecorator.None,
    endDecorator: BottomSheetEntryDecorator = BottomSheetEntryDecorator.None,
) {
    val contentColor =
        if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val iconColor =
        if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = BottomSheetEntryMinHeight)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(
                    horizontal = BottomSheetEntryHorizontalPadding,
                    vertical = BottomSheetEntryVerticalPadding,
                ).then(if (enabled) Modifier else Modifier.alpha(0.7f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BottomSheetEntryDecoration(decorator = startDecorator, tint = iconColor)
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BottomSheetEntryDecoration(decorator = endDecorator, tint = iconColor)
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
                    contentDescription = decorator.contentDescription,
                    modifier = Modifier.size(BottomSheetEntryIconSize),
                    tint = tint,
                )
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
                startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Add, "New session"),
                onClick = {},
            )
            OpenCodeBottomSheetEntry(
                title = "Open project",
                startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Folder, "Open project"),
                endDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.ChevronRight, null),
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
                endDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.ChevronRight, null),
                onClick = {},
            )
            OpenCodeBottomSheetEntry(
                title = "Delete session",
                enabled = false,
                startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Close, "Delete session"),
                onClick = {},
            )
        }
    }
}
