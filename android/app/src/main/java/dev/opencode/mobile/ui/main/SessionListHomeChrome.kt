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

    fun filterTabs(
        projects: List<Project>,
        sessionCount: Int,
    ): List<SessionListFilterTab> {
        val projectTabs =
            projects.map { project ->
                SessionListFilterTab(
                    id = project.worktree,
                    title = project.title,
                    count = project.sessionCount,
                )
            }
        if (projects.size < 2) {
            return projectTabs
        }
        return listOf(
            SessionListFilterTab(
                id = SessionListFilterTab.ALL_ID,
                title = "All",
                count = sessionCount,
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

    fun newSessionAction(projects: List<Project>): NewSessionAction =
        when (projects.size) {
            0 -> NewSessionAction.None
            1 -> NewSessionAction.Create(projects.first().worktree)
            else -> NewSessionAction.PresentPicker
        }
}
