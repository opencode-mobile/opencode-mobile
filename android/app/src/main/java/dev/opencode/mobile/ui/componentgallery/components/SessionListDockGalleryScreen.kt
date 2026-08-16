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
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetContentMode
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeSessionListDock
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.SessionListFilterTab
import dev.opencode.mobile.ui.components.SessionListProjectPickerRows

@Composable
fun SessionListDockGalleryScreen(modifier: Modifier = Modifier) {
    var selectedFilterId by rememberSaveable { mutableStateOf(SessionListFilterTab.ALL_ID) }
    var showCreateError by rememberSaveable { mutableStateOf(false) }
    var oneProject by rememberSaveable { mutableStateOf(false) }
    var showProjectPicker by rememberSaveable { mutableStateOf(false) }
    var projectPickerQuery by rememberSaveable { mutableStateOf("") }
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
        }

        OpenCodeSessionListDock(
            filterTabs = filterTabs,
            selectedFilterId = selectedFilterId,
            createError = if (showCreateError) "Could not create session. Try again." else null,
            onOpenProjectPicker = { showProjectPicker = true },
            onAddProject = {},
            onNewSession = {},
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
        alwaysFixedHeight = true,
        onDismissRequest = {
            showProjectPicker = false
            projectPickerQuery = ""
        },
        pinnedContent = {
            OpenCodeTextInput(
                value = projectPickerQuery,
                onValueChange = { projectPickerQuery = it },
                hint = "Search projects",
            )
        },
    ) {
        SessionListProjectPickerRows(
            tabs = filterTabs,
            query = projectPickerQuery,
            onSelect = { id ->
                selectedFilterId = id
                showProjectPicker = false
                projectPickerQuery = ""
            },
            onRemoveProject = { worktree ->
                val remaining = filterTabs.filter { it.id != worktree }
                selectedFilterId = remaining.firstOrNull()?.id ?: selectedFilterId
                showProjectPicker = false
                projectPickerQuery = ""
            },
        )
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
