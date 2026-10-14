package dev.opencode.mobile.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import dev.opencode.mobile.domain.home.HomeSettleGesture
import dev.opencode.mobile.domain.home.HomeSessionLadder
import dev.opencode.mobile.domain.home.HomeSessionLadderKind
import dev.opencode.mobile.domain.home.HomeSessionLadderSection
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.domain.home.HomeSessionOrderStabilizer
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
import dev.opencode.mobile.theme.openCodeColor
import kotlinx.coroutines.delay

private val SessionListFallbackBottomSpacer = 168.dp

enum class SessionListStatus {
    Idle,
    Running,
    Retrying,
    Ready,
    Snoozed,

    /** A tool call is waiting for Allow / Deny. Outranks Running. */
    NeedsApproval,

    /** Server-tracked unread run that succeeded (v2). */
    Done,

    /** Server-tracked unread run that failed (v2). */
    Failed,

    /** Server-tracked unread run that was interrupted (v2). */
    Stopped,
    ;

    /** Finished work the user has not opened yet (local Ready or server unread). */
    val isUnreadFinish: Boolean
        get() = this == Ready || this == Done || this == Failed || this == Stopped

    /** Rows that belong in the attention band: live work, a pending approval, or unseen finished work. */
    val needsAttention: Boolean
        get() = this == Running || this == Retrying || this == NeedsApproval || isUnreadFinish
}

data class SessionListSession(
    val id: String,
    val title: String,
    val worktree: String,
    val letter: Char,
    val letterAccent: LetterAccent,
    val subtitle: String,
    val status: SessionListStatus,
    val caption: String,
    val updatedAtEpochMs: Long? = null,
    /** Retry attempt shown with the Retrying chip, e.g. "Retrying · 2". */
    val retryAttempt: Int? = null,
    /** Cached activity that has not been confirmed by REST yet. */
    val statusStale: Boolean = false,
    /** Empty hides swipe. Home fills this only when settle is available. */
    val settleGestures: List<HomeSettleGesture> = emptyList(),
    /** Settled-page rows keep the letter and caption and drop the status pill. */
    val showStatus: Boolean = true,
)

data class SessionListFilterTab(
    val id: String,
    val title: String,
    val count: Int,
    val subtitle: String = "",
    val letter: Char? = null,
    val letterAccent: LetterAccent? = null,
) {
    val isAll: Boolean get() = id == ALL_ID
    val pickerTitle: String get() = if (isAll) PICKER_ALL_TITLE else title

    companion object {
        const val ALL_ID = "all"
        const val ALL_TITLE = "All"
        const val PICKER_ALL_TITLE = "All projects"

        fun all(
            projectCount: Int,
            sessionCount: Int,
        ): SessionListFilterTab =
            SessionListFilterTab(
                id = ALL_ID,
                title = ALL_TITLE,
                count = sessionCount,
                subtitle = if (projectCount == 1) "1 project" else "$projectCount projects",
            )

        fun project(
            worktree: String,
            title: String,
            count: Int,
        ): SessionListFilterTab =
            SessionListFilterTab(
                id = worktree,
                title = title,
                count = count,
                subtitle = worktree,
                letter = letterCharacterForProject(title),
                letterAccent = letterAccentForWorktree(worktree),
            )
    }
}

