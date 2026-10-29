package dev.opencode.mobile.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
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
import dev.opencode.mobile.domain.home.HomeSessionLadder
import dev.opencode.mobile.domain.home.HomeSessionLadderKind
import dev.opencode.mobile.domain.home.HomeSessionLadderSection
import dev.opencode.mobile.domain.home.HomeSessionOrderStabilizer
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.preferences.HomeSessionRowStyle
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import kotlinx.coroutines.delay

private val SessionListFallbackBottomSpacer = 168.dp
private const val SCOPE_LOAD_CROSSFADE_MS = 220

enum class SessionListStatus {
    Idle,
    Running,
    Retrying,
    Ready,

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

    /** Rows that belong in the attention band: live work or unseen finished work. */
    val needsAttention: Boolean
        get() = this == Running || this == Retrying || isUnreadFinish
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
    /**
     * Directory the session runs in. [worktree] stays the project scope id so project
     * filters and counts keep grouping worktree sessions with that project.
     */
    val directory: String = worktree,
    /** Scope id of the session's worktree when its project lists more than one. */
    val worktreeScopeId: String? = null,
    /** Branch and worktree folder for a repository project; null keeps the [subtitle] path. */
    val location: List<SessionListLocationPart>? = null,
    /** Project name shown on the line above [location]. */
    val projectName: String? = null,
    /** Worktree part of the compact row's `project / worktree`; null on the main checkout. */
    val compactWorktree: String? = null,
) {
    /** Line 2 of the compact row: the project name (else the folder), then [compactWorktree]. */
    fun compactPath(): OpenCodeListItemPath {
        val project =
            projectName?.takeIf { it.isNotEmpty() }
                ?: subtitle.trimEnd('/').substringAfterLast('/').ifEmpty { subtitle }
        return OpenCodeListItemPath(project = project, worktree = compactWorktree?.takeIf { it.isNotEmpty() })
    }

    /**
     * Git project rows: the project name (folder icon) on the subtitle line, and the worktree
     * (branch icon, then folder) on the detail line under it, in every scope. Each line cuts on
     * its own, so a long project name never hides the branch. Null keeps the path subtitle.
     */
    fun locationLines(): SessionListLocationLines? {
        val parts = location ?: return null
        val name =
            projectName?.takeIf { it.isNotEmpty() }
                ?: return SessionListLocationLines(parts.toSubtitleSegments(), detail = null)
        val worktree = parts.toSubtitleSegments(OpenCodeListItemSubtitleTone.Tertiary)
        return SessionListLocationLines(
            subtitle = listOf(OpenCodeListItemSubtitleSegment(name, OpenCodeListItemSubtitleTone.Secondary, OpenCodeIcons.Folder)),
            detail = worktree.ifEmpty { null },
        )
    }
}

/** Row lines of a session on Home. */
data class SessionListLocationLines(
    val subtitle: List<OpenCodeListItemSubtitleSegment>,
    val detail: List<OpenCodeListItemSubtitleSegment>?,
)

/** One part of a row's location subtitle; parts read joined with " · ". */
data class SessionListLocationPart(
    val text: String,
    /** Drawn with the branch mark. */
    val isBranch: Boolean = false,
)

/** First load of a project scope's sessions that has not finished yet. */
sealed interface SessionListScopeLoad {
    /** Loading; [isSlow] once it has taken long enough to say so. */
    data class Loading(
        val isSlow: Boolean = false,
    ) : SessionListScopeLoad

    /** The server did not return the scope's sessions; the list offers Retry. */
    data object Failed : SessionListScopeLoad

    companion object {
        const val LOADING_SUBTITLE = "Loading sessions…"
        const val SLOW_SUBTITLE = "Still loading…"
        const val FAILED_SUBTITLE = "Couldn't load sessions"
        const val SLOW_CAPTION = "Still loading — large projects can take a moment"
        const val FAILED_MESSAGE = "The server didn't return this project's sessions."
        const val RETRY_TITLE = "Retry"

        fun failedTitle(scopeTitle: String): String = "Couldn't load $scopeTitle"
    }
}

