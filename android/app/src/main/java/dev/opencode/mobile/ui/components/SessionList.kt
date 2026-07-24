package dev.opencode.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.icons.OpenCodeIcons

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
            if (selectedFilterId == SessionListFilterTab.ALL_ID) {
                sessions
            } else {
                sessions.filter { it.worktree == selectedFilterId }
            }
        }

    Column(modifier = modifier.fillMaxSize()) {
        OpenCodeFilterTabs(
            tabs =
                filterTabs.map { tab ->
                    OpenCodeFilterTab(id = tab.id, title = tab.title, count = tab.count)
                },
            selectedTabId = selectedFilterId,
            onTabSelected = onFilterSelected,
        )

        LazyColumn(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
        ) {
            items(visibleSessions, key = { it.id }) { session ->
                OpenCodeListItem(
                    title = session.title,
                    subtitle = compactHomePath(session.subtitle),
                    status = listItemStatus(session.status),
                    caption = session.caption.takeIf { it.isNotEmpty() },
                    style = OpenCodeListItemStyle.Plain,
                    startDecorator = StartDecorator.Letter(session.letter, session.letterAccent),
                    onClick = { onSessionSelected(session) },
                )
            }
        }

        SessionListFooter(
            createError = createError,
            onAddProject = onAddProject,
            onNewSession = onNewSession,
        )
    }
}

@Composable
private fun SessionListFooter(
    createError: String?,
    onAddProject: () -> Unit,
    onNewSession: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 16.dp)
                .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenCodeButton(
                variant = OpenCodeButtonVariant.IconSquare(OpenCodeIcons.Add, "Add project"),
                onClick = onAddProject,
            )
            OpenCodeButton(
                variant = OpenCodeButtonVariant.FullWidthText("New session"),
                size = OpenCodeButtonSize.Medium,
                onClick = onNewSession,
                modifier = Modifier.weight(1f),
            )
        }
        createError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun listItemStatus(status: SessionListStatus): OpenCodeListItemStatus =
    when (status) {
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
