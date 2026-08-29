package dev.opencode.mobile.ui.main

import dev.opencode.mobile.ui.components.SessionListFilterTab

object SessionListHomeChrome {
    data class Project(
        val worktree: String,
        val title: String,
        val sessionCount: Int,
    )

    sealed class NewSessionAction {
        data object None : NewSessionAction()

        data object PresentPicker : NewSessionAction()

        data class Create(
            val worktree: String,
        ) : NewSessionAction()
    }

    sealed class TerminalAction {
        data object None : TerminalAction()

        data object Snack : TerminalAction()

        data class Open(
            val worktree: String,
            val projectTitle: String,
        ) : TerminalAction()
    }

    const val SELECT_PROJECT_SNACK = "Select a project first."

    fun filterTabs(
        projects: List<Project>,
        sessionCount: Int,
    ): List<SessionListFilterTab> {
        val projectTabs =
            projects.map { project ->
                SessionListFilterTab.project(
                    worktree = project.worktree,
                    title = project.title,
                    count = project.sessionCount,
                )
            }
        if (projects.size < 2) {
            return projectTabs
        }
        return listOf(
            SessionListFilterTab.all(
                projectCount = projects.size,
                sessionCount = sessionCount,
            ),
        ) + projectTabs
    }

    fun resolvedFilterId(
        selectedId: String,
        tabs: List<SessionListFilterTab>,
    ): String =
        tabs.firstOrNull { it.id == selectedId }?.id
            ?: tabs.firstOrNull()?.id
            ?: SessionListFilterTab.ALL_ID

    fun newSessionAction(
        projects: List<Project>,
        selectedFilterId: String,
    ): NewSessionAction {
        if (projects.isEmpty()) {
            return NewSessionAction.None
        }
        if (selectedFilterId != SessionListFilterTab.ALL_ID &&
            projects.any { it.worktree == selectedFilterId }
        ) {
            return NewSessionAction.Create(selectedFilterId)
        }
        if (projects.size == 1) {
            return NewSessionAction.Create(projects.first().worktree)
        }
        return NewSessionAction.PresentPicker
    }

    fun terminalAction(
        projects: List<Project>,
        selectedFilterId: String,
    ): TerminalAction {
        if (projects.isEmpty()) {
            return TerminalAction.None
        }
        val selected = projects.firstOrNull { it.worktree == selectedFilterId }
        if (selected != null) {
            return TerminalAction.Open(selected.worktree, selected.title)
        }
        if (projects.size == 1) {
            val project = projects.first()
            return TerminalAction.Open(project.worktree, project.title)
        }
        return TerminalAction.Snack
    }

    fun newSessionLongPressAction(
        projects: List<Project>,
        useProjectPicker: Boolean,
    ): NewSessionAction {
        if (!useProjectPicker || projects.size < 2) {
            return NewSessionAction.None
        }
        return NewSessionAction.PresentPicker
    }
}
