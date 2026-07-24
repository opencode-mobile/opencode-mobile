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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor

enum class SessionListStatus {
    Idle,
    Running,
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
) {
    val visibleSessions =
        remember(sessions, selectedFilterId) {
            sessions.visibleForFilter(selectedFilterId)
        }

    val background = openCodeColor("background-base")
    val borderWeaker = openCodeColor("border-weaker-base")
    val density = LocalDensity.current

    var dockBlockHeight by remember { mutableStateOf(0.dp) }
    val listBottomFadeHeight =
        if (dockBlockHeight > 0.dp) {
            dockBlockHeight + OpenCodeBottomFadeOverhang
        } else {
            168.dp
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(background),
    ) {
        LazyColumn(
            modifier = Modifier.matchParentSize(),
            contentPadding = PaddingValues(bottom = listBottomFadeHeight),
        ) {
            itemsIndexed(visibleSessions, key = { _, session -> session.id }) { index, session ->
                OpenCodeListItem(
                    title = session.title,
                    subtitle = compactHomePath(session.subtitle),
                    status = session.status.toListItemStatus(),
                    caption = session.caption.takeIf { it.isNotEmpty() },
                    startDecorator = StartDecorator.Letter(session.letter, session.letterAccent),
                    onClick = { onSessionSelected(session) },
                )
                if (index < visibleSessions.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 60.dp),
                        color = borderWeaker,
                    )
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
    }

private fun compactHomePath(path: String): String {
    val home = System.getProperty("user.home").orEmpty()
    if (home.isEmpty()) return path
    if (path == home) return "~"
    if (path.startsWith("$home/")) return "~" + path.removePrefix(home)
    return path
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