data class SessionListFilterTab(
    val id: String,
    val title: String,
    val count: Int,
    val subtitle: String = "",
    val letter: Char? = null,
    val letterAccent: LetterAccent? = null,
    /** Unfinished first load; the dock shows it instead of [count] and the list shows a placeholder. */
    val load: SessionListScopeLoad? = null,
    /** Set on a worktree row: the id of the project scope it narrows. */
    val parentId: String? = null,
    /** Dock title when it differs from the picker row (a worktree shows its project). */
    val dockTitle: String? = null,
    /** Dock subtitle when it differs from the picker row. */
    val dockSubtitle: String? = null,
) {
    val isAll: Boolean get() = id == ALL_ID
    val isWorktree: Boolean get() = parentId != null
    val pickerTitle: String get() = if (isAll) PICKER_ALL_TITLE else title

    companion object {
        const val ALL_ID = "all"
        const val ALL_TITLE = "All"
        const val PICKER_ALL_TITLE = "All projects"

        /** [load] is loading while any project's first list is outstanding (a server switch). */
        fun all(
            projectCount: Int,
            sessionCount: Int,
            load: SessionListScopeLoad.Loading? = null,
        ): SessionListFilterTab =
            SessionListFilterTab(
                id = ALL_ID,
                title = ALL_TITLE,
                count = sessionCount,
                subtitle = if (projectCount == 1) "1 project" else "$projectCount projects",
                load = load,
            )

        fun project(
            worktree: String,
            title: String,
            count: Int,
            load: SessionListScopeLoad? = null,
        ): SessionListFilterTab =
            SessionListFilterTab(
                id = worktree,
                title = title,
                count = count,
                subtitle = worktree,
                letter = letterCharacterForProject(title),
                letterAccent = letterAccentForWorktree(worktree),
                load = load,
            )
    }

    /**
     * Broader scope to offer when this worktree filter has no sessions. Null while the
     * project is still loading or failed, and for any scope that is not a worktree.
     * Prefers the project when another worktree has sessions, otherwise All when another
     * project does, otherwise the project so the empty worktree is not a dead end.
     */
    fun worktreeFilterClear(
        broaderTabs: List<SessionListFilterTab>,
        visibleIsEmpty: Boolean,
    ): WorktreeFilterClear? {
        if (!isWorktree || !visibleIsEmpty || load != null) return null
        val parent = parentId?.let { id -> broaderTabs.firstOrNull { it.id == id } }
        if (parent?.load != null) return null
        val all = broaderTabs.firstOrNull { it.isAll }
        return when {
            parent != null && parent.count > 0 ->
                WorktreeFilterClear(parent.id, SessionListWorktreeEmptyCopy.OTHER_WORKTREES)
            all != null && all.count > 0 ->
                WorktreeFilterClear(all.id, SessionListWorktreeEmptyCopy.OTHER_PROJECTS)
            parent != null ->
                WorktreeFilterClear(parent.id, SessionListWorktreeEmptyCopy.EVERY_WORKTREE)
            parentId != null ->
                WorktreeFilterClear(parentId, SessionListWorktreeEmptyCopy.EVERY_WORKTREE)
            all != null ->
                WorktreeFilterClear(all.id, SessionListWorktreeEmptyCopy.EVERY_PROJECT)
            else -> null
        }
    }
}

/** Scope a "Clear filter" action selects, and the line that explains why. */
data class WorktreeFilterClear(
    val filterId: String,
    val message: String,
)

