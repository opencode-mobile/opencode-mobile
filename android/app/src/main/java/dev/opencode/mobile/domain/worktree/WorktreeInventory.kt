package dev.opencode.mobile.domain.worktree

import dev.opencode.mobile.domain.filesystem.ServerPathPolicy

/** Read-only worktree inventory for one added project. */
sealed class WorktreeInventory {
    data object Unloaded : WorktreeInventory()

    data class Loaded(
        val directories: Set<String>,
    ) : WorktreeInventory()

    data object Failed : WorktreeInventory()

    companion object {
        fun loaded(directories: List<String>): Loaded =
            Loaded(
                directories
                    .map { ServerPathPolicy.canonical(it.trim()) }
                    .filter { it.isNotEmpty() }
                    .toSet(),
            )
    }
}

/**
 * Home badges a row only when that added project's inventory has loaded and the
 * session directory is a listed worktree other than the added path.
 */
object WorktreeMark {
    fun showsBadge(
        sessionDirectory: String?,
        addedPath: String,
        inventory: WorktreeInventory,
    ): Boolean {
        val loaded = inventory as? WorktreeInventory.Loaded ?: return false
        val directory = sessionDirectory?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val canonicalDirectory = ServerPathPolicy.canonical(directory)
        if (canonicalDirectory == ServerPathPolicy.canonical(addedPath.trim())) return false
        return canonicalDirectory in loaded.directories
    }

    fun checkoutName(directory: String): String = ServerPathPolicy.name(ServerPathPolicy.canonical(directory))

    /**
     * Session Chat names the checkout when the bound directory meets the Home badge
     * predicate for any added project. Returns null otherwise.
     */
    fun checkoutNameIfMarked(
        boundDirectory: String,
        addedPaths: List<String>,
        inventoryFor: (String) -> WorktreeInventory,
    ): String? {
        val marked =
            addedPaths.any { added ->
                showsBadge(boundDirectory, added, inventoryFor(added))
            }
        return if (marked) checkoutName(boundDirectory) else null
    }
}
