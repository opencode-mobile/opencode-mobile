package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.ui.components.SessionList
import dev.opencode.mobile.ui.components.SessionListSession
import dev.opencode.mobile.ui.components.SessionListStatus

@Composable
fun SessionListGalleryScreen(modifier: Modifier = Modifier) {
    SessionList(
        sessions = sessionListGallerySamples,
        onSessionSelected = {},
        modifier = modifier,
    )
}

private val sessionListGallerySamples =
    listOf(
        SessionListSession(
            id = "session-1",
            title = "Refactor Navigation",
            worktree = "/Users/demo/opencode-mobile",
            letter = 'O',
            letterAccent = LetterAccent.Violet,
            subtitle = "/Users/demo/opencode-mobile",
            status = SessionListStatus.Running,
            caption = "now",
        ),
        SessionListSession(
            id = "session-2",
            title = "Bug: Header Clipping",
            worktree = "/Users/demo/playground",
            letter = 'P',
            letterAccent = LetterAccent.Teal,
            subtitle = "/Users/demo/playground",
            status = SessionListStatus.Idle,
            caption = "14m ago",
        ),
        SessionListSession(
            id = "session-3",
            title = "Add Dark Mode Assets",
            worktree = "/Users/demo/opencode-mobile",
            letter = 'O',
            letterAccent = LetterAccent.Violet,
            subtitle = "/Users/demo/opencode-mobile",
            status = SessionListStatus.Idle,
            caption = "3h ago",
        ),
        SessionListSession(
            id = "session-4",
            title = "Review Session List",
            worktree = "/Users/demo/api-client",
            letter = 'A',
            letterAccent = LetterAccent.Amber,
            subtitle = "/Users/demo/api-client",
            status = SessionListStatus.Running,
            caption = "2d ago",
        ),
    )
