package dev.opencode.mobile.ui.main

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.home.HomeProjectPickerState
import dev.opencode.mobile.domain.home.HomeScope
import dev.opencode.mobile.domain.home.HomeWorktree
import dev.opencode.mobile.domain.home.HomeWorktreeCopy
import dev.opencode.mobile.domain.session.NewSessionDraftStart
import dev.opencode.mobile.domain.session.NewSessionPlace
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.domain.text.UiTextLookup
import dev.opencode.mobile.domain.text.resolve
import dev.opencode.mobile.ui.components.SessionListFilterTab
import dev.opencode.mobile.ui.components.SessionListProjectPickerRow
import dev.opencode.mobile.ui.components.SessionListScopeLoad
import dev.opencode.mobile.ui.components.SessionListSession

object SessionListHomeChrome {
    data class Project(
        /** Primary added path: where project-level requests and single-worktree sessions go. */
        val worktree: String,
        val title: String,
        /** Home lists sessions of this project; Home shows no session counts (#981). */
        val hasSessions: Boolean = false,
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
        val hasSessions: Boolean = false,
        val runningCount: Int = 0,
        /** Scope id; [HomeScope.worktreeId] of the project key and directory. */
        val scopeId: String = HomeScope.worktreeId("", worktree.directory),
    ) {
        val directory: String get() = worktree.directory
        /** Branch, else folder name; server content. */
        val title: String get() = HomeWorktreeCopy.worktreeTitle(worktree)
        val subtitle: UiText get() = HomeText.worktreeSubtitle(worktree)
    }

    sealed class NewSessionAction {
        data object None : NewSessionAction()

        /** Opens the New session draft (#1004); a fresh draft starts at [start]. */
        data class OpenDraft(
            val start: NewSessionDraftStart,
        ) : NewSessionAction()
    }

    sealed class TerminalAction {
        data object None : TerminalAction()

        data object OpenCatalog : TerminalAction()
    }

    /**
     * Scopes the picker and the dock walk: All (with two or more projects) and each project.
     * [text] resolves the app text (All, project and worktree counts).
     */
    fun filterTabs(
        projects: List<Project>,
        text: UiTextLookup,
        worktreesEnabled: Boolean = true,
    ): List<SessionListFilterTab> {
        val projectTabs = projects.map { projectTab(it, worktreesEnabled, text) }
        if (projects.size < 2) {
            return projectTabs
        }
        val loading = projects.mapNotNull { it.load as? SessionListScopeLoad.Loading }
        val all =
            SessionListFilterTab.all(
                projectCount = projects.size,
                hasSessions = projects.any { it.hasSessions },
                load = loading.takeIf { it.isNotEmpty() }?.let { SessionListScopeLoad.Loading(isSlow = it.any { load -> load.isSlow }) },
            )
        return listOf(
            all.copy(
                title = UiText.resource(R.string.home_all).resolve(text),
                subtitle = HomeText.projectCount(projects.size).resolve(text),
                pickerTitleOverride = UiText.resource(R.string.home_all_projects).resolve(text),
            ),
        ) + projectTabs
    }

    private fun projectTab(
        project: Project,
        worktreesEnabled: Boolean,
        text: UiTextLookup,
    ): SessionListFilterTab {
        val base =
            SessionListFilterTab
                .project(
                    worktree = project.worktree,
                    title = project.title,
                    hasSessions = project.hasSessions,
                    load = project.load,
                ).copy(id = project.id)
        if (!worktreesEnabled || !project.hasManyWorktrees) return base
        return base.copy(dockSubtitle = HomeText.allWorktrees(project.worktrees.size).resolve(text))
    }

