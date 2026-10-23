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
import dev.opencode.mobile.domain.preferences.HomeSessionRowStyle
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeight
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetKeyboard
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.SessionList
import dev.opencode.mobile.ui.components.SessionListFilterTab
import dev.opencode.mobile.ui.components.SessionListProjectPickerRows
import dev.opencode.mobile.ui.components.SessionListScopeLoad
import dev.opencode.mobile.ui.components.SessionListSession
import dev.opencode.mobile.ui.components.SessionListStatus

@Composable
fun SessionListGalleryScreen(modifier: Modifier = Modifier) {
    var selectedFilterId by rememberSaveable { mutableStateOf(SessionListFilterTab.ALL_ID) }
    var oneProject by rememberSaveable { mutableStateOf(false) }
    var showProjectPicker by rememberSaveable { mutableStateOf(false) }
    var loadStep by rememberSaveable { mutableStateOf(GalleryScopeLoadStep.None) }
    var classicRows by rememberSaveable { mutableStateOf(false) }
    val filterTabs =
        galleryTabsWithNewProject(
            base =
                if (oneProject) {
                    sessionListGalleryOneProjectFilterTabs
                } else {
                    sessionListGalleryFilterTabs
                },
            oneProject = oneProject,
            load = loadStep.load,
        )
    Box(modifier = modifier.fillMaxSize()) {
        SessionList(
            sessions = sessionListGallerySamples,
            filterTabs = filterTabs,
            selectedFilterId = selectedFilterId,
            onOpenProjectPicker = { showProjectPicker = true },
            onFilterSelected = { selectedFilterId = it },
            onSessionSelected = {},
            onAddProject = {},
            onNewSession = {},
            onRetryScopeLoad = { loadStep = GalleryScopeLoadStep.Loading },
            rowStyle = if (classicRows) HomeSessionRowStyle.Classic else HomeSessionRowStyle.Compact,
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
                    loadStep = GalleryScopeLoadStep.None
                },
            )
            OpenCodeChoiceBadge(
                label = if (classicRows) "Classic" else "Compact",
                selected = classicRows,
                onClick = { classicRows = !classicRows },
            )
            OpenCodeChoiceBadge(
                label = loadStep.label,
                selected = loadStep != GalleryScopeLoadStep.None,
                onClick = {
                    loadStep = loadStep.next()
                    selectedFilterId =
                        when {
                            loadStep != GalleryScopeLoadStep.None -> GALLERY_NEW_PROJECT_WORKTREE
                            oneProject -> sessionListGalleryOneProjectFilterTabs.first().id
                            else -> SessionListFilterTab.ALL_ID
                        }
                },
            )
        }
    }
    OpenCodeBottomSheet(
        visible = showProjectPicker,
        title = "Filter by project",
        // Half + Keep keeps today's fixed height and keyboard policy; Dismiss would be more
        // natural for this list (#680).
        height = OpenCodeBottomSheetHeight.Half,
        keyboard = OpenCodeBottomSheetKeyboard.Keep,
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
                projectName = "opencode-mobile",
                compactWorktree = "794-readable-tool-results",
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
                projectName = "opencode-mobile",
            ),
            SessionListSession(
                id = "session-done",
                title = "Server Unread: Done",
                worktree = "/Users/demo/playground",
                letter = 'P',
                letterAccent = LetterAccent.Teal,
                subtitle = "/Users/demo/playground",
                status = SessionListStatus.Done,
                caption = "5m ago",
                updatedAtEpochMs = now - 5 * 60_000L,
            ),
            SessionListSession(
                id = "session-failed",
                title = "Server Unread: Failed",
                worktree = "/Users/demo/playground",
                letter = 'P',
                letterAccent = LetterAccent.Teal,
                subtitle = "/Users/demo/playground",
                status = SessionListStatus.Failed,
                caption = "8m ago",
                updatedAtEpochMs = now - 8 * 60_000L,
            ),
            SessionListSession(
                id = "session-stopped",
                title = "Server Unread: Stopped",
                worktree = "/Users/demo/playground",
                letter = 'P',
                letterAccent = LetterAccent.Teal,
                subtitle = "/Users/demo/playground",
                status = SessionListStatus.Stopped,
                caption = "11m ago",
                updatedAtEpochMs = now - 11 * 60_000L,
            ),
            SessionListSession(
                id = "session-retry",
                title = "Retry Provider Error",
                worktree = "/Users/demo/opencode-mobile",
                letter = 'O',
                letterAccent = LetterAccent.Violet,
                subtitle = "/Users/demo/opencode-mobile",
                status = SessionListStatus.Retrying,
                caption = "now",
                updatedAtEpochMs = now,
                retryAttempt = 2,
                projectName = "opencode-mobile",
                compactWorktree = "ope-1042-show-worktree-and-project-on-home-rows",
            ),
            SessionListSession(
                id = "session-stale",
                title = "Cached Working Session",
                worktree = "/Users/demo/opencode-mobile",
                letter = 'O',
                letterAccent = LetterAccent.Violet,
                subtitle = "/Users/demo/opencode-mobile",
                status = SessionListStatus.Running,
                statusStale = true,
                caption = "now",
                updatedAtEpochMs = now,
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
        SessionListFilterTab.all(projectCount = 3, sessionCount = 9),
        SessionListFilterTab.project(
            worktree = "/Users/demo/opencode-mobile",
            title = "opencode-mobile",
            count = 4,
        ),
        SessionListFilterTab.project(worktree = "/Users/demo/playground", title = "playground", count = 4),
        SessionListFilterTab.project(worktree = "/Users/demo/api-client", title = "api-client", count = 1),
    )

private val sessionListGalleryOneProjectFilterTabs =
    listOf(
        SessionListFilterTab.project(
            worktree = "/Users/demo/opencode-mobile",
            title = "opencode-mobile",
            count = 4,
        ),
    )

internal const val GALLERY_NEW_PROJECT_WORKTREE = "/Users/demo/new-project"

/** Gallery cycle through a new project's first-load states. */
internal enum class GalleryScopeLoadStep(
    val label: String,
    val load: SessionListScopeLoad?,
) {
    None("Loading", null),
    Loading("Loading", SessionListScopeLoad.Loading()),
    Slow("Slow", SessionListScopeLoad.Loading(isSlow = true)),
    Failed("Failed", SessionListScopeLoad.Failed),
    ;

    fun next(): GalleryScopeLoadStep = entries[(ordinal + 1) % entries.size]
}

/** Adds a just-added project with no sessions yet whose first load is [load]. */
internal fun galleryTabsWithNewProject(
    base: List<SessionListFilterTab>,
    oneProject: Boolean,
    load: SessionListScopeLoad?,
): List<SessionListFilterTab> {
    if (load == null) return base
    val newProject =
        SessionListFilterTab.project(
            worktree = GALLERY_NEW_PROJECT_WORKTREE,
            title = "new-project",
            count = 0,
            load = load,
        )
    if (oneProject) return listOf(newProject)
    val projects = base.filterNot { it.isAll } + newProject
    return listOf(
        SessionListFilterTab.all(projectCount = projects.size, sessionCount = projects.sumOf { it.count }),
    ) + projects
}
