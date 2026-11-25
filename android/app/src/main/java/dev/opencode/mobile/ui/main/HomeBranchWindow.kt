package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.session.SessionSummary

/**
 * The worktree directories Home looks a branch up for. Every directory asked about makes the
 * server open a location (and start its MCP servers), so Home asks for the few it shows and
 * never for the whole inventory of a repository with dozens of worktrees: each project's main
 * checkout (the New session label) and the directories of the newest [VISIBLE_ROWS] session
 * rows. Other worktrees keep their folder name until they have a row in that window; a branch
 * the Session Cache already knows still paints from the first frame.
 */
internal object HomeBranchWindow {
    /** About three phone screens of Home rows. */
    const val VISIBLE_ROWS = 20

    fun directories(
        projects: List<HomeProject>,
        sections: List<ProjectSectionUi>,
        extraSessions: Map<String, List<SessionSummary>>,
    ): List<String> {
        val worktrees =
            projects
                .filter { it.projectId != null }
                .flatMap { project -> project.worktrees }
        if (worktrees.isEmpty()) return emptyList()
        val known = worktrees.map { it.directory }.toSet()
        val mains = worktrees.filter { it.isMain }.map { it.directory }
        val rows =
            sections.flatMap { section -> section.allSessions.map { section.worktree to it } } +
                extraSessions.flatMap { (directory, sessions) -> sessions.map { directory to it } }
        val rowDirectories =
            rows
                .sortedByDescending { (_, summary) -> summary.updatedAtEpochMs ?: Long.MIN_VALUE }
                .take(VISIBLE_ROWS)
                .map { (listed, summary) ->
                    ServerPathPolicy.canonical((summary.directory?.takeIf { it.isNotBlank() } ?: listed).trim())
                }.filter { it in known }
        return (mains + rowDirectories).distinct()
    }
}
