package dev.opencode.mobile.domain.filesystem

/**
 * Folder listings for Add project. The repository keeps every child folder; the sheet filters
 * by name prefix first and caps what it shows afterwards, so a folder past the cap is still found.
 */
object DirectoryListingPolicy {
    const val MAX_VISIBLE_DIRECTORIES = 200

    /** The rows to show for a filter, and whether more matched than [rows] holds. */
    data class VisibleEntries(
        val rows: List<DirectoryNode>,
        val isTruncated: Boolean,
    )

    /** Every child folder, hidden ones included, without `.` and `..`, sorted case-insensitively. */
    fun directories(nodes: List<DirectoryNode>): List<DirectoryNode> =
        nodes
            .asSequence()
            .filter { it.isDirectory }
            .filter { it.name.isNotBlank() && it.name != "." && it.name != ".." }
            .sortedBy { it.name.lowercase() }
            .toList()

    fun isHidden(node: DirectoryNode): Boolean = node.name.startsWith(".")

    /**
     * [entries] whose name starts with [filter] (case-insensitive), then capped at [limit].
     * Hidden folders match only when [filter] itself starts with `.`.
     */
    fun visibleEntries(
        entries: List<DirectoryNode>,
        filter: String,
        limit: Int = MAX_VISIBLE_DIRECTORIES,
    ): VisibleEntries {
        val showHidden = filter.startsWith(".")
        val matches =
            entries.filter { node ->
                (showHidden || !isHidden(node)) && node.name.startsWith(filter, ignoreCase = true)
            }
        return VisibleEntries(rows = matches.take(limit), isTruncated = matches.size > limit)
    }
}
