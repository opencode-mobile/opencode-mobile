package dev.opencode.mobile.domain.project

import dev.opencode.mobile.domain.filesystem.ServerPathPolicy

/**
 * The server's own projects offered in Add project ("On this server"): no global project, nothing
 * already on home, one row per worktree (the newest), newest activity first, undated last, at
 * most [MAX_SUGGESTIONS] so the folder list stays on screen.
 */
object ServerProjectSuggestions {
    const val GLOBAL_PROJECT_ID = "global"
    const val MAX_SUGGESTIONS = 5

    fun suggestions(
        projects: List<Project>,
        addedWorktrees: Collection<String>,
    ): List<Project> {
        val added = addedWorktrees.mapTo(HashSet()) { ServerPathPolicy.canonical(it) }
        val newestByWorktree = LinkedHashMap<String, Project>()
        for (project in projects) {
            if (project.id == GLOBAL_PROJECT_ID) continue
            val worktree = ServerPathPolicy.canonical(project.worktree)
            if (worktree.isBlank() || worktree == "/") continue
            if (worktree in added) continue
            val current = newestByWorktree[worktree]
            if (current == null || activity(project) > activity(current)) {
                newestByWorktree[worktree] = project
            }
        }
        return newestByWorktree.values.sortedByDescending(::activity).take(MAX_SUGGESTIONS)
    }

    private fun activity(project: Project): Long = project.activityAtEpochMs ?: Long.MIN_VALUE
}
