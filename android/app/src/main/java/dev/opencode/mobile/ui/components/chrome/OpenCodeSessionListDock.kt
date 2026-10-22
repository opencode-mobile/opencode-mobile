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
import androidx.compose.ui.platform.testTag
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

/** New session's hold action: one gesture for the project choice and Run in. */
const val CHOOSE_PROJECT_OR_WORKTREE_LABEL = "Choose project or worktree"
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
    /** Scopes the dock can show but does not swipe through (worktrees of a project). */
    scopeTabs: List<SessionListFilterTab> = emptyList(),
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
                    scopeTabs = scopeTabs,
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
                    onLongClickLabel = if (onNewSessionLongPress != null) CHOOSE_PROJECT_OR_WORKTREE_LABEL else null,
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
    /** Scopes shown while selected but skipped by the swipe; each sits inside its [SessionListFilterTab.parentId]. */
    scopeTabs: List<SessionListFilterTab> = emptyList(),
) {
    val textWeak = openCodeColor("text-weak")
    var shownTabId by remember { mutableStateOf(selectedId) }
    val visibleTab =
        tabs.firstOrNull { it.id == shownTabId }
            ?: scopeTabs.firstOrNull { it.id == shownTabId }
            ?: tabs.firstOrNull()
            ?: return
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
    val latestVisibleTab by rememberUpdatedState(visibleTab)
    val rubberBandLimitPx =
        with(density) { SessionListScopePaging.RUBBER_BAND_LIMIT_DP.dp.toPx() }
    val switchThresholdPx =
        with(density) { SessionListScopePaging.SWITCH_THRESHOLD_DP.dp.toPx() }
    val canGoPrevious =
        SessionListScopePaging.neighborIndex(
            tabs = tabs,
            selected = visibleTab,
            direction = SessionListScopePaging.Direction.Previous,
        ) != null
    val canGoNext =
        SessionListScopePaging.neighborIndex(
            tabs = tabs,
            selected = visibleTab,
            direction = SessionListScopePaging.Direction.Next,
        ) != null
    val latestCanGoPrevious by rememberUpdatedState(canGoPrevious)
    val latestCanGoNext by rememberUpdatedState(canGoNext)
    val switchEasing = CubicBezierEasing(0.45f, 0.0f, 0.12f, 1.0f)

    fun neighborTab(direction: SessionListScopePaging.Direction): SessionListFilterTab? {
        val currentTabs = latestTabs
        val index =
            SessionListScopePaging.neighborIndex(
                tabs = currentTabs,
                selected = latestVisibleTab,
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
                text = scopeControlTitle(tab),
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

/** One row of the project picker. */
sealed interface SessionListProjectPickerRow {
    val key: String

    /** Guidance at the top of the list (the reorder hint while editing). */
    data class Note(
        val text: String,
    ) : SessionListProjectPickerRow {
        override val key: String get() = "note"
    }

    /**
     * The All row or a project. A project with [worktreeCount] above one shows the worktrees
     * pill, pointing up while [expanded].
     */
    data class Scope(
        val tab: SessionListFilterTab,
        val worktreeCount: Int = 0,
        val expanded: Boolean = false,
    ) : SessionListProjectPickerRow {
        override val key: String get() = tab.id
        val showsWorktreesPill: Boolean get() = !tab.isAll && worktreeCount > 1
    }

    /** A worktree of the project above, indented under it. */
    data class Worktree(
        val tab: SessionListFilterTab,
    ) : SessionListProjectPickerRow {
        override val key: String get() = tab.id
    }

    /** "Show all N" / "Show fewer" under an expanded project with many worktrees. */
    data class ShowAll(
        val projectId: String,
        val title: String,
    ) : SessionListProjectPickerRow {
        override val key: String get() = "showAll:$projectId"
    }
}

/**
 * Project picker rows: All, projects, and the worktrees of an expanded project. Tap selects a
 * scope; a project's worktrees pill calls [onToggleWorktrees]; "Show all" calls
 * [onToggleShowAll]. While [isEditing], project rows show drag handles and reorder by
 * dragging them (tapping one does nothing) and the pills stay visible but disabled. Outside
 * edit, rows only reorder with [reorderOnLongPress]. Swiping a project row left reveals Remove
 * in both modes. All, worktree, and Show all rows never move or reveal.
 */
@Composable
fun SessionListProjectPickerRows(
    rows: List<SessionListProjectPickerRow>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    isEditing: Boolean = false,
    reorderOnLongPress: Boolean = false,
    onToggleWorktrees: (String) -> Unit = {},
    onToggleShowAll: (String) -> Unit = {},
    onRemoveProject: ((String) -> Unit)? = null,
    onReorderProjects: ((List<String>) -> Unit)? = null,
) {
    val reorders = onReorderProjects != null && (isEditing || reorderOnLongPress)
    OpenCodeReorderableList(
        items = rows,
        key = { it.key },
        onSelect = { row ->
            when (row) {
                is SessionListProjectPickerRow.Scope -> if (!isEditing || row.tab.isAll) onSelect(row.tab.id)
                is SessionListProjectPickerRow.Worktree -> onSelect(row.tab.id)
                is SessionListProjectPickerRow.ShowAll -> onToggleShowAll(row.projectId)
                is SessionListProjectPickerRow.Note -> Unit
            }
        },
        canMove = { it.isProjectRow },
        onMove =
            onReorderProjects?.takeIf { reorders }?.let { reorder ->
                { from, to -> reorder(reorderedProjectPickerProjectIds(rows, from, to)) }
            },
        onContextAction =
            onRemoveProject?.let { remove ->
                { row -> (row as? SessionListProjectPickerRow.Scope)?.let { remove(it.tab.id) } }
            },
        supportsContextAction = { it.isProjectRow },
        modifier = modifier,
    ) { row ->
        when (row) {
            is SessionListProjectPickerRow.Note ->
                Text(
                    text = row.text,
                    color = openCodeColor("text-weak"),
                    style = MaterialTheme.typography.bodySmall,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag(SessionListProjectPickerTags.REORDER_NOTE),
                )
            is SessionListProjectPickerRow.Scope -> {
                val tab = row.tab
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isEditing && !tab.isAll) {
                        DragHandle(
                            modifier =
                                Modifier
                                    .padding(start = 4.dp)
                                    .testTag(SessionListProjectPickerTags.dragHandle(tab.title)),
                        )
                    }
                    OpenCodeListItem(
                        title = tab.pickerTitle,
                        subtitle = displayedScopeSubtitle(tab),
                        startDecorator = pickerStartDecorator(tab),
                        trailing = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (row.showsWorktreesPill) {
                                    SessionListWorktreesPill(
                                        count = row.worktreeCount,
                                        expanded = row.expanded,
                                        enabled = !isEditing,
                                        contentDescription =
                                            if (row.expanded) {
                                                "Hide worktrees of ${tab.title}"
                                            } else {
                                                "Show worktrees of ${tab.title}"
                                            },
                                        onClick = { onToggleWorktrees(tab.id) },
                                        modifier = Modifier.testTag(SessionListProjectPickerTags.worktreesPill(tab.title)),
                                    )
                                }
                                SessionListScopeTrailing(tab = tab)
                            }
                        },
                        modifier =
                            Modifier
                                .weight(1f)
                                .testTag(SessionListProjectPickerTags.scopeRow(tab.pickerTitle)),
                    )
                }
            }
            is SessionListProjectPickerRow.Worktree ->
                OpenCodeListItem(
                    title = row.tab.pickerTitle,
                    subtitle = displayedScopeSubtitle(row.tab),
                    startDecorator = pickerStartDecorator(row.tab),
                    trailing = { SessionListScopeTrailing(tab = row.tab) },
                    // A project's worktrees sit indented directly under it.
                    modifier =
                        Modifier
                            .padding(start = WorktreeRowIndent)
                            .testTag(SessionListProjectPickerTags.worktreeRow(row.tab.title)),
                )
            is SessionListProjectPickerRow.ShowAll ->
                Text(
                    text = row.title,
                    color = openCodeColor("text-interactive-base"),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .padding(start = WorktreeRowIndent + 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
                            .testTag(SessionListProjectPickerTags.SHOW_ALL),
                )
        }
    }
}