    /** [project]'s worktree scopes, listed under it in the picker; none while worktrees are off. */
    fun worktreeTabs(
        project: Project,
        text: UiTextLookup,
        worktreesEnabled: Boolean = true,
    ): List<SessionListFilterTab> {
        if (!worktreesEnabled || !project.hasManyWorktrees) return emptyList()
        val base = projectTab(project, worktreesEnabled, text)
        return project.worktrees.map { worktree ->
            SessionListFilterTab(
                id = worktree.scopeId,
                title = worktree.title,
                hasSessions = worktree.hasSessions,
                subtitle = worktree.subtitle.resolve(text),
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
        text: UiTextLookup,
        worktreesEnabled: Boolean = true,
    ): List<SessionListFilterTab> = projects.flatMap { worktreeTabs(it, text, worktreesEnabled) }

    /**
     * Rows of the project picker: the reorder note while editing, All and the projects
     * ([filterTabs]), and under the expanded project its first worktrees plus "Show all N" /
     * "Show fewer" past the limit. No worktree rows or pills while worktrees are off.
     */
    fun pickerRows(
        projects: List<Project>,
        state: HomeProjectPickerState,
        text: UiTextLookup,
        worktreesEnabled: Boolean = true,
    ): List<SessionListProjectPickerRow> {
        val byId = projects.associateBy { it.id }
        return buildList {
            if (state.isEditing) add(SessionListProjectPickerRow.Note(HomeText.reorderNote.resolve(text)))
            filterTabs(projects, text, worktreesEnabled).forEach { tab ->
                val project = byId[tab.id]
                val worktreeCount = if (worktreesEnabled && project != null && project.hasManyWorktrees) project.worktrees.size else 0
                val expanded = worktreeCount > 0 && state.isExpanded(tab.id)
                add(SessionListProjectPickerRow.Scope(tab, worktreeCount = worktreeCount, expanded = expanded))
                if (project == null || !expanded) return@forEach
                state.visibleWorktrees(project.id, worktreeTabs(project, text, worktreesEnabled)).forEach {
                    add(SessionListProjectPickerRow.Worktree(it))
                }
                showAllLabel(state, project.id, project.worktrees.size)?.let { title ->
                    add(SessionListProjectPickerRow.ShowAll(projectId = project.id, title = title.resolve(text)))
                }
            }
        }
    }

    /** "Show all N" / "Show fewer" under expanded project [key] with more than the limit; null otherwise. */
    fun showAllLabel(
        state: HomeProjectPickerState,
        key: String,
        worktreeCount: Int,
    ): UiText? {
        if (worktreeCount <= HomeProjectPickerState.COLLAPSED_WORKTREE_LIMIT) return null
        return if (key in state.showAllProjectKeys) HomeText.showFewer else HomeText.showAll(worktreeCount)
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
     * New session: the draft, starting (when there is none yet) in the filtered worktree, else
     * the filtered project's main checkout; in the All scope, where the most recent of [sessions]
     * runs, else the first project's main checkout. Never asks anything.
     */
    fun newSessionAction(
        projects: List<Project>,
        selectedFilterId: String,
        worktreesEnabled: Boolean = true,
        sessions: List<SessionListSession> = emptyList(),
    ): NewSessionAction {
        if (projects.isEmpty()) return NewSessionAction.None
        if (worktreesEnabled && selectedFilterId != SessionListFilterTab.ALL_ID) {
            projects.forEach { project ->
                project.worktrees.firstOrNull { it.scopeId == selectedFilterId }?.let { worktree ->
                    return NewSessionAction.OpenDraft(start(project, worktree.worktree, worktreesEnabled))
                }
            }
        }
        if (selectedFilterId != SessionListFilterTab.ALL_ID) {
            targetProject(projects, selectedFilterId)?.let { return NewSessionAction.OpenDraft(start(it, null, worktreesEnabled)) }
        }
        val recent = sessions.filter { it.updatedAtEpochMs != null }.maxByOrNull { it.updatedAtEpochMs ?: Long.MIN_VALUE }
        if (recent != null) {
            val directory = ServerPathPolicy.canonical(recent.directory.trim())
            val owner =
                projects.firstOrNull { project -> project.worktrees.any { it.directory == directory } }
                    ?: projects.firstOrNull { it.id == recent.worktree || it.worktree == recent.worktree }
            if (owner != null) {
                val worktree = owner.worktrees.firstOrNull { it.directory == directory }?.worktree
                return NewSessionAction.OpenDraft(start(owner, worktree, worktreesEnabled))
            }
        }
        return NewSessionAction.OpenDraft(start(projects.first(), null, worktreesEnabled))
    }

    /** [project] with [worktree] as the place: its main checkout is Local; none while worktrees are off. */
    private fun start(
        project: Project,
        worktree: HomeWorktree?,
        worktreesEnabled: Boolean,
    ): NewSessionDraftStart {
        val place =
            if (worktreesEnabled && worktree != null && !worktree.isMain && worktree.directory != project.mainCheckoutDirectory) {
                NewSessionPlace.Worktree(worktree.directory, worktree.branch)
            } else {
                NewSessionPlace.Local
            }
        return NewSessionDraftStart(projectKey = project.id, projectDirectory = project.mainCheckoutDirectory, place = place)
    }

    fun terminalAction(projects: List<Project>): TerminalAction {
        if (projects.isEmpty()) {
            return TerminalAction.None
        }
        return TerminalAction.OpenCatalog
    }
}
