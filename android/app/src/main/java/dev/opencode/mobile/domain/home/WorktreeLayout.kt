package dev.opencode.mobile.domain.home

import dev.opencode.mobile.domain.filesystem.ServerPathPolicy

/**
 * The worktrees one added path last listed and their branches, so a cold Home labels rows
 * (branch, worktree folder) and the project pill the way the live load will instead of
 * relabeling every row once the inventory and branch answers arrive.
 */
data class WorktreeLayout(
    /** Listed worktree directories, canonical and sorted. */
    val directories: List<String>,
    /** Known branch per canonical directory. */
    val branches: Map<String, String> = emptyMap(),
) {
    companion object {
        /**
         * [addedPath]'s layout from Home's index: the listed [directories] and the branches of
         * those, the added path itself, and the resolution's main checkout.
         */
        fun of(
            addedPath: String,
            directories: Set<String>,
            resolution: ResolvedHomePath?,
            branches: Map<String, String>,
        ): WorktreeLayout {
            val listed = directories.map(::canonical).filter { it.isNotEmpty() }.distinct().sorted()
            val relevant =
                buildSet {
                    addAll(listed)
                    add(canonical(addedPath))
                    resolution?.canonicalWorktree?.let { add(canonical(it)) }
                }
            return WorktreeLayout(directories = listed, branches = branches.filterKeys { it in relevant })
        }

        private fun canonical(path: String): String = ServerPathPolicy.canonical(path.trim())
    }
}
