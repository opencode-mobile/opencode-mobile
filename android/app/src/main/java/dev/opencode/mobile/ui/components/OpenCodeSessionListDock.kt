package dev.opencode.mobile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val NewSessionButtonShape = RoundedCornerShape(percent = 50)
private val ScopeControlShape = RoundedCornerShape(14.dp)

@Composable
fun OpenCodeSessionListDock(
    filterTabs: List<SessionListFilterTab>,
    selectedFilterId: String,
    onAddProject: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
    createError: String? = null,
    useProjectPicker: Boolean = false,
    onOpenProjectPicker: () -> Unit = {},
    onFilterSelected: (String) -> Unit = {},
    onRemoveProject: ((String) -> Unit)? = null,
) {
    val textStrong = openCodeColor("text-strong")
    val error = openCodeColor("error")
    val iconStrong = openCodeColor("icon-strong-base")
    val ctaFill = textStrong.copy(alpha = 0.14f)
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

            if (useProjectPicker && filterTabs.isNotEmpty()) {
                SessionListScopeControl(
                    tabs = filterTabs,
                    selectedId = selectedFilterId,
                    onSelect = onFilterSelected,
                    onOpen = onOpenProjectPicker,
                )
            } else if (filterTabs.isNotEmpty()) {
                SessionListFilterChipRow(
                    filterTabs = filterTabs,
                    selectedFilterId = selectedFilterId,
                    onFilterSelected = onFilterSelected,
                    onRemoveProject = onRemoveProject,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DockCta(
                    onClick = onAddProject,
                    fill = ctaFill,
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
                    fill = ctaFill,
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
private fun SessionListFilterChipRow(
    filterTabs: List<SessionListFilterTab>,
    selectedFilterId: String,
    onFilterSelected: (String) -> Unit,
    onRemoveProject: ((String) -> Unit)?,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        filterTabs.forEach { tab ->
            val selected = tab.id == selectedFilterId
            val showRemove =
                tab.id != SessionListFilterTab.ALL_ID &&
                    selected &&
                    onRemoveProject != null
            val bringIntoViewRequester = remember(tab.id) { BringIntoViewRequester() }
            LaunchedEffect(selected, showRemove) {
                if (selected) {
                    bringIntoViewRequester.bringIntoView()
                }
            }
            OpenCodeFilterChip(
                title = tab.title,
                count = tab.count,
                selected = selected,
                onClick = { onFilterSelected(tab.id) },
                onRemove =
                    if (showRemove) {
                        onRemoveProject?.let { remove -> { remove(tab.id) } }
                    } else {
                        null
                    },
                modifier = Modifier.bringIntoViewRequester(bringIntoViewRequester),
            )
        }
    }
}

@Composable
fun SessionListScopeControl(
    tabs: List<SessionListFilterTab>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStrong = openCodeColor("text-strong")
    val textWeak = openCodeColor("text-weak")
    val selectedIndex = tabs.indexOfFirst { it.id == selectedId }.let { if (it >= 0) it else 0 }
    val selectedTab = tabs.getOrNull(selectedIndex) ?: return
    val previousTab =
        SessionListScopePaging
            .neighborIndex(selectedIndex, tabs.size, SessionListScopePaging.Direction.Previous)
            ?.let { tabs[it] }
    val nextTab =
        SessionListScopePaging
            .neighborIndex(selectedIndex, tabs.size, SessionListScopePaging.Direction.Next)
            ?.let { tabs[it] }
    val subtitle = displayedScopeSubtitle(selectedTab)
    val description =
        if (subtitle.isEmpty()) {
            "${selectedTab.pickerTitle}, ${selectedTab.count} sessions"
        } else {
            "${selectedTab.pickerTitle}, $subtitle, ${selectedTab.count} sessions"
        }
    val dragOffset = remember { Animatable(0f) }
    val dragOffsetValue by dragOffset.asState()
    var pageWidthPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(selectedId) {
        if (!dragOffset.isRunning) {
            dragOffset.snapTo(0f)
        }
    }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(ScopeControlShape)
                .background(textStrong.copy(alpha = 0.08f))
                .pointerInput(selectedId, tabs, pageWidthPx) {
                    val velocityTracker = VelocityTracker()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        velocityTracker.resetTracking()
                        velocityTracker.addPosition(down.uptimeMillis, down.position)
                        var dragged = false
                        var total = dragOffset.value
                        val slop = viewConfiguration.touchSlop
                        drag(down.id) { change ->
                            val delta = change.positionChange().x
                            total += delta
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            if (abs(total) > slop) {
                                dragged = true
                            }
                            change.consume()
                            scope.launch { dragOffset.snapTo(total) }
                        }
                        if (!dragged) {
                            onOpen()
                            return@awaitEachGesture
                        }
                        val translationDp = total / density.density
                        val velocityDp = velocityTracker.calculateVelocity().x / density.density
                        val direction =
                            SessionListScopePaging.commitDirection(
                                translation = translationDp,
                                velocity = velocityDp,
                                canGoPrevious = previousTab != null,
                                canGoNext = nextTab != null,
                            )
                        val neighborIndex =
                            direction?.let {
                                SessionListScopePaging.neighborIndex(
                                    selectedIndex = selectedIndex,
                                    count = tabs.size,
                                    direction = it,
                                )
                            }
                        scope.launch {
                            if (direction != null && neighborIndex != null) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val width = pageWidthPx.coerceAtLeast(1f)
                                val compensated =
                                    total +
                                        if (direction == SessionListScopePaging.Direction.Next) {
                                            width
                                        } else {
                                            -width
                                        }
                                dragOffset.snapTo(compensated)
                                onSelect(tabs[neighborIndex].id)
                                dragOffset.animateTo(
                                    0f,
                                    tween(
                                        SessionListScopePaging.SETTLE_DURATION_MS,
                                        easing = FastOutSlowInEasing,
                                    ),
                                )
                            } else {
                                dragOffset.animateTo(
                                    0f,
                                    tween(
                                        SessionListScopePaging.SETTLE_DURATION_MS,
                                        easing = FastOutSlowInEasing,
                                    ),
                                )
                            }
                        }
                    }
                }
                .semantics {
                    contentDescription = description
                    onClick {
                        onOpen()
                        true
                    }
                    customActions =
                        listOfNotNull(
                            previousTab?.let { tab ->
                                CustomAccessibilityAction("Previous project") {
                                    onSelect(tab.id)
                                    true
                                }
                            },
                            nextTab?.let { tab ->
                                CustomAccessibilityAction("Next project") {
                                    onSelect(tab.id)
                                    true
                                }
                            },
                        )
                }
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp)
                    .clipToBounds()
                    .onSizeChanged { pageWidthPx = it.width.toFloat() }
                    .scopeClusterFade(),
        ) {
            val displayed =
                SessionListScopePaging.displayedTranslation(
                    translation = dragOffsetValue,
                    pageWidth = pageWidthPx.coerceAtLeast(1f),
                    canGoPrevious = previousTab != null,
                    canGoNext = nextTab != null,
                )
            val baseOffset = if (previousTab == null) 0f else -pageWidthPx
            Row(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .offset { IntOffset((baseOffset + displayed).roundToInt(), 0) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                previousTab?.let { tab ->
                    SessionListScopeIdentity(
                        tab = tab,
                        modifier = Modifier.width(with(density) { pageWidthPx.toDp() }),
                    )
                }
                SessionListScopeIdentity(
                    tab = selectedTab,
                    modifier = Modifier.width(with(density) { pageWidthPx.toDp() }),
                )
                nextTab?.let { tab ->
                    SessionListScopeIdentity(
                        tab = tab,
                        modifier = Modifier.width(with(density) { pageWidthPx.toDp() }),
                    )
                }
            }
        }
        Icon(
            imageVector = OpenCodeIcons.KeyboardArrowDown,
            contentDescription = null,
            tint = textWeak,
            modifier = Modifier.size(16.dp),
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
    val subtitle = displayedScopeSubtitle(tab)
    Row(
        modifier = modifier,
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
        SessionListScopeCountBadge(count = tab.count)
    }
}

private fun Modifier.scopeClusterFade(): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                brush =
                    Brush.horizontalGradient(
                        0f to Color.Black,
                        0.86f to Color.Black,
                        1f to Color.Transparent,
                    ),
                blendMode = BlendMode.DstIn,
            )
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

