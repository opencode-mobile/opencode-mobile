package dev.opencode.mobile.domain.filesystem

import java.io.File

object DirectoryListingPolicy {
    const val MAX_VISIBLE_DIRECTORIES = 200

    fun prepare(nodes: List<DirectoryNode>): List<DirectoryNode> =
        nodes
            .asSequence()
            .filter { it.isDirectory }
            .filter { isVisibleDirectoryName(it.name) }
            .sortedBy { it.name.lowercase() }
            .take(MAX_VISIBLE_DIRECTORIES)
            .toList()

    fun parentPath(path: String): String? {
        if (path.isBlank() || path == "/") return null
        val parent = File(path).parent ?: return null
        return parent.ifBlank { "/" }
    }

    private fun isVisibleDirectoryName(name: String): Boolean {
        if (name.isBlank() || name == "." || name == "..") return false
        return !name.startsWith(".")
    }
}
