package dev.opencode.mobile.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.provider.Settings
import android.view.RoundedCorner
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons
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

@Suppress("ktlint:standard:property-naming")
private const val BottomSheetScrimAlpha = 0.32f

@Suppress("ktlint:standard:property-naming")
private val BottomSheetDragDismissThreshold = 96.dp

@Suppress("ktlint:standard:property-naming")
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

@Suppress("ktlint:standard:property-naming")
private const val BottomSheetScrollableHeightFraction = 0.50f

private val BottomSheetCompactMaxHeight = 360.dp
private val BottomSheetEstimatedHeaderHeight =
    BottomSheetHeaderTopPadding + BottomSheetHeaderBottomPadding + 40.dp

private enum class BottomSheetLayoutMode {
    Measuring,
    Compact,
    Scrollable,
}

enum class OpenCodeBottomSheetContentMode {
    ActionList,
    Form,
}

sealed class BottomSheetEntryDecorator {
    data object None : BottomSheetEntryDecorator()

    data class Icon(
        val imageVector: ImageVector,
        val contentDescription: String? = null,
    ) : BottomSheetEntryDecorator()
}

data class OpenCodeBottomSheetHeaderAction(
    val imageVector: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit,
)

@Composable
fun OpenCodeBottomSheet(
    visible: Boolean,
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    headerLeadingAction: OpenCodeBottomSheetHeaderAction? = null,
    contentMode: OpenCodeBottomSheetContentMode = OpenCodeBottomSheetContentMode.ActionList,
    alwaysFixedHeight: Boolean = false,
    pinnedContent: @Composable ColumnScope.() -> Unit = {},
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
    val sheetCornerRadius = rememberBottomSheetCornerRadius()
    val view = LocalView.current
    val threeButtonNav = remember(view) { view.context.usesThreeButtonNavigation() }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val sheetBottomInset =
        if (threeButtonNav) {
            navBottom + BottomSheetInset
        } else {
            BottomSheetInset
        }

    BackHandler(onBack = ::dismissWithAnimation)
    BottomSheetWindowOverlay {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .semantics { dialog() }
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha))
                    .clickable(
                        interactionSource = scrimInteractionSource,
                        indication = null,
                        onClick = ::dismissWithAnimation,
                    ),
        ) {
            AnimatedVisibility(
                visible = sheetVisible,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .then(
                            if (contentMode == OpenCodeBottomSheetContentMode.Form) Modifier.imePadding() else Modifier,
                        ),
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
                            .padding(start = BottomSheetInset, end = BottomSheetInset, bottom = sheetBottomInset)
                            .offset { IntOffset(x = 0, y = dragOffsetPx.roundToInt()) }
                            .clickable(
                                interactionSource = sheetInteractionSource,
                                indication = null,
                                onClick = {},
                            ),
                    shape =
                        RoundedCornerShape(
                            topStart = BottomSheetCornerRadiusTop,
                            topEnd = BottomSheetCornerRadiusTop,
                            bottomEnd = sheetCornerRadius,
                            bottomStart = sheetCornerRadius,
                        ),
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    BottomSheetContent(
                        title = title,
                        headerLeadingAction = headerLeadingAction,
                        contentMode = contentMode,
                        alwaysFixedHeight = alwaysFixedHeight,
                        onDismissRequest = ::dismissWithAnimation,
                        headerDragModifier =
                            Modifier.draggable(
                                orientation = Orientation.Vertical,
                                state = dragState,
                                onDragStopped = { velocity ->
                                    if (dragOffsetPx >= dragDismissThresholdPx || velocity >= BottomSheetDragDismissVelocity) {
                                        dismissWithAnimation()
                                    } else {
                                        dragOffsetPx = 0f
                                    }
                                },
                            ),
                        pinnedContent = pinnedContent,
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
    headerLeadingAction: OpenCodeBottomSheetHeaderAction?,
    contentMode: OpenCodeBottomSheetContentMode,
    alwaysFixedHeight: Boolean,
    onDismissRequest: () -> Unit,
    headerDragModifier: Modifier,
    pinnedContent: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val maxSheetHeight =
        configuration.screenHeightDp.dp * BottomSheetScrollableHeightFraction - BottomSheetInset
    var contentHeight by remember { mutableStateOf(0.dp) }
    var headerHeight by remember { mutableStateOf(0.dp) }
    var pinnedContentHeight by remember { mutableStateOf(0.dp) }
    var lockedSheetHeight by remember { mutableStateOf<Dp?>(null) }
    var committedToScrollable by remember { mutableStateOf(alwaysFixedHeight) }
    var previousContentMode by remember { mutableStateOf(contentMode) }
    val scrollState = rememberScrollState()

    if (alwaysFixedHeight && !committedToScrollable) {
        committedToScrollable = true
        lockedSheetHeight = maxSheetHeight
    }

    if (contentMode != previousContentMode) {
        val switchingIntoForm =
            previousContentMode == OpenCodeBottomSheetContentMode.ActionList &&
                contentMode == OpenCodeBottomSheetContentMode.Form
        previousContentMode = contentMode
        // Invalidate the previous body's measured height so layout mode is not chosen
        // from stale provider-list metrics while the new model list is already rendering.
        contentHeight = 0.dp
        if (switchingIntoForm || alwaysFixedHeight) {
            // Filtered model lists are always the long-sheet case; lock immediately so the
            // first frame does not expand unconstrained under Compact.
            committedToScrollable = true
            lockedSheetHeight = maxSheetHeight
        } else {
            lockedSheetHeight = null
            committedToScrollable = false
        }
    }

    val layoutMode =
        remember(
            contentHeight,
            headerHeight,
            pinnedContentHeight,
            committedToScrollable,
            contentMode,
            alwaysFixedHeight,
        ) {
            if (alwaysFixedHeight || committedToScrollable) {
                BottomSheetLayoutMode.Scrollable
            } else if (contentHeight == 0.dp) {
                BottomSheetLayoutMode.Measuring
            } else {
                val resolvedHeader =
                    if (headerHeight > 0.dp) headerHeight else BottomSheetEstimatedHeaderHeight
                val total =
                    resolvedHeader + pinnedContentHeight + contentHeight + BottomSheetContentBottomPadding
                if (total > BottomSheetCompactMaxHeight) {
                    BottomSheetLayoutMode.Scrollable
                } else {
                    BottomSheetLayoutMode.Compact
                }
            }
        }

    LaunchedEffect(layoutMode) {
        if (layoutMode == BottomSheetLayoutMode.Scrollable) {
            committedToScrollable = true
        }
    }

    LaunchedEffect(contentHeight, headerHeight, pinnedContentHeight, contentMode, maxSheetHeight) {
        if (contentMode != OpenCodeBottomSheetContentMode.Form || contentHeight == 0.dp) return@LaunchedEffect
        val resolvedHeader =
            if (headerHeight > 0.dp) headerHeight else BottomSheetEstimatedHeaderHeight
        val total =
            resolvedHeader + pinnedContentHeight + contentHeight + BottomSheetContentBottomPadding
        val target =
            if (total > BottomSheetCompactMaxHeight || committedToScrollable) {
                maxSheetHeight
            } else {
                total.coerceAtLeast(BottomSheetEntryMinHeight)
            }
        lockedSheetHeight =
            when (val current = lockedSheetHeight) {
                null -> target
                else -> maxOf(current, target)
            }
    }

    val resolvedHeaderHeight =
        if (headerHeight > 0.dp) headerHeight else BottomSheetEstimatedHeaderHeight
    val resolvedSheetHeight =
        when {
            contentMode == OpenCodeBottomSheetContentMode.Form ->
                lockedSheetHeight ?: maxSheetHeight
            else -> maxSheetHeight
        }
    val scrollBodyMaxHeight =
        (resolvedSheetHeight - resolvedHeaderHeight - pinnedContentHeight - BottomSheetContentBottomPadding)
            .coerceAtLeast(BottomSheetEntryMinHeight)

    val contentView = LocalView.current
    val threeButtonNav = remember(contentView) { contentView.context.usesThreeButtonNavigation() }
    val bodyModifier =
        Modifier
            .fillMaxWidth()
            .padding(bottom = BottomSheetContentBottomPadding)

    Box(modifier = bodyModifier) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(
                        when (layoutMode) {
                            // Cap Compact so a stale short contentHeight cannot let a newly
                            // swapped long body expand the sheet to full screen for a frame.
                            BottomSheetLayoutMode.Compact ->
                                Modifier
                                    .heightIn(max = BottomSheetCompactMaxHeight)
                                    .wrapContentHeight()
                            BottomSheetLayoutMode.Measuring -> Modifier.heightIn(max = maxSheetHeight)
                            BottomSheetLayoutMode.Scrollable ->
                                if (contentMode == OpenCodeBottomSheetContentMode.Form) {
                                    Modifier.height(lockedSheetHeight ?: maxSheetHeight)
                                } else {
                                    Modifier.height(maxSheetHeight)
                                }
                        },
                    ),
        ) {
            BottomSheetHeader(
                title = title,
                leadingAction = headerLeadingAction,
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
                            pinnedContentHeight = with(density) { it.height.toDp() }
                        },
            ) {
                pinnedContent()
            }

            when (layoutMode) {
                BottomSheetLayoutMode.Compact -> {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .wrapContentHeight(),
                    ) {
                        content()
                    }
                }
                BottomSheetLayoutMode.Measuring,
                BottomSheetLayoutMode.Scrollable,
                -> {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = scrollBodyMaxHeight)
                                .then(if (threeButtonNav) Modifier else Modifier.navigationBarsPadding())
                                .verticalScroll(scrollState),
                    ) {
                        content()
                    }
                }
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .measureContentHeight(density) { measured ->
                        if (contentHeight != measured) {
                            contentHeight = measured
                        }
                    },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
