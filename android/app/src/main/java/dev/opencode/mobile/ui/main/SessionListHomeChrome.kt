package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.home.HomeScope
import dev.opencode.mobile.domain.home.HomeWorktree
import dev.opencode.mobile.domain.home.HomeWorktreeCopy
import dev.opencode.mobile.ui.components.SessionListFilterTab
import dev.opencode.mobile.ui.components.SessionListScopeLoad

object SessionListHomeChrome {
    data class Project(
        /** Primary added path: where project-level requests and single-worktree sessions go. */
        val worktree: String,
        val title: String,
        val sessionCount: Int,
        val load: SessionListScopeLoad? = null,
        /** Scope id (the Home project key). */
        val id: String = worktree,
        /** The project's worktrees; the picker lists them only when there is more than one. */
        val worktrees: List<Worktree> = emptyList(),
    ) {
        val hasManyWorktrees: Boolean
            get() = worktrees.size > 1
    }

    data class Worktree(
        val worktree: HomeWorktree,
        val sessionCount: Int = 0,
        val runningCount: Int = 0,
        /** Scope id; [HomeScope.worktreeId] of the project key and directory. */
        val scopeId: String = HomeScope.worktreeId("", worktree.directory),
    ) {
        val directory: String get() = worktree.directory
        val title: String get() = HomeWorktreeCopy.worktreeTitle(worktree)
        val subtitle: String get() = HomeWorktreeCopy.worktreeSubtitle(worktree)
    }

    sealed class NewSessionAction {
        data object None : NewSessionAction()

        data object PresentPicker : NewSessionAction()

        data class Create(
            val worktree: String,
        ) : NewSessionAction()

        /** The project has several worktrees: ask where to run. */
        data class ChooseWorktree(
            val projectId: String,
        ) : NewSessionAction()
    }

    sealed class TerminalAction {
        data object None : TerminalAction()

        data object OpenCatalog : TerminalAction()
    }

    fun filterTabs(
        projects: List<Project>,
        sessionCount: Int,
    ): List<SessionListFilterTab> {
        val projectTabs = projects.flatMap(::projectTabs)
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

    private fun projectTabs(project: Project): List<SessionListFilterTab> {
        val base =
            SessionListFilterTab
                .project(
                    worktree = project.worktree,
                    title = project.title,
                    count = project.sessionCount,
                    load = project.load,
                ).copy(id = project.id)
        if (!project.hasManyWorktrees) return listOf(base)
        val projectTab =
            base.copy(
                subtitle = HomeWorktreeCopy.worktreeCount(project.worktrees.size),
                dockSubtitle = HomeWorktreeCopy.allWorktrees(project.worktrees.size),
            )
        val worktreeTabs =
            project.worktrees.map { worktree ->
                SessionListFilterTab(
                    id = worktree.scopeId,
                    title = worktree.title,
                    count = worktree.sessionCount,
                    subtitle = worktree.subtitle,
                    letter = base.letter,
                    letterAccent = base.letterAccent,
                    parentId = project.id,
                    dockTitle = project.title,
                    dockSubtitle = HomeWorktreeCopy.worktreePill(worktree.worktree),
                )
            }
        return listOf(projectTab) + worktreeTabs
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
        if (selectedFilterId != SessionListFilterTab.ALL_ID) {
            projects.firstOrNull { it.id == selectedFilterId }?.let { return projectAction(it) }
            projects
                .firstNotNullOfOrNull { project -> project.worktrees.firstOrNull { it.scopeId == selectedFilterId } }
                ?.let { return NewSessionAction.Create(it.directory) }
        }
        if (projects.size == 1) {
            return projectAction(projects.first())
        }
        return NewSessionAction.PresentPicker
    }

    /** New session in [project]: its only checkout, or a Run in choice when it has several. */
    fun projectAction(project: Project): NewSessionAction =
        if (project.hasManyWorktrees) {
            NewSessionAction.ChooseWorktree(project.id)
        } else {
            NewSessionAction.Create(project.worktree)
        }

    fun terminalAction(projects: List<Project>): TerminalAction {
        if (projects.isEmpty()) {
            return TerminalAction.None
        }
        return TerminalAction.OpenCatalog
    }

    fun newSessionLongPressAction(projects: List<Project>): NewSessionAction {
        if (projects.size < 2) {
            return NewSessionAction.None
        }
        return NewSessionAction.PresentPicker
    }
}