@Composable
fun SessionList(
    sessions: List<SessionListSession>,
    filterTabs: List<SessionListFilterTab>,
    selectedFilterId: String,
    onSessionSelected: (SessionListSession) -> Unit,
    onAddProject: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
    createError: String? = null,
    onOpenProjectPicker: () -> Unit = {},
    onFilterSelected: (String) -> Unit = {},
    emptyContent: (@Composable () -> Unit)? = null,
    onSessionLongPress: ((SessionListSession) -> Unit)? = null,
    onNewSessionLongPress: (() -> Unit)? = null,
    revealNewestToken: Int = 0,
    selectedSessionId: String? = null,
    /** Rows of [selectedFilterId] in display order (sections flattened, top to bottom). */
    onDisplayedSessionsChange: ((List<SessionListSession>) -> Unit)? = null,
    settledSessions: List<SessionListSession> = emptyList(),
    onOpenSettled: (() -> Unit)? = null,
    onSettleGesture: ((SessionListSession, HomeSettleGesture) -> Unit)? = null,
) {
    val background = openCodeColor("background-base")
    val borderWeaker = openCodeColor("border-weaker-base")
    val density = LocalDensity.current

    var dockBlockHeight by remember { mutableStateOf(0.dp) }
    val listBottomFadeHeight =
        if (dockBlockHeight > 0.dp) {
            dockBlockHeight + OpenCodeBottomFadeOverhang
        } else {
            SessionListFallbackBottomSpacer
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(background),
    ) {
        OpenCodeReadableColumn {
            Crossfade(
                targetState = selectedFilterId,
                animationSpec =
                    tween(
                        durationMillis = SessionListScopePaging.LIST_CROSSFADE_DURATION_MS,
                        easing = EaseInOut,
                    ),
                modifier = Modifier.fillMaxSize(),
                label = "sessionListFilter",
            ) { filterId ->
                SessionListFeedPage(
                    sessions = sessions,
                    filterId = filterId,
                    listBottomFadeHeight = listBottomFadeHeight,
                    borderWeaker = borderWeaker,
                    background = background,
                    emptyContent = emptyContent,
                    revealNewestToken = revealNewestToken,
                    selectedSessionId = selectedSessionId,
                    onSessionSelected = onSessionSelected,
                    onSessionLongPress = onSessionLongPress,
                    onDisplayedSessionsChange =
                        onDisplayedSessionsChange.takeIf { filterId == selectedFilterId },
                    settledSessions = settledSessions,
                    onOpenSettled = onOpenSettled,
                    onSettleGesture = onSettleGesture,
                )
            }

            OpenCodeBottomFade(
                height = listBottomFadeHeight,
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            OpenCodeSessionListDock(
                filterTabs = filterTabs,
                selectedFilterId = selectedFilterId,
                createError = createError,
                onOpenProjectPicker = onOpenProjectPicker,
                onFilterSelected = onFilterSelected,
                onAddProject = onAddProject,
                onNewSession = onNewSession,
                onNewSessionLongPress = onNewSessionLongPress,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            val height: Dp = with(density) { coordinates.size.height.toDp() }
                            if (height != dockBlockHeight) {
                                dockBlockHeight = height
                            }
                        }.navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun SessionListFeedPage(
    sessions: List<SessionListSession>,
    filterId: String,
    listBottomFadeHeight: Dp,
    borderWeaker: androidx.compose.ui.graphics.Color,
    background: androidx.compose.ui.graphics.Color,
    emptyContent: (@Composable () -> Unit)?,
    revealNewestToken: Int,
    selectedSessionId: String?,
    onSessionSelected: (SessionListSession) -> Unit,
    onSessionLongPress: ((SessionListSession) -> Unit)?,
    onDisplayedSessionsChange: ((List<SessionListSession>) -> Unit)?,
    settledSessions: List<SessionListSession> = emptyList(),
    onOpenSettled: (() -> Unit)? = null,
    onSettleGesture: ((SessionListSession, HomeSettleGesture) -> Unit)? = null,
) {
    val recentWindowHours = HomeDisplayPreferences.recentWindowHours
    val visibleSessions =
        remember(sessions, filterId) {
            sessions.visibleForFilter(filterId)
        }
    val desiredSections =
        remember(visibleSessions, recentWindowHours) {
            HomeSessionLadder.group(
                sessions = visibleSessions,
                updatedAtEpochMs = { it.updatedAtEpochMs },
                isAttention = { it.status.needsAttention },
                recentWindowHours = recentWindowHours,
                nowEpochMs = System.currentTimeMillis(),
            )
        }
    val ladderSections = rememberStabilizedLadder(filterId, desiredSections)
    val listState = rememberLazyListState()
    var followingTop by remember { mutableStateOf(true) }
    var seenIds by remember(filterId) { mutableStateOf(emptySet<String>()) }
    val displayedIds = ladderSections.flatMap { section -> section.items.map { it.id } }
    val newcomerId = displayedIds.firstOrNull { it !in seenIds }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { inProgress ->
            if (inProgress) {
                followingTop =
                    SessionListScrollPin.followingTop(
                        currentlyFollowing = followingTop,
                        isUserScrolling = true,
                        isAtTop =
                            SessionListScrollPin.isAtTop(
                                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                            ),
                    )
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            SessionListScrollPin.isAtTop(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
            )
        }.collect { atTop ->
            if (atTop) followingTop = true
        }
    }
    SideEffect {
        if (displayedIds.isNotEmpty()) {
            seenIds = seenIds + displayedIds
        }
    }
    LaunchedEffect(newcomerId) {
        if (newcomerId != null &&
            SessionListScrollPin.shouldScrollToTrueTop(
                followingTop = followingTop,
                newestIdentityChanged = true,
                forceRevealNewest = false,
            )
        ) {
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(revealNewestToken) {
        if (revealNewestToken > 0) {
            followingTop = true
            listState.scrollToItem(0)
        }
    }
    if (onDisplayedSessionsChange != null) {
        val displayedSessions = ladderSections.flatMap { section -> section.items }
        SideEffect { onDisplayedSessionsChange(displayedSessions) }
    }
    // A selection made elsewhere (keyboard stepping) scrolls its row into view. The first
    // composition keeps the restored scroll position.
    val revealedSelection = remember { SelectionRevealHold(selectedSessionId) }
    val currentLadderSections by rememberUpdatedState(ladderSections)
    LaunchedEffect(selectedSessionId) {
        val previous = revealedSelection.sessionId
        revealedSelection.sessionId = selectedSessionId
        if (selectedSessionId == null || selectedSessionId == previous) return@LaunchedEffect
        val index = sessionListItemIndex(currentLadderSections, selectedSessionId) ?: return@LaunchedEffect
        listState.revealItem(index)
        // A reveal that leaves the top stops following it, so a newcomer does not scroll the
        // selected row back out of view. The scroll-start sample cannot see this: it fires
        // while the list is still at the top.
        followingTop =
            SessionListScrollPin.isAtTop(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
            )
    }

    SessionListFeed(
        visibleSessions = visibleSessions,
        ladderSections = ladderSections,
        listState = listState,
        listBottomFadeHeight = listBottomFadeHeight,
        borderWeaker = borderWeaker,
        emptyContent = emptyContent,
        selectedSessionId = selectedSessionId,
        onSessionSelected = onSessionSelected,
        onSessionLongPress = onSessionLongPress,
        settledCount = settledSessions.visibleForFilter(filterId).size,
        onOpenSettled = onOpenSettled,
        onSettleGesture = onSettleGesture,
        modifier =
            Modifier
                .fillMaxSize()
                .background(background),
    )
}

@Composable
private fun SessionListFeed(
    visibleSessions: List<SessionListSession>,
    ladderSections: List<HomeSessionLadderSection<SessionListSession>>,
    listState: LazyListState,
    listBottomFadeHeight: Dp,
    borderWeaker: androidx.compose.ui.graphics.Color,
    emptyContent: (@Composable () -> Unit)?,
    selectedSessionId: String?,
    onSessionSelected: (SessionListSession) -> Unit,
    onSessionLongPress: ((SessionListSession) -> Unit)?,
    settledCount: Int = 0,
    onOpenSettled: (() -> Unit)? = null,
    onSettleGesture: ((SessionListSession, HomeSettleGesture) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (visibleSessions.isEmpty() && emptyContent != null) {
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(bottom = listBottomFadeHeight),
            contentAlignment = Alignment.Center,
        ) {
            emptyContent()
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = listBottomFadeHeight),
        ) {
            ladderSections.forEach { section ->
                when (section.kind) {
                    HomeSessionLadderKind.Recent -> {
                        item(key = "header-${section.id}") {
                            OpenCodeSectionHeader(title = "Recent")
                        }
                        itemsIndexed(
                            section.items,
                            key = { _, session -> "${section.id}-${session.id}" },
                        ) { index, session ->
                            SessionListRow(
                                session = session,
                                showStatusDot = true,
                                showDivider = sessionListShowsDivider(index, section.items, selectedSessionId),
                                borderWeaker = borderWeaker,
                                selected = session.id == selectedSessionId,
                                onSessionSelected = onSessionSelected,
                                onSessionLongPress = onSessionLongPress,
                                onSettleGesture = onSettleGesture,
                            )
                        }
                    }
                    HomeSessionLadderKind.Day,
                    HomeSessionLadderKind.Week,
                    -> {
                        item(key = "header-${section.id}") {
                            OpenCodeSectionHeader(title = section.title)
                        }
                        itemsIndexed(
                            section.items,
                            key = { _, session -> "${section.id}-${session.id}" },
                        ) { index, session ->
                            SessionListRow(
                                session = session,
                                showStatusDot = false,
                                showDivider = sessionListShowsDivider(index, section.items, selectedSessionId),
                                borderWeaker = borderWeaker,
                                selected = session.id == selectedSessionId,
                                onSessionSelected = onSessionSelected,
                                onSessionLongPress = onSessionLongPress,
                                onSettleGesture = onSettleGesture,
                            )
                        }
                    }
                }
            }
            if (settledCount > 0 && onOpenSettled != null) {
                item(key = "settled-link") {
                    SettledSessionsLink(count = settledCount, onClick = onOpenSettled)
                }
            }
        }
    }
}

@Composable
private fun SettledSessionsLink(
    count: Int,
    onClick: () -> Unit,
) {
    val tint = openCodeColor("text-interactive-base")
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (count == 1) "1 settled" else "$count settled",
            color = tint,
            style = MaterialTheme.typography.bodyLarge,
        )
        Icon(
            imageVector = OpenCodeIcons.ChevronRight,
            contentDescription = null,
            tint = tint,
        )
    }
}

@Composable
private fun SessionListRow(
    session: SessionListSession,
    showStatusDot: Boolean,
    showDivider: Boolean,
    borderWeaker: androidx.compose.ui.graphics.Color,
    selected: Boolean,
    onSessionSelected: (SessionListSession) -> Unit,
    onSessionLongPress: ((SessionListSession) -> Unit)?,
    onSettleGesture: ((SessionListSession, HomeSettleGesture) -> Unit)? = null,
) {
    val status = session.status.toListItemStatus(session.retryAttempt, stale = session.statusStale)
    SettleSwipeRow(
        gestures = session.settleGestures,
        onGesture = { gesture -> onSettleGesture?.invoke(session, gesture) },
    ) {
        OpenCodeListItem(
            title = session.title,
            subtitle = compactHomePath(session.subtitle).takeIf { session.subtitle.isNotBlank() },
            status = if (session.showStatus) status else null,
            caption = session.caption.takeIf { it.isNotEmpty() },
            startDecorator =
                StartDecorator.Letter(
                    character = session.letter,
                    accent = session.letterAccent,
                    statusDot = if (showStatusDot && session.showStatus) status.tone else null,
                ),
            selected = selected,
            onClick = { onSessionSelected(session) },
            onLongPress = onSessionLongPress?.let { handler -> { handler(session) } },
        )
    }
    if (showDivider) {
        HorizontalDivider(
            modifier = Modifier.padding(start = 60.dp),
            color = borderWeaker,
        )
    }
}

private class SelectionRevealHold(
    var sessionId: String?,
)

/** Scrolls the least distance that shows item [index] above the bottom content padding (the dock). */
private suspend fun LazyListState.revealItem(index: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        animateScrollToItem(index)
        return
    }
    val visibleEnd = info.viewportEndOffset - info.afterContentPadding
    val itemEnd = item.offset + item.size
    when {
        item.offset < 0 -> animateScrollBy(item.offset.toFloat())
        itemEnd > visibleEnd -> animateScrollBy(minOf(itemEnd - visibleEnd, item.offset).toFloat())
    }
}

/** LazyColumn index of [sessionId]'s row: every ladder section is a header item plus its rows. */
internal fun sessionListItemIndex(
    sections: List<HomeSessionLadderSection<SessionListSession>>,
    sessionId: String,
): Int? {
    var index = 0
    sections.forEach { section ->
        index += 1
        section.items.forEach { session ->
            if (session.id == sessionId) return index
            index += 1
        }
    }
    return null
}

private class HomeSessionOrderHold {
    var sections: List<HomeSessionLadderSection<SessionListSession>> = emptyList()
    var lastAppliedAtEpochMs: Long? = null
}

@Composable
private fun rememberStabilizedLadder(
    filterId: String,
    desired: List<HomeSessionLadderSection<SessionListSession>>,
): List<HomeSessionLadderSection<SessionListSession>> {
    val hold = remember(filterId) { HomeSessionOrderHold() }
    var flushTick by remember(filterId) { mutableIntStateOf(0) }
    val result =
        remember(desired, flushTick) {
            val next =
                HomeSessionOrderStabilizer.stabilize(
                    previousSections = hold.sections,
                    previousLastAppliedAtEpochMs = hold.lastAppliedAtEpochMs,
                    desired = desired,
                    itemId = SessionListSession::id,
                    isAttention = { it.status.needsAttention },
                    isReady = { it.status.isUnreadFinish },
                    nowEpochMs = System.currentTimeMillis(),
                )
            hold.sections = next.sections
            hold.lastAppliedAtEpochMs = next.lastAppliedAtEpochMs
            next
        }
    LaunchedEffect(result.nextApplyAtEpochMs, desired, filterId) {
        val nextAt = result.nextApplyAtEpochMs ?: return@LaunchedEffect
        val wait = nextAt - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        flushTick++
    }
    return result.sections
}

fun List<SessionListSession>.visibleForFilter(selectedFilterId: String): List<SessionListSession> =
    if (selectedFilterId == SessionListFilterTab.ALL_ID) {
        this
    } else {
        filter { it.worktree == selectedFilterId }
    }

internal fun SessionListStatus.toListItemStatus(
    retryAttempt: Int? = null,
    stale: Boolean = false,
): OpenCodeListItemStatus {
    val tone =
        if (stale) {
            OpenCodeListItemStatusTone.Stale
        } else {
            when (this) {
                SessionListStatus.Idle -> OpenCodeListItemStatusTone.Info
                SessionListStatus.Running -> OpenCodeListItemStatusTone.Interactive
                SessionListStatus.Retrying -> OpenCodeListItemStatusTone.Warning
                SessionListStatus.Ready -> OpenCodeListItemStatusTone.Success
                SessionListStatus.Snoozed -> OpenCodeListItemStatusTone.Warning
                SessionListStatus.NeedsApproval -> OpenCodeListItemStatusTone.Warning
                SessionListStatus.Done -> OpenCodeListItemStatusTone.Success
                SessionListStatus.Failed -> OpenCodeListItemStatusTone.Error
                SessionListStatus.Stopped -> OpenCodeListItemStatusTone.Warning
            }
        }
    return when (this) {
        SessionListStatus.Idle -> OpenCodeListItemStatus(text = "Idle", tone = tone)
        SessionListStatus.Running -> OpenCodeListItemStatus(text = "Running", tone = tone)
        SessionListStatus.Retrying ->
            OpenCodeListItemStatus(
                text = retryAttempt?.let { "Retrying · $it" } ?: "Retrying",
                tone = tone,
            )
        SessionListStatus.Ready -> OpenCodeListItemStatus(text = "Ready", tone = tone)
        SessionListStatus.Snoozed -> OpenCodeListItemStatus(text = "Snoozed", tone = tone)
        SessionListStatus.NeedsApproval -> OpenCodeListItemStatus(text = "Needs approval", tone = tone)
        SessionListStatus.Done -> OpenCodeListItemStatus(text = "Done", tone = tone)
        SessionListStatus.Failed -> OpenCodeListItemStatus(text = "Failed", tone = tone)
        SessionListStatus.Stopped -> OpenCodeListItemStatus(text = "Stopped", tone = tone)
    }
}

internal fun compactHomePath(path: String): String {
    val home = System.getProperty("user.home").orEmpty()
    if (home.isNotEmpty()) {
        if (path == home) return "~"
        if (path.startsWith("$home/")) return "~" + path.removePrefix(home)
    }
    if (!HomeDisplayPreferences.hideUserDirectoryPrefix) return path
    remoteUnixHomePrefix(path)?.let { remoteHome ->
        if (path == remoteHome) return "~"
        if (path.startsWith("$remoteHome/")) return "~" + path.removePrefix(remoteHome)
    }
    return path
}

/** `/Users/<name>` or `/home/<name>` prefix when the path is a remote server worktree. */
private fun remoteUnixHomePrefix(path: String): String? {
    val parts = path.split('/', limit = 4)
    if (parts.size < 3 || parts[0].isNotEmpty()) return null
    val root = parts[1]
    if (root != "Users" && root != "home") return null
    val user = parts[2]
    if (user.isEmpty()) return null
    return "/$root/$user"
}

@Preview(showBackground = true)
@Composable
private fun SessionListPreview() {
    OpenCodeTheme(darkTheme = false) {
        SessionList(
            sessions =
                listOf(
                    SessionListSession(
                        id = "1",
                        title = "Refactor Navigation",
                        worktree = "/Users/demo/opencode-mobile",
                        letter = 'O',
                        letterAccent = LetterAccent.Violet,
                        subtitle = "/Users/demo/opencode-mobile",
                        status = SessionListStatus.Running,
                        caption = "now",
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                    SessionListSession(
                        id = "2",
                        title = "Bug: Header Clipping",
                        worktree = "/Users/demo/playground",
                        letter = 'P',
                        letterAccent = LetterAccent.Teal,
                        subtitle = "/Users/demo/playground",
                        status = SessionListStatus.Idle,
                        caption = "14m ago",
                        updatedAtEpochMs = System.currentTimeMillis() - 14 * 60_000L,
                    ),
                ),
            filterTabs =
                listOf(
                    SessionListFilterTab.all(projectCount = 2, sessionCount = 2),
                    SessionListFilterTab.project(
                        worktree = "/Users/demo/opencode-mobile",
                        title = "opencode-mobile",
                        count = 1,
                    ),
                    SessionListFilterTab.project(
                        worktree = "/Users/demo/playground",
                        title = "playground",
                        count = 1,
                    ),
                ),
            selectedFilterId = SessionListFilterTab.ALL_ID,
            onSessionSelected = {},
            onAddProject = {},
            onNewSession = {},
        )
    }
}

/**
 * Whether the hairline divider after row [index] is drawn. Skipped after the last
 * row and on either side of the selected row so it never butts against the pill.
 */
internal fun sessionListShowsDivider(
    index: Int,
    items: List<SessionListSession>,
    selectedSessionId: String?,
): Boolean {
    if (index < 0 || index >= items.lastIndex) return false
    if (selectedSessionId == null) return true
    return items[index].id != selectedSessionId && items[index + 1].id != selectedSessionId
}
