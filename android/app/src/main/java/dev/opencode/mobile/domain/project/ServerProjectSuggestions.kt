package dev.opencode.mobile.domain.project

import dev.opencode.mobile.domain.filesystem.ServerPathPolicy

/**
 * The server's own projects offered in Add project ("On this server"): repositories only, no global
 * project, nothing already on home, nothing under a temp or hidden folder, one row per worktree
 * (the newest), newest activity first, undated last, at most [MAX_SUGGESTIONS] so the folder list
 * stays on screen.
 *
 * The server creates a project for every folder a request names, so agents and tools leave
 * plain-folder projects behind (scratch, upload, and evidence folders). Only repositories are
 * worth suggesting; Browse folders still adds any folder.
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
            if (project.id == GLOBAL_PROJECT_ID || !project.isRepository) continue
            val worktree = ServerPathPolicy.canonical(project.worktree)
            if (worktree.isBlank() || worktree == "/") continue
            if (worktree in added || isScratchLocation(worktree)) continue
            val current = newestByWorktree[worktree]
            if (current == null || activity(project) > activity(current)) {
                newestByWorktree[worktree] = project
            }
        }
        return newestByWorktree.values.sortedByDescending(::activity).take(MAX_SUGGESTIONS)
    }

    /** System temp folders and any path with a hidden (dot) segment, such as `~/.t3/scratch`. */
    fun isScratchLocation(path: String): Boolean {
        val posix = path.replace('\\', '/').lowercase()
        if (posix.split('/').any { it.startsWith(".") }) return true
        if (TEMP_PREFIXES.any { posix == it || posix.startsWith("$it/") }) return true
        return posix.contains("/appdata/local/temp/") || posix.endsWith("/appdata/local/temp")
    }

    private val TEMP_PREFIXES = listOf("/tmp", "/private/tmp", "/var/folders", "/private/var/folders", "/var/tmp")

    private fun activity(project: Project): Long = project.activityAtEpochMs ?: Long.MIN_VALUE
}
