package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetContentMode
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeSessionListDock
import dev.opencode.mobile.ui.components.SessionListFilterTab
import dev.opencode.mobile.ui.components.SessionListProjectPickerRows
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun SessionListDockGalleryScreen(modifier: Modifier = Modifier) {
    var selectedFilterId by rememberSaveable { mutableStateOf(SessionListFilterTab.ALL_ID) }
    var showCreateError by rememberSaveable { mutableStateOf(false) }
    var oneProject by rememberSaveable { mutableStateOf(false) }
    var useProjectPicker by rememberSaveable { mutableStateOf(false) }
    var showProjectPicker by rememberSaveable { mutableStateOf(false) }
    var showNewSessionPicker by rememberSaveable { mutableStateOf(false) }
    val filterTabs =
        if (oneProject) {
            sessionListDockGalleryOneProjectFilterTabs
        } else {
            sessionListDockGalleryFilterTabs
        }

    Box(modifier = modifier.fillMaxSize()) {
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
                            sessionListDockGalleryOneProjectFilterTabs.first().id
                        } else {
                            SessionListFilterTab.ALL_ID
                        }
                },
            )
            OpenCodeChoiceBadge(
                label = "Create error",
                selected = showCreateError,
                onClick = { showCreateError = !showCreateError },
            )
            OpenCodeChoiceBadge(
                label = "Picker",
                selected = useProjectPicker,
                onClick = { useProjectPicker = !useProjectPicker },
            )
        }

        OpenCodeSessionListDock(
            filterTabs = filterTabs,
            selectedFilterId = selectedFilterId,
            createError = if (showCreateError) "Could not create session. Try again." else null,
            useProjectPicker = useProjectPicker,
            onOpenProjectPicker = { showProjectPicker = true },
            onFilterSelected = { selectedFilterId = it },
            onAddProject = {},
            onNewSession = {},
            onNewSessionLongPress =
                if (useProjectPicker && !oneProject) {
                    { showNewSessionPicker = true }
                } else {
                    null
                },
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        )
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
            onRemoveProject = { worktree ->
                val remaining = filterTabs.filter { it.id != worktree }
                selectedFilterId = remaining.firstOrNull()?.id ?: selectedFilterId
                showProjectPicker = false
            },
        )
    }

    OpenCodeBottomSheet(
        visible = showNewSessionPicker,
        title = "New session",
        onDismissRequest = { showNewSessionPicker = false },
    ) {
        filterTabs.filterNot { it.isAll }.forEach { tab ->
            OpenCodeBottomSheetEntry(
                title = tab.title,
                startDecorator =
                    BottomSheetEntryDecorator.Icon(
                        imageVector = OpenCodeIcons.Folder,
                        contentDescription = null,
                    ),
                onClick = { showNewSessionPicker = false },
            )
        }
    }
}

private val sessionListDockGalleryFilterTabs =
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

private val sessionListDockGalleryOneProjectFilterTabs =
    listOf(
        SessionListFilterTab.project(
            worktree = "/Users/demo/opencode-mobile",
            title = "opencode-mobile",
            count = 2,
        ),
    )