object SessionListWorktreeEmptyCopy {
    const val TITLE = "No sessions in this worktree"
    const val ACTION = "Clear filter"
    const val OTHER_WORKTREES = "Clear the filter to see sessions on other worktrees."
    const val OTHER_PROJECTS = "Clear the filter to see sessions on other projects."
    const val EVERY_WORKTREE = "Clear the filter to see every worktree."
    const val EVERY_PROJECT = "Clear the filter to see every project."
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
    /** Retry for a scope whose [SessionListFilterTab.load] failed; receives the tab id. */
    onRetryScopeLoad: (String) -> Unit = {},
    /** Selectable scopes outside [filterTabs] (worktrees); the dock shows them but never swipes to them. */
    scopeTabs: List<SessionListFilterTab> = emptyList(),
    rowStyle: HomeSessionRowStyle = HomeDisplayPreferences.sessionRowStyle,
) {
    val background = openCodeColor("background-base")
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
                    scopeTab = filterTabs.firstOrNull { it.id == filterId } ?: scopeTabs.firstOrNull { it.id == filterId },
                    broaderTabs = filterTabs,
                    onFilterSelected = onFilterSelected,
                    onRetryScopeLoad = onRetryScopeLoad,
                    listBottomFadeHeight = listBottomFadeHeight,
                    background = background,
                    emptyContent = emptyContent,
                    revealNewestToken = revealNewestToken,
                    selectedSessionId = selectedSessionId,
                    onSessionSelected = onSessionSelected,
                    onSessionLongPress = onSessionLongPress,
                    onDisplayedSessionsChange =
                        onDisplayedSessionsChange.takeIf { filterId == selectedFilterId },
                    rowStyle = rowStyle,
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
                scopeTabs = scopeTabs,
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
    scopeTab: SessionListFilterTab?,
    broaderTabs: List<SessionListFilterTab>,
    onFilterSelected: (String) -> Unit,
    onRetryScopeLoad: (String) -> Unit,
    listBottomFadeHeight: Dp,
    background: androidx.compose.ui.graphics.Color,
    emptyContent: (@Composable () -> Unit)?,
    revealNewestToken: Int,
    selectedSessionId: String?,
    onSessionSelected: (SessionListSession) -> Unit,
    onSessionLongPress: ((SessionListSession) -> Unit)?,
    onDisplayedSessionsChange: ((List<SessionListSession>) -> Unit)?,
    rowStyle: HomeSessionRowStyle,
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
        scopeTab = scopeTab,
        broaderTabs = broaderTabs,
        onFilterSelected = onFilterSelected,
        onRetryScopeLoad = onRetryScopeLoad,
        ladderSections = ladderSections,
        listState = listState,
        listBottomFadeHeight = listBottomFadeHeight,
        emptyContent = emptyContent,
        selectedSessionId = selectedSessionId,
        onSessionSelected = onSessionSelected,
        onSessionLongPress = onSessionLongPress,
        rowStyle = rowStyle,
        modifier =
            Modifier
                .fillMaxSize()
                .background(background),
    )
}

