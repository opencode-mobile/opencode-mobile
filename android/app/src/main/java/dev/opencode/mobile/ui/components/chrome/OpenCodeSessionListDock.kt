package dev.opencode.mobile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.badgeColors
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private val NewSessionButtonShape = RoundedCornerShape(percent = 50)
private val ScopeControlShape = RoundedCornerShape(20.dp)
private const val DockControlFillAlpha = 0.05f

@Composable
private fun dockControlFill(): Color = openCodeColor("text-strong").copy(alpha = DockControlFillAlpha)

@Composable
fun OpenCodeSessionListDock(
    filterTabs: List<SessionListFilterTab>,
    selectedFilterId: String,
    onAddProject: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
    createError: String? = null,
    onOpenProjectPicker: () -> Unit = {},
    onFilterSelected: (String) -> Unit = {},
    onNewSessionLongPress: (() -> Unit)? = null,
) {
    val textStrong = openCodeColor("text-strong")
    val error = openCodeColor("error")
    val iconStrong = openCodeColor("icon-strong-base")
    val controlFill = dockControlFill()
    OpenCodeFloatingDock(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            createError?.let {
                Text(
                    text = it,
                    color = error,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (filterTabs.isNotEmpty()) {
                SessionListScopeControl(
                    tabs = filterTabs,
                    selectedId = selectedFilterId,
                    onOpen = onOpenProjectPicker,
                    onSelect = onFilterSelected,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DockCta(
                    onClick = onAddProject,
                    fill = controlFill,
                    shape = CircleShape,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        imageVector = OpenCodeIcons.CreateNewFolder,
                        contentDescription = "Add project",
                        tint = iconStrong,
                        modifier = Modifier.size(22.dp),
                    )
                }
                DockCta(
                    onClick = onNewSession,
                    onLongClick = onNewSessionLongPress,
                    onLongClickLabel = if (onNewSessionLongPress != null) "Choose project" else null,
                    fill = controlFill,
                    shape = NewSessionButtonShape,
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(52.dp),
                ) {
                    Text(
                        text = "New session",
                        color = textStrong,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                    )
                }
            }
        }
    }
}

@Composable
fun SessionListScopeControl(
    tabs: List<SessionListFilterTab>,
    selectedId: String,
    onOpen: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textWeak = openCodeColor("text-weak")
    var shownTabId by remember { mutableStateOf(selectedId) }
    val selectedIndex = tabs.indexOfFirst { it.id == shownTabId }.let { if (it >= 0) it else 0 }
    val visibleTab = tabs.getOrNull(selectedIndex) ?: return
    val description = scopeControlDescription(visibleTab)
    val settleOffset = remember { Animatable(0f) }
    val outgoingOffset = remember { Animatable(0f) }
    val incomingOffset = remember { Animatable(0f) }
    val settleValue by settleOffset.asState()
    val outgoingValue by outgoingOffset.asState()
    val incomingValue by incomingOffset.asState()
    var clusterSlide by remember { mutableFloatStateOf(0f) }
    var isSettling by remember { mutableStateOf(false) }
    var isSwitching by remember { mutableStateOf(false) }
    var outgoingTab by remember { mutableStateOf<SessionListFilterTab?>(null) }
    var slotWidthPx by remember { mutableFloatStateOf(1f) }
    val displayed =
        when {
            isSwitching -> incomingValue
            isSettling -> settleValue
            else -> clusterSlide
        }
    val showMotionFade =
        isSwitching || isSettling || abs(displayed) > 0.5f || abs(clusterSlide) > 0.5f
    val settleJob = remember { arrayOfNulls<Job>(1) }
    val density = LocalDensity.current
    val fadeWidthPx by animateFloatAsState(
        targetValue =
            if (showMotionFade) {
                with(density) { SessionListScopePaging.EDGE_FADE_WIDTH_DP.dp.toPx() }
            } else {
                0f
            },
        animationSpec =
            tween(
                durationMillis = SessionListScopePaging.EDGE_FADE_DURATION_MS,
                easing = CubicBezierEasing(0f, 0f, 0.58f, 1f),
            ),
        label = "scopeEdgeFade",
    )
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val latestOnOpen by rememberUpdatedState(onOpen)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestTabs by rememberUpdatedState(tabs)
    val latestSelectedIndex by rememberUpdatedState(selectedIndex)
    val latestVisibleTab by rememberUpdatedState(visibleTab)
    val rubberBandLimitPx =
        with(density) { SessionListScopePaging.RUBBER_BAND_LIMIT_DP.dp.toPx() }
    val switchThresholdPx =
        with(density) { SessionListScopePaging.SWITCH_THRESHOLD_DP.dp.toPx() }
    val canGoPrevious =
        SessionListScopePaging.neighborIndex(
            selectedIndex = selectedIndex,
            count = tabs.size,
            direction = SessionListScopePaging.Direction.Previous,
        ) != null
    val canGoNext =
        SessionListScopePaging.neighborIndex(
            selectedIndex = selectedIndex,
            count = tabs.size,
            direction = SessionListScopePaging.Direction.Next,
        ) != null
    val latestCanGoPrevious by rememberUpdatedState(canGoPrevious)
    val latestCanGoNext by rememberUpdatedState(canGoNext)
    val switchEasing = CubicBezierEasing(0.45f, 0.0f, 0.12f, 1.0f)

    fun neighborTab(direction: SessionListScopePaging.Direction): SessionListFilterTab? {
        val currentTabs = latestTabs
        val index =
            SessionListScopePaging.neighborIndex(
                selectedIndex = latestSelectedIndex,
                count = currentTabs.size,
                direction = direction,
            ) ?: return null
        return currentTabs.getOrNull(index)
    }

    fun startSwitch(
        direction: SessionListScopePaging.Direction,
        from: Float,
    ) {
        val neighbor = neighborTab(direction) ?: return
        settleJob[0]?.cancel()
        settleJob[0] =
            scope.launch {
                val exit =
                    SessionListScopePaging.settleTranslation(
                        direction = direction,
                        pageWidth = slotWidthPx,
                    )
                outgoingTab = latestVisibleTab
                outgoingOffset.snapTo(from)
                incomingOffset.snapTo(-exit)
                shownTabId = neighbor.id
                isSwitching = true
                isSettling = false
                clusterSlide = 0f
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                latestOnSelect(neighbor.id)
                kotlinx.coroutines.coroutineScope {
                    launch {
                        outgoingOffset.animateTo(
                            exit,
                            tween(SessionListScopePaging.SWITCH_DURATION_MS, easing = switchEasing),
                        )
                    }
                    launch {
                        incomingOffset.animateTo(
                            0f,
                            tween(SessionListScopePaging.SWITCH_DURATION_MS, easing = switchEasing),
                        )
                    }
                }
                ensureActive()
                outgoingTab = null
                outgoingOffset.snapTo(0f)
                incomingOffset.snapTo(0f)
                settleOffset.snapTo(0f)
                isSwitching = false
            }
    }

    LaunchedEffect(selectedId) {
        if (!isSwitching) {
            shownTabId = selectedId
            clusterSlide = 0f
            settleOffset.snapTo(0f)
            outgoingTab = null
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(ScopeControlShape)
                .background(dockControlFill())
                .pointerInput(shownTabId, isSwitching) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (isSwitching) {
                            return@awaitEachGesture
                        }
                        settleJob[0]?.cancel()
                        settleJob[0] = null
                        if (isSettling) {
                            clusterSlide = settleOffset.value
                            isSettling = false
                        }
                        var dragged = false
                        var committed = false
                        var total = clusterSlide
                        val slop = viewConfiguration.touchSlop
                        drag(down.id) { change ->
                            if (committed || isSwitching) {
                                change.consume()
                                return@drag
                            }
                            val delta = change.positionChange().x
                            total += delta
                            if (abs(total) > slop) {
                                dragged = true
                            }
                            change.consume()
                            val visual =
                                SessionListScopePaging.rubberBandOffset(
                                    translation = total,
                                    rubberBandLimit = rubberBandLimitPx,
                                )
                            clusterSlide = visual
                            val direction =
                                SessionListScopePaging.commitDirection(
                                    visualOffset = visual,
                                    canGoPrevious = latestCanGoPrevious,
                                    canGoNext = latestCanGoNext,
                                    switchThreshold = switchThresholdPx,
                                )
                            if (direction != null) {
                                committed = true
                                startSwitch(direction, from = visual)
                            }
                        }
                        if (committed || isSwitching) {
                            return@awaitEachGesture
                        }
                        if (!dragged) {
                            latestOnOpen()
                            return@awaitEachGesture
                        }
                        val visual =
                            SessionListScopePaging.rubberBandOffset(
                                translation = total,
                                rubberBandLimit = rubberBandLimitPx,
                            )
                        clusterSlide = visual
                        settleJob[0] =
                            scope.launch {
                                settleOffset.snapTo(visual)
                                isSettling = true
                                settleOffset.animateTo(
                                    0f,
                                    tween(
                                        SessionListScopePaging.RUBBER_BAND_RETURN_DURATION_MS,
                                        easing = switchEasing,
                                    ),
                                )
                                ensureActive()
                                clusterSlide = 0f
                                settleOffset.snapTo(0f)
                                isSettling = false
                            }
                    }
                }.semantics {
                    contentDescription = description
                    onClick {
                        onOpen()
                        true
                    }
                    val actions = mutableListOf<CustomAccessibilityAction>()
                    if (canGoPrevious) {
                        actions +=
                            CustomAccessibilityAction("Previous project") {
                                startSwitch(SessionListScopePaging.Direction.Previous, from = 0f)
                                true
                            }
                    }
                    if (canGoNext) {
                        actions +=
                            CustomAccessibilityAction("Next project") {
                                startSwitch(SessionListScopePaging.Direction.Next, from = 0f)
                                true
                            }
                    }
                    if (actions.isNotEmpty()) {
                        customActions = actions
                    }
                }.padding(horizontal = 10.dp, vertical = 8.dp)
                .heightIn(min = 44.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .align(Alignment.CenterStart)
                    .clipToBounds()
                    .horizontalEdgeAlphaFade(fadeWidthPx)
                    .onSizeChanged { slotWidthPx = max(it.width.toFloat(), 1f) },
            contentAlignment = Alignment.CenterStart,
        ) {
            outgoingTab?.let { tab ->
                SessionListScopeIdentity(
                    tab = tab,
                    modifier = Modifier.offset { IntOffset(outgoingValue.roundToInt(), 0) },
                )
            }
            SessionListScopeIdentity(
                tab = visibleTab,
                modifier = Modifier.offset { IntOffset(displayed.roundToInt(), 0) },
            )
        }
        Icon(
            imageVector = OpenCodeIcons.KeyboardArrowDown,
            contentDescription = null,
            tint = textWeak,
            modifier =
                Modifier
                    .size(16.dp)
                    .align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun SessionListScopeIdentity(
    tab: SessionListFilterTab,
    modifier: Modifier = Modifier,
) {
    val textStrong = openCodeColor("text-strong")
    val textWeak = openCodeColor("text-weak")
    val subtitle = scopeControlSubtitle(tab)
    Row(
        modifier = modifier.padding(end = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SessionListScopeMark(tab = tab)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tab.pickerTitle,
                color = textStrong,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    color = textWeak,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SessionListScopeTrailing(tab = tab)
    }
}

/**
 * Trailing mark for a scope: its session count, a small spinner while its first load runs, or
 * nothing when that load failed (the subtitle says so).
 */
@Composable
fun SessionListScopeTrailing(
    tab: SessionListFilterTab,
    modifier: Modifier = Modifier,
) {
    when (tab.load) {
        is SessionListScopeLoad.Loading ->
            CircularProgressIndicator(
                modifier = modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = openCodeColor("text-base"),
                trackColor = openCodeColor("border-weaker-base"),
            )
        SessionListScopeLoad.Failed -> Unit
        null -> SessionListScopeCountBadge(count = tab.count, modifier = modifier)
    }
}

@Composable
fun SessionListScopeMark(
    tab: SessionListFilterTab,
    modifier: Modifier = Modifier,
) {
    val letter = tab.letter
    val accent = tab.letterAccent
    if (letter != null && accent != null) {
        val dark = isSystemInDarkTheme()
        val (fill, tint) = accent.badgeColors(dark)
        Box(
            modifier =
                modifier
                    .size(32.dp)
                    .background(fill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = letter.toString(),
                color = tint,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    } else {
        val textStrong = openCodeColor("text-strong")
        Box(
            modifier =
                modifier
                    .size(32.dp)
                    .background(textStrong.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = OpenCodeIcons.Widgets,
                contentDescription = null,
                tint = textStrong,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
fun SessionListScopeCountBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    val textStrong = openCodeColor("text-strong")
    Text(
        text = count.toString(),
        color = textStrong,
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        modifier =
            modifier
                .background(textStrong.copy(alpha = 0.10f), RoundedCornerShape(percent = 50))
                .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * Project picker rows. Tap selects a scope; long-press on a project row lifts
 * it for drag reordering when [onReorderProjects] is set. Swiping a project row
 * left reveals Remove, which calls [onRemoveProject]. The "All" row stays
 * pinned first and neither reorders nor reveals.
 */
@Composable
fun SessionListProjectPickerRows(
    tabs: List<SessionListFilterTab>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRemoveProject: ((String) -> Unit)? = null,
    onReorderProjects: ((List<String>) -> Unit)? = null,
) {
    OpenCodeReorderableList(
        items = tabs,
        key = { it.id },
        onSelect = { onSelect(it.id) },
        canMove = { !it.isAll },
        onMove =
            onReorderProjects?.let { reorder ->
                { from, to -> reorder(reorderedProjectPickerWorktrees(tabs, from, to)) }
            },
        onContextAction = onRemoveProject?.let { remove -> { tab -> remove(tab.id) } },
        supportsContextAction = { !it.isAll },
        modifier = modifier,
    ) { tab ->
        OpenCodeListItem(
            title = tab.pickerTitle,
            subtitle = displayedScopeSubtitle(tab),
            startDecorator = pickerStartDecorator(tab),
            trailing = { SessionListScopeTrailing(tab = tab) },
        )
    }
}

fun reorderedProjectPickerWorktrees(
    tabs: List<SessionListFilterTab>,
    from: Int,
    to: Int,
): List<String> =
    ReorderableListMath
        .move(tabs, from, to)
        .filterNot { it.isAll }
        .map { it.id }

private fun displayedScopeSubtitle(tab: SessionListFilterTab): String = if (tab.isAll) tab.subtitle else compactHomePath(tab.subtitle)

/** Scope control subtitle: load progress while the scope's first load is unfinished, else the path. */
private fun scopeControlSubtitle(tab: SessionListFilterTab): String =
    when (val load = tab.load) {
        is SessionListScopeLoad.Loading ->
            if (load.isSlow) SessionListScopeLoad.SLOW_SUBTITLE else SessionListScopeLoad.LOADING_SUBTITLE
        SessionListScopeLoad.Failed -> SessionListScopeLoad.FAILED_SUBTITLE
        null -> displayedScopeSubtitle(tab)
    }

internal fun scopeControlDescription(tab: SessionListFilterTab): String {
    val subtitle = scopeControlSubtitle(tab)
    val parts =
        buildList {
            add(tab.pickerTitle)
            if (subtitle.isNotEmpty()) add(subtitle)
            if (tab.load == null) add("${tab.count} sessions")
        }
    return parts.joinToString(", ")
}

private fun Modifier.horizontalEdgeAlphaFade(fadeWidthPx: Float): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (fadeWidthPx <= 0.5f || size.width <= 0f) return@drawWithContent
            val t = (fadeWidthPx / size.width).coerceIn(0f, 0.49f)
            drawRect(
                brush =
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        t to Color.White,
                        1f - t to Color.White,
                        1f to Color.Transparent,
                    ),
                blendMode = BlendMode.DstIn,
            )
        }

private fun pickerStartDecorator(tab: SessionListFilterTab): StartDecorator {
    val letter = tab.letter
    val accent = tab.letterAccent
    return if (letter != null && accent != null) {
        StartDecorator.Letter(letter, accent)
    } else {
        StartDecorator.IconCircle(OpenCodeIcons.Widgets, null)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockCta(
    onClick: () -> Unit,
    fill: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val interaction =
        if (onLongClick != null) {
            Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
                onLongClickLabel = onLongClickLabel,
                role = Role.Button,
            )
        } else {
            Modifier.clickable(role = Role.Button, onClick = onClick)
        }
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(fill)
                .then(interaction),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionListDockPreview() {
    OpenCodeTheme(darkTheme = true) {
        Box(modifier = Modifier.padding(16.dp)) {
            OpenCodeSessionListDock(
                filterTabs =
                    listOf(
                        SessionListFilterTab.all(projectCount = 3, sessionCount = 4),
                        SessionListFilterTab.project(
                            worktree = "/a",
                            title = "opencode-mobile",
                            count = 2,
                        ),
                        SessionListFilterTab.project(worktree = "/b", title = "playground", count = 1),
                    ),
                selectedFilterId = SessionListFilterTab.ALL_ID,
                onAddProject = {},
                onNewSession = {},
            )
        }
    }
}
