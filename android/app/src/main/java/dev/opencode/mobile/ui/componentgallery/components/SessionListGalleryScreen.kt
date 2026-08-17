package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetContentMode
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.SessionList
import dev.opencode.mobile.ui.components.SessionListFilterTab
import dev.opencode.mobile.ui.components.SessionListProjectPickerRows
import dev.opencode.mobile.ui.components.SessionListSession
import dev.opencode.mobile.ui.components.SessionListStatus

@Composable
fun SessionListGalleryScreen(modifier: Modifier = Modifier) {
    var selectedFilterId by rememberSaveable { mutableStateOf(SessionListFilterTab.ALL_ID) }
    var oneProject by rememberSaveable { mutableStateOf(false) }
    var useProjectPicker by rememberSaveable { mutableStateOf(false) }
    var showProjectPicker by rememberSaveable { mutableStateOf(false) }
    val filterTabs =
        if (oneProject) {
            sessionListGalleryOneProjectFilterTabs
        } else {
            sessionListGalleryFilterTabs
        }
    Box(modifier = modifier.fillMaxSize()) {
        SessionList(
            sessions = sessionListGallerySamples,
            filterTabs = filterTabs,
            selectedFilterId = selectedFilterId,
            useProjectPicker = useProjectPicker,
            onOpenProjectPicker = { showProjectPicker = true },
            onFilterSelected = { selectedFilterId = it },
            onSessionSelected = {},
            onAddProject = {},
            onNewSession = {},
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OpenCodeChoiceBadge(
                label = "1 project",
                selected = oneProject,
                onClick = {
                    oneProject = !oneProject
                    selectedFilterId =
                        if (oneProject) {
                            sessionListGalleryOneProjectFilterTabs.first().id
                        } else {
                            SessionListFilterTab.ALL_ID
                        }
                },
            )
            OpenCodeChoiceBadge(
                label = "Picker",
                selected = useProjectPicker,
                onClick = { useProjectPicker = !useProjectPicker },
            )
        }
    }
    OpenCodeBottomSheet(
        visible = showProjectPicker,
        title = "Filter by project",
        contentMode = OpenCodeBottomSheetContentMode.Form,
        onDismissRequest = { showProjectPicker = false },
    ) {
        SessionListProjectPickerRows(
            tabs = filterTabs,
            onSelect = { id ->
                selectedFilterId = id
                showProjectPicker = false
            },
        )
    }
}

private val sessionListGallerySamples: List<SessionListSession>
    get() {
        val now = System.currentTimeMillis()
        return listOf(
            SessionListSession(
                id = "session-1",
                title = "Refactor Navigation",
                worktree = "/Users/demo/opencode-mobile",
                letter = 'O',
                letterAccent = LetterAccent.Violet,
                subtitle = "/Users/demo/opencode-mobile",
                status = SessionListStatus.Running,
                caption = "now",
                updatedAtEpochMs = now,
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
                updatedAtEpochMs = now - 14 * 60_000L,
            ),
            SessionListSession(
                id = "session-3",
                title = "Add Dark Mode Assets",
                worktree = "/Users/demo/opencode-mobile",
                letter = 'O',
                letterAccent = LetterAccent.Violet,
                subtitle = "/Users/demo/opencode-mobile",
                status = SessionListStatus.Ready,
                caption = "3h ago",
                updatedAtEpochMs = now - 3 * 3_600_000L,
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
                updatedAtEpochMs = now - 2 * 86_400_000L,
            ),
        )
    }

private val sessionListGalleryFilterTabs =
    listOf(
        SessionListFilterTab.all(projectCount = 3, sessionCount = 4),
        SessionListFilterTab.project(
            worktree = "/Users/demo/opencode-mobile",
            title = "opencode-mobile",
            count = 2,
        ),
        SessionListFilterTab.project(worktree = "/Users/demo/playground", title = "playground", count = 1),
        SessionListFilterTab.project(worktree = "/Users/demo/api-client", title = "api-client", count = 1),
    )

private val sessionListGalleryOneProjectFilterTabs =
    listOf(
        SessionListFilterTab.project(
            worktree = "/Users/demo/opencode-mobile",
            title = "opencode-mobile",
            count = 2,
        ),
    )
