package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.ui.components.SessionList
import dev.opencode.mobile.ui.components.SessionListFilterTab
import dev.opencode.mobile.ui.components.SessionListSession
import dev.opencode.mobile.ui.components.SessionListStatus

@Composable
fun SessionListGalleryScreen(modifier: Modifier = Modifier) {
    var selectedFilterId by rememberSaveable { mutableStateOf(SessionListFilterTab.ALL_ID) }
    SessionList(
        sessions = sessionListGallerySamples,
        filterTabs = sessionListGalleryFilterTabs,
        selectedFilterId = selectedFilterId,
        onFilterSelected = { selectedFilterId = it },
        onSessionSelected = {},
        onAddProject = {},
        onNewSession = {},
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

private val sessionListGalleryFilterTabs =
    listOf(
        SessionListFilterTab(id = SessionListFilterTab.ALL_ID, title = "All", count = 4),
        SessionListFilterTab(
            id = "/Users/demo/opencode-mobile",
            title = "opencode-mobile",
            count = 2,
        ),
        SessionListFilterTab(id = "/Users/demo/playground", title = "playground", count = 1),
        SessionListFilterTab(id = "/Users/demo/api-client", title = "api-client", count = 1),
    )