/**
 * Picker rows for plain [tabs] (galleries and older callers): projects reorder with a
 * long-press, and worktree tabs sit indented under their project.
 */
@Composable
fun SessionListProjectPickerRows(
    tabs: List<SessionListFilterTab>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRemoveProject: ((String) -> Unit)? = null,
    onReorderProjects: ((List<String>) -> Unit)? = null,
) {
    SessionListProjectPickerRows(
        rows =
            tabs.map { tab ->
                if (tab.isWorktree) SessionListProjectPickerRow.Worktree(tab) else SessionListProjectPickerRow.Scope(tab)
            },
        onSelect = onSelect,
        modifier = modifier,
        reorderOnLongPress = true,
        onRemoveProject = onRemoveProject,
        onReorderProjects = onReorderProjects,
    )
}

/** Stable test tags of the project picker (UI tests and real-app evidence). */
object SessionListProjectPickerTags {
    const val EDIT = "projectPickerEdit"
    const val REORDER_NOTE = "projectPickerReorderNote"
    const val SHOW_ALL = "projectPickerShowAll"

    fun worktreesPill(projectTitle: String): String = "projectPickerWorktreesPill:$projectTitle"

    fun dragHandle(projectTitle: String): String = "projectPickerDragHandle:$projectTitle"

    fun scopeRow(title: String): String = "projectPickerRow:$title"