private fun BottomSheetHeader(
    title: String,
    leadingAction: OpenCodeBottomSheetHeaderAction?,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
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
}

private fun Modifier.measureContentHeight(
    density: Density,
    onHeight: (Dp) -> Unit,
): Modifier =
    layout { measurable, constraints ->
        val placeable =
            measurable.measure(
                constraints.copy(
                    minHeight = 0,
                    maxHeight = Constraints.Infinity,
                ),
            )
        onHeight(with(density) { placeable.height.toDp() })
        layout(0, 0) {}
    }

@Composable
private fun rememberBottomSheetCornerRadius(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    val displayRadius =
        deviceBottomCornerRadiusPx(view.context)?.let { px ->
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

private const val NavigationModeGestural = 2

private fun Context.usesThreeButtonNavigation(): Boolean =
    Settings.Secure.getInt(contentResolver, "navigation_mode", NavigationModeGestural) !=
        NavigationModeGestural

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@Composable
private fun BottomSheetWindowOverlay(content: @Composable () -> Unit) {
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
            }
        val root = activity.window.decorView as ViewGroup
        root.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.requestFocus()
        onDispose { root.removeView(overlay) }
    }
}

@Composable
fun OpenCodeBottomSheetEntry(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    startDecorator: BottomSheetEntryDecorator = BottomSheetEntryDecorator.None,
    endDecorator: BottomSheetEntryDecorator = BottomSheetEntryDecorator.None,
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
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            destructive -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        }
    val iconColor =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            destructive -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val entryShape = RoundedCornerShape(10.dp)

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
                )
                .defaultMinSize(minHeight = BottomSheetEntryMinHeight)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true),
                    enabled = enabled,
                    onClick = onClick,
                )
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
private fun OpenCodeBottomSheetScrollablePreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeBottomSheet(visible = true, title = "Model", onDismissRequest = {}) {
            repeat(24) { index ->
                OpenCodeBottomSheetEntry(
                    title = "Model ${index + 1} · Provider",
                    onClick = {},
                )
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
                destructive = true,
                startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Close, "Delete session"),
                onClick = {},
            )
        }
    }
}
