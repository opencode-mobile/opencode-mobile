package dev.opencode.mobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.OpenCodeTheme

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

@Composable
fun SessionList(
    sessions: List<SessionListSession>,
    onSessionSelected: (SessionListSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sessions.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        sessions.forEach { session ->
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
            onSessionSelected = {},
        )
    }
}
