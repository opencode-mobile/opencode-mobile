package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.domain.home.HomeSessionLadder
import dev.opencode.mobile.domain.home.HomeSessionLadderKind
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor

private val SessionListFallbackBottomSpacer = 168.dp

enum class SessionListStatus {
    Idle,
    Running,
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
)

data class SessionListFilterTab(
    val id: String,
    val title: String,
    val count: Int,
) {
    companion object {
        const val ALL_ID = "all"
    }
}

@Composable
fun SessionList(
    sessions: List<SessionListSession>,
    filterTabs: List<SessionListFilterTab>,
    selectedFilterId: String,
    onFilterSelected: (String) -> Unit,
    onSessionSelected: (SessionListSession) -> Unit,
    onAddProject: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
    createError: String? = null,
    emptyContent: (@Composable () -> Unit)? = null,
    onRemoveProject: ((String) -> Unit)? = null,
) {
    val recentWindowHours = HomeDisplayPreferences.recentWindowHours
    val visibleSessions =
        remember(sessions, selectedFilterId) {
            sessions.visibleForFilter(selectedFilterId)
        }
    val ladderSections =
        remember(visibleSessions, recentWindowHours) {
            HomeSessionLadder.group(
                sessions = visibleSessions,
                updatedAtEpochMs = { it.updatedAtEpochMs },
                isAttention = { it.status == SessionListStatus.Running || it.status == SessionListStatus.Ready },
                recentWindowHours = recentWindowHours,
                nowEpochMs = System.currentTimeMillis(),
            )
        }

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
        if (visibleSessions.isEmpty() && emptyContent != null) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(bottom = listBottomFadeHeight),
                contentAlignment = Alignment.Center,
            ) {
                emptyContent()
            }
        } else {
            LazyColumn(
                modifier = Modifier.matchParentSize(),
                contentPadding = PaddingValues(bottom = listBottomFadeHeight),
            ) {
                ladderSections.forEach { section ->
                    when (section.kind) {
                        HomeSessionLadderKind.Recent -> {
                            item(key = "header-${section.id}") {
                                SessionListSectionHeader(title = "Recent")
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
                                    onSessionSelected = onSessionSelected,
                                )
                            }
                        }
                        HomeSessionLadderKind.Day,
                        HomeSessionLadderKind.Week,
                        -> {
                            item(key = "header-${section.id}") {
                                SessionListSectionHeader(title = section.title)
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
                                    onSessionSelected = onSessionSelected,
                                )
                            }
                        }
                    }
                }
            }
        }

        OpenCodeBottomFade(
            height = listBottomFadeHeight,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        OpenCodeSessionListDock(
            filterTabs = filterTabs,
            selectedFilterId = selectedFilterId,
            createError = createError,
            onFilterSelected = onFilterSelected,
            onAddProject = onAddProject,
            onNewSession = onNewSession,
            onRemoveProject = onRemoveProject,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        val height: Dp = with(density) { coordinates.size.height.toDp() }
                        if (height != dockBlockHeight) {
                            dockBlockHeight = height
                        }
                    }
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SessionListSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = openCodeColor("text-base"),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun SessionListRow(
    session: SessionListSession,
    showStatusDot: Boolean,
    showDivider: Boolean,
    borderWeaker: androidx.compose.ui.graphics.Color,
    onSessionSelected: (SessionListSession) -> Unit,
) {
    val status = session.status.toListItemStatus()
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
        onClick = { onSessionSelected(session) },
    )
    if (showDivider) {
        HorizontalDivider(
            modifier = Modifier.padding(start = 60.dp),
            color = borderWeaker,
        )
    }
}

fun List<SessionListSession>.visibleForFilter(selectedFilterId: String): List<SessionListSession> =
    if (selectedFilterId == SessionListFilterTab.ALL_ID) {
        this
    } else {
        filter { it.worktree == selectedFilterId }
    }

private fun SessionListStatus.toListItemStatus(): OpenCodeListItemStatus =
    when (this) {
        SessionListStatus.Idle ->
            OpenCodeListItemStatus(text = "Idle", tone = OpenCodeListItemStatusTone.Info)
        SessionListStatus.Running ->
            OpenCodeListItemStatus(text = "Running", tone = OpenCodeListItemStatusTone.Interactive)
        SessionListStatus.Ready ->
            OpenCodeListItemStatus(text = "Ready", tone = OpenCodeListItemStatusTone.Success)
    }

private fun compactHomePath(path: String): String {
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
                    SessionListFilterTab(id = SessionListFilterTab.ALL_ID, title = "All", count = 2),
                    SessionListFilterTab(
                        id = "/Users/demo/opencode-mobile",
                        title = "opencode-mobile",
                        count = 1,
                    ),
                    SessionListFilterTab(id = "/Users/demo/playground", title = "playground", count = 1),
                ),
            selectedFilterId = SessionListFilterTab.ALL_ID,
            onFilterSelected = {},
            onSessionSelected = {},
            onAddProject = {},
            onNewSession = {},
        )
    }
}