@Composable
fun SessionListProjectPickerRows(
    tabs: List<SessionListFilterTab>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRemoveProject: ((String) -> Unit)? = null,
) {
    tabs.forEach { tab ->
        OpenCodeListItem(
            title = tab.pickerTitle,
            subtitle = displayedScopeSubtitle(tab),
            startDecorator = pickerStartDecorator(tab),
            onClick = { onSelect(tab.id) },
            onLongPress =
                if (tab.isAll) {
                    null
                } else {
                    onRemoveProject?.let { remove -> { remove(tab.id) } }
                },
            trailing = { SessionListScopeCountBadge(count = tab.count) },
            modifier = modifier,
        )
    }
}

private fun displayedScopeSubtitle(tab: SessionListFilterTab): String = if (tab.isAll) tab.subtitle else compactHomePath(tab.subtitle)

private fun pickerStartDecorator(tab: SessionListFilterTab): StartDecorator {
    val letter = tab.letter
    val accent = tab.letterAccent
    return if (letter != null && accent != null) {
        StartDecorator.Letter(letter, accent)
    } else {
        StartDecorator.IconCircle(OpenCodeIcons.Widgets, null)
    }
}

@Composable
private fun DockCta(
    onClick: () -> Unit,
    fill: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(fill)
                .clickable(role = Role.Button, onClick = onClick),
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