    fun worktreeRow(title: String): String = "projectPickerWorktreeRow:$title"
}

private val SessionListProjectPickerRow.isProjectRow: Boolean
    get() = this is SessionListProjectPickerRow.Scope && !tab.isAll

/** Project ids in their new order after moving row [from] to [to]; worktree and other rows drop out. */
fun reorderedProjectPickerProjectIds(
    rows: List<SessionListProjectPickerRow>,
    from: Int,
    to: Int,
): List<String> =
    ReorderableListMath
        .move(rows, from, to)
        .filter { it.isProjectRow }
        .map { it.key }

fun reorderedProjectPickerWorktrees(
    tabs: List<SessionListFilterTab>,
    from: Int,
    to: Int,
): List<String> =
    ReorderableListMath
        .move(tabs, from, to)
        .filterNot { it.isAll || it.isWorktree }
        .map { it.id }

/**
 * Trailing "N worktrees" pill of a project row: its own tap target that shows or hides the
 * project's worktrees. The chevron points up while [expanded]. Disabled, it stays visible.
 */
@Composable
fun SessionListWorktreesPill(
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val textStrong = openCodeColor("text-strong")
    val contentColor = if (enabled) openCodeColor("text-base") else openCodeColor("text-weak")
    val rotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "worktreesPillChevron")
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(textStrong.copy(alpha = if (enabled) 0.08f else 0.04f))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { this.contentDescription = contentDescription }
                .heightIn(min = 28.dp)
                .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$count worktrees",
            color = contentColor,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        Icon(
            imageVector = OpenCodeIcons.KeyboardArrowDown,
            contentDescription = null,
            tint = contentColor,
            modifier =
                Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = rotation },
        )
    }
}

private val WorktreeRowIndent = 28.dp

private fun displayedScopeSubtitle(tab: SessionListFilterTab): String = if (tab.isAll) tab.subtitle else compactHomePath(tab.subtitle)

/** Scope control title: a worktree scope shows its project. */
private fun scopeControlTitle(tab: SessionListFilterTab): String = tab.dockTitle ?: tab.pickerTitle

/** Scope control subtitle: load progress while the scope's first load is unfinished, else the path. */
private fun scopeControlSubtitle(tab: SessionListFilterTab): String =
    when (val load = tab.load) {
        is SessionListScopeLoad.Loading ->
            if (load.isSlow) SessionListScopeLoad.SLOW_SUBTITLE else SessionListScopeLoad.LOADING_SUBTITLE
        SessionListScopeLoad.Failed -> SessionListScopeLoad.FAILED_SUBTITLE
        null -> tab.dockSubtitle?.let(::compactHomePath) ?: displayedScopeSubtitle(tab)
    }

internal fun scopeControlDescription(tab: SessionListFilterTab): String {
    val subtitle = scopeControlSubtitle(tab)
    val parts =
        buildList {
            add(scopeControlTitle(tab))
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
    if (tab.isWorktree) return StartDecorator.Icon(OpenCodeIcons.CallSplit, null)
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
