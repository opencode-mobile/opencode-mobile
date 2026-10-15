package dev.opencode.mobile.domain.filesystem

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

    private fun isVisibleDirectoryName(name: String): Boolean {
        if (name.isBlank() || name == "." || name == "..") return false
        return !name.startsWith(".")
    }
}
