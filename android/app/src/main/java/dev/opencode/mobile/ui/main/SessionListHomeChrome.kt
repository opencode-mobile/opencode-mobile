package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.home.HomeProjectPickerState
import dev.opencode.mobile.domain.home.HomeScope
import dev.opencode.mobile.domain.home.HomeWorktree
import dev.opencode.mobile.domain.home.HomeWorktreeCopy
import dev.opencode.mobile.ui.components.SessionListFilterTab
import dev.opencode.mobile.ui.components.SessionListProjectPickerRow
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

        /** Where a tapped New session starts: the main checkout, else the primary added path. */
        val mainCheckoutDirectory: String
            get() = worktrees.firstOrNull { it.worktree.isMain }?.directory ?: worktree
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

        data class Create(
            val worktree: String,
        ) : NewSessionAction()

        /** Opens the New session sheet in [mode]. */
        data class PresentSheet(
            val mode: NewSessionSheetMode,
        ) : NewSessionAction()
    }

    /** What the New session sheet lists. */
    sealed class NewSessionSheetMode {
        /**
         * The project list. After a hold ([holdPick]) a pick opens the project's Run in list
         * while Worktrees is on; after a tap every pick starts on the project's main checkout.
         */
        data class Projects(
            val holdPick: Boolean,
        ) : NewSessionSheetMode()

        /**
         * [projectId]'s worktrees under "Run in" (main checkout first) and "New worktree…", then
         * "Other projects" when [showsOtherProjects].
         */
        data class RunIn(
            val projectId: String,
            val showsOtherProjects: Boolean,
        ) : NewSessionSheetMode()
    }

    sealed class TerminalAction {
        data object None : TerminalAction()

        data object OpenCatalog : TerminalAction()
    }

    /** Scopes the picker and the dock walk: All (with two or more projects) and each project. */
    fun filterTabs(
        projects: List<Project>,
        sessionCount: Int,
        worktreesEnabled: Boolean = true,
    ): List<SessionListFilterTab> {
        val projectTabs = projects.map { projectTab(it, worktreesEnabled) }
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

    private fun projectTab(
        project: Project,
        worktreesEnabled: Boolean,
    ): SessionListFilterTab {
        val base =
            SessionListFilterTab
                .project(
                    worktree = project.worktree,
                    title = project.title,
                    count = project.sessionCount,
                    load = project.load,
                ).copy(id = project.id)
        if (!worktreesEnabled || !project.hasManyWorktrees) return base
        return base.copy(dockSubtitle = HomeWorktreeCopy.allWorktrees(project.worktrees.size))
    }

    /** [project]'s worktree scopes, listed under it in the picker; none while worktrees are off. */
    fun worktreeTabs(
        project: Project,
        worktreesEnabled: Boolean = true,
    ): List<SessionListFilterTab> {
        if (!worktreesEnabled || !project.hasManyWorktrees) return emptyList()
        val base = projectTab(project, worktreesEnabled)
        return project.worktrees.map { worktree ->
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
    }

    /** Every worktree scope across [projects]; the dock shows one while it is selected. */
    fun worktreeTabs(
        projects: List<Project>,
        worktreesEnabled: Boolean = true,
    ): List<SessionListFilterTab> = projects.flatMap { worktreeTabs(it, worktreesEnabled) }

    /**
     * Rows of the project picker: the reorder note while editing, All and the projects
     * ([filterTabs]), and under the expanded project its first worktrees plus "Show all N" /
     * "Show fewer" past the limit. No worktree rows or pills while worktrees are off.
     */
    fun pickerRows(
        projects: List<Project>,
        sessionCount: Int,
        state: HomeProjectPickerState,
        worktreesEnabled: Boolean = true,
    ): List<SessionListProjectPickerRow> {
        val byId = projects.associateBy { it.id }
        return buildList {
            if (state.isEditing) add(SessionListProjectPickerRow.Note(HomeWorktreeCopy.REORDER_NOTE))
            filterTabs(projects, sessionCount, worktreesEnabled).forEach { tab ->
                val project = byId[tab.id]
                val worktreeCount = if (worktreesEnabled && project != null && project.hasManyWorktrees) project.worktrees.size else 0
                val expanded = worktreeCount > 0 && state.isExpanded(tab.id)
                add(SessionListProjectPickerRow.Scope(tab, worktreeCount = worktreeCount, expanded = expanded))
                if (project == null || !expanded) return@forEach
                state.visibleWorktrees(project.id, worktreeTabs(project, worktreesEnabled)).forEach {
                    add(SessionListProjectPickerRow.Worktree(it))
                }
                state.showAllLabel(project.id, project.worktrees.size)?.let { title ->
                    add(SessionListProjectPickerRow.ShowAll(projectId = project.id, title = title))
                }
            }
        }
    }

    /** [selectedId] when it names a scope in [tabs] or [worktreeTabs], else the first scope. */
    fun resolvedFilterId(
        selectedId: String,
        tabs: List<SessionListFilterTab>,
        worktreeTabs: List<SessionListFilterTab> = emptyList(),
    ): String =
        tabs.firstOrNull { it.id == selectedId }?.id
            ?: worktreeTabs.firstOrNull { it.id == selectedId }?.id
            ?: tabs.firstOrNull()?.id
            ?: SessionListFilterTab.ALL_ID

    /**
     * Project a new session belongs to: the scoped project (or a worktree scope's owner), or
     * the only project from the All scope. Null in the All scope with several projects.
     */
    fun targetProject(
        projects: List<Project>,
        selectedFilterId: String,
    ): Project? {
        if (selectedFilterId != SessionListFilterTab.ALL_ID) {
            projects.firstOrNull { it.id == selectedFilterId }?.let { return it }
            projects.firstOrNull { project -> project.worktrees.any { it.scopeId == selectedFilterId } }?.let { return it }
        }
        return projects.singleOrNull()
    }

    /**
     * Tap on New session: the filtered worktree, else the target project's main checkout. Nothing
     * is asked, except the project choice from the All scope with several projects.
     */
    fun newSessionAction(
        projects: List<Project>,
        selectedFilterId: String,
        worktreesEnabled: Boolean = true,
    ): NewSessionAction {
        if (projects.isEmpty()) {
            return NewSessionAction.None
        }
        if (worktreesEnabled && selectedFilterId != SessionListFilterTab.ALL_ID) {
            projects
                .firstNotNullOfOrNull { project -> project.worktrees.firstOrNull { it.scopeId == selectedFilterId } }
                ?.let { return NewSessionAction.Create(it.directory) }
        }
        targetProject(projects, selectedFilterId)?.let { return NewSessionAction.Create(it.mainCheckoutDirectory) }
        return NewSessionAction.PresentSheet(NewSessionSheetMode.Projects(holdPick = false))
    }

    /**
     * Hold on New session: while Worktrees is on, the target project's Run in list (with the
     * other projects below it), even with one checkout, so "New worktree…" is reachable. Else
     * the project list. Null handler with nothing to choose.
     */
    fun newSessionLongPressAction(
        projects: List<Project>,
        selectedFilterId: String = SessionListFilterTab.ALL_ID,
        worktreesEnabled: Boolean = true,
    ): NewSessionAction {
        val target = targetProject(projects, selectedFilterId)
        if (worktreesEnabled && target != null) {
            return NewSessionAction.PresentSheet(
                NewSessionSheetMode.RunIn(projectId = target.id, showsOtherProjects = projects.size >= 2),
            )
        }
        if (projects.size >= 2) {
            return NewSessionAction.PresentSheet(NewSessionSheetMode.Projects(holdPick = true))
        }
        return NewSessionAction.None
    }

    /**
     * A project picked in the New session sheet: after a hold with Worktrees on, it asks where
     * to run (its checkouts and "New worktree…"); otherwise the session starts on its main
     * checkout.
     */
    fun projectPickAction(
        project: Project,
        holdPick: Boolean,
        worktreesEnabled: Boolean = true,
    ): NewSessionAction =
        if (holdPick && worktreesEnabled) {
            NewSessionAction.PresentSheet(NewSessionSheetMode.RunIn(projectId = project.id, showsOtherProjects = false))
        } else {
            NewSessionAction.Create(project.mainCheckoutDirectory)
        }

    fun terminalAction(projects: List<Project>): TerminalAction {
        if (projects.isEmpty()) {
            return TerminalAction.None
        }
        return TerminalAction.OpenCatalog
    }
}
