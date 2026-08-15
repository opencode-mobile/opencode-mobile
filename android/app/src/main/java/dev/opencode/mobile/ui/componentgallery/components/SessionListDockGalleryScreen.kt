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
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeSessionListDock
import dev.opencode.mobile.ui.components.SessionListFilterTab

@Composable
fun SessionListDockGalleryScreen(modifier: Modifier = Modifier) {
    var selectedFilterId by rememberSaveable { mutableStateOf(SessionListFilterTab.ALL_ID) }
    var showCreateError by rememberSaveable { mutableStateOf(false) }
    var oneProject by rememberSaveable { mutableStateOf(false) }
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
            onFilterSelected = { selectedFilterId = it },
            onAddProject = {},
            onNewSession = {},
            onRemoveProject = { selectedFilterId = SessionListFilterTab.ALL_ID },
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

private val sessionListDockGalleryFilterTabs =
    listOf(
        SessionListFilterTab(id = SessionListFilterTab.ALL_ID, title = "All", count = 4),
        SessionListFilterTab(id = "/Users/demo/opencode-mobile", title = "opencode-mobile", count = 2),
        SessionListFilterTab(id = "/Users/demo/playground", title = "playground", count = 1),
        SessionListFilterTab(id = "/Users/demo/api-client", title = "api-client", count = 1),
    )

private val sessionListDockGalleryOneProjectFilterTabs =
    listOf(
        SessionListFilterTab(
            id = "/Users/demo/opencode-mobile",
            title = "opencode-mobile",
            count = 2,
        ),
    )
