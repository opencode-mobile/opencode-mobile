package dev.opencode.mobile.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.home.HomeSessionLadder
import dev.opencode.mobile.domain.home.HomeSessionLadderKind
import dev.opencode.mobile.domain.home.HomeSessionLadderSection
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
                isAttention = {
                    it.status == SessionListStatus.Running ||
                        it.status == SessionListStatus.Retrying ||
                        it.status == SessionListStatus.Ready
                },
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
                                showDivider = index < section.items.lastIndex,
                                borderWeaker = borderWeaker,
                                selected = session.id == selectedSessionId,
                                onSessionSelected = onSessionSelected,
                                onSessionLongPress = onSessionLongPress,
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
                                showDivider = index < section.items.lastIndex,
                                borderWeaker = borderWeaker,
                                selected = session.id == selectedSessionId,
                                onSessionSelected = onSessionSelected,
                                onSessionLongPress = onSessionLongPress,
                            )
                        }
                    }
                }
            }
        }
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
) {
    val status = session.status.toListItemStatus(session.retryAttempt, stale = session.statusStale)
    OpenCodeListItem(
        title = session.title,
        subtitle = compactHomePath(session.subtitle),
        status = status,
        caption = session.caption.takeIf { it.isNotEmpty() },
        startDecorator =
            StartDecorator.Letter(
                character = session.letter,
                accent = session.letterAccent,
                statusDot = if (showStatusDot) status.tone else null,
            ),
        selected = selected,
        onClick = { onSessionSelected(session) },
        onLongPress = onSessionLongPress?.let { handler -> { handler(session) } },
    )
    if (showDivider) {
        HorizontalDivider(
            modifier = Modifier.padding(start = 60.dp),
            color = borderWeaker,
        )
    }
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
                    isAttention = {
                        it.status == SessionListStatus.Running ||
                            it.status == SessionListStatus.Retrying ||
                            it.status == SessionListStatus.Ready
                    },
                    isReady = { it.status == SessionListStatus.Ready },
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