@Composable
private fun SessionListFeed(
    visibleSessions: List<SessionListSession>,
    scopeTab: SessionListFilterTab?,
    broaderTabs: List<SessionListFilterTab>,
    onFilterSelected: (String) -> Unit,
    onRetryScopeLoad: (String) -> Unit,
    ladderSections: List<HomeSessionLadderSection<SessionListSession>>,
    listState: LazyListState,
    listBottomFadeHeight: Dp,
    emptyContent: (@Composable () -> Unit)?,
    selectedSessionId: String?,
    onSessionSelected: (SessionListSession) -> Unit,
    onSessionLongPress: ((SessionListSession) -> Unit)?,
    rowStyle: HomeSessionRowStyle,
    modifier: Modifier = Modifier,
) {
    // A loading or failed scope shows its placeholder only while it has no rows to show.
    val placeholder = scopeTab?.load?.takeIf { visibleSessions.isEmpty() }
    Crossfade(
        targetState = placeholder?.let { it::class },
        animationSpec = tween(durationMillis = SCOPE_LOAD_CROSSFADE_MS, easing = EaseInOut),
        modifier = modifier,
        label = "sessionListScopeLoad",
    ) { placeholderKind ->
        when {
            placeholderKind == SessionListScopeLoad.Failed::class && scopeTab != null ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(bottom = listBottomFadeHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    OpenCodeEmptyState(
                        title = SessionListScopeLoad.failedTitle(scopeTab.title),
                        message = SessionListScopeLoad.FAILED_MESSAGE,
                        primaryAction =
                            OpenCodeEmptyStateAction(
                                title = SessionListScopeLoad.RETRY_TITLE,
                                onClick = { onRetryScopeLoad(scopeTab.id) },
                            ),
                    )
                }
            placeholderKind != null ->
                OpenCodeSessionListSkeleton(
                    accent = scopeTab?.letterAccent,
                    title = scopeTab?.title,
                    caption =
                        SessionListScopeLoad.SLOW_CAPTION.takeIf {
                            (scopeTab?.load as? SessionListScopeLoad.Loading)?.isSlow == true
                        },
                    modifier = Modifier.fillMaxSize(),
                )
            else -> {
                val clear =
                    scopeTab?.worktreeFilterClear(
                        broaderTabs = broaderTabs,
                        visibleIsEmpty = visibleSessions.isEmpty(),
                    )
                if (clear != null) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(bottom = listBottomFadeHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        OpenCodeEmptyState(
                            title = SessionListWorktreeEmptyCopy.TITLE,
                            message = clear.message,
                            icon = OpenCodeIcons.CallSplit,
                            primaryAction =
                                OpenCodeEmptyStateAction(
                                    title = SessionListWorktreeEmptyCopy.ACTION,
                                    onClick = { onFilterSelected(clear.filterId) },
                                ),
                        )
                    }
                } else {
                    SessionListFeedRows(
                        visibleSessions = visibleSessions,
                        ladderSections = ladderSections,
                        listState = listState,
                        listBottomFadeHeight = listBottomFadeHeight,
                        emptyContent = emptyContent,
                        selectedSessionId = selectedSessionId,
                        onSessionSelected = onSessionSelected,
                        onSessionLongPress = onSessionLongPress,
                        rowStyle = rowStyle,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionListFeedRows(
    visibleSessions: List<SessionListSession>,
    ladderSections: List<HomeSessionLadderSection<SessionListSession>>,
    listState: LazyListState,
    listBottomFadeHeight: Dp,
    emptyContent: (@Composable () -> Unit)?,
    selectedSessionId: String?,
    onSessionSelected: (SessionListSession) -> Unit,
    onSessionLongPress: ((SessionListSession) -> Unit)?,
    rowStyle: HomeSessionRowStyle,
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
                        ) { _, session ->
                            SessionListRow(
                                session = session,
                                showStatusDot = true,
                                selected = session.id == selectedSessionId,
                                onSessionSelected = onSessionSelected,
                                onSessionLongPress = onSessionLongPress,
                                rowStyle = rowStyle,
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
                        ) { _, session ->
                            SessionListRow(
                                session = session,
                                showStatusDot = false,
                                selected = session.id == selectedSessionId,
                                onSessionSelected = onSessionSelected,
                                onSessionLongPress = onSessionLongPress,
                                rowStyle = rowStyle,
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
    selected: Boolean,
    onSessionSelected: (SessionListSession) -> Unit,
    onSessionLongPress: ((SessionListSession) -> Unit)?,
    rowStyle: HomeSessionRowStyle,
) {
    val status = session.status.toListItemStatus(session.retryAttempt, stale = session.statusStale)
    if (rowStyle == HomeSessionRowStyle.Compact) {
        SessionListCompactRow(
            session = session,
            status = status,
            selected = selected,
            onSessionSelected = onSessionSelected,
            onSessionLongPress = onSessionLongPress,
        )
        return
    }
    val lines = session.locationLines()
    OpenCodeListItem(
        title = session.title,
        subtitle = if (lines == null) compactHomePath(session.subtitle) else null,
        subtitleSegments = lines?.subtitle,
        detailSegments = lines?.detail,
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
}

/** Two-line row (#803): the same in every scope; Idle shows no dot and no status. */
@Composable
private fun SessionListCompactRow(
    session: SessionListSession,
    status: OpenCodeListItemStatus,
    selected: Boolean,
    onSessionSelected: (SessionListSession) -> Unit,
    onSessionLongPress: ((SessionListSession) -> Unit)?,
) {
    val active = session.compactStatus(status)
    OpenCodeListItem(
        title = session.title,
        variant = OpenCodeListItemVariant.Compact,
        path = session.compactPath(),
        titleEmphasized = session.status.isUnreadFinish,
        status = active,
        caption = session.caption.takeIf { it.isNotEmpty() },
        startDecorator =
            StartDecorator.Tile(
                character = session.letter,
                accent = session.letterAccent,
                statusDot = active?.tone,
                pulsing = session.status == SessionListStatus.Running && !session.statusStale,
            ),
        selected = selected,
        onClick = { onSessionSelected(session) },
        onLongPress = onSessionLongPress?.let { handler -> { handler(session) } },
    )
}

/** Compact rows show a status only when the session is not Idle. */
internal fun SessionListSession.compactStatus(
    status: OpenCodeListItemStatus = this.status.toListItemStatus(retryAttempt, stale = statusStale),
): OpenCodeListItemStatus? = status.takeIf { this.status != SessionListStatus.Idle }

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
        filter { it.worktree == selectedFilterId || it.worktreeScopeId == selectedFilterId }
    }

/** Location parts as list subtitle segments: the branch carries the branch mark, parts joined with " · ". */
internal fun List<SessionListLocationPart>.toSubtitleSegments(
    tone: OpenCodeListItemSubtitleTone = OpenCodeListItemSubtitleTone.Secondary,
): List<OpenCodeListItemSubtitleSegment> =
    flatMapIndexed { index, part ->
        listOfNotNull(
            OpenCodeListItemSubtitleSegment(LOCATION_SEPARATOR, tone).takeIf { index > 0 },
            OpenCodeListItemSubtitleSegment(
                text = part.text,
                tone = tone,
                icon = if (part.isBranch) OpenCodeIcons.CallSplit else null,
            ),
        )
    }

private const val LOCATION_SEPARATOR = " · "

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

