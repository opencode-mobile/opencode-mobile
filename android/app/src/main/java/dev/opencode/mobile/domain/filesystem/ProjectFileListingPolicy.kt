package dev.opencode.mobile.domain.filesystem

/**
 * File Browser listing rules: folders first, then case-insensitive name; dot-prefixed entries
 * only when Show hidden files is on. No cap and files stay (unlike [DirectoryListingPolicy],
 * which serves Add Project).
 */
object ProjectFileListingPolicy {
    private val order =
        compareByDescending<ProjectEntry> { it.isDirectory }
            .thenBy { it.name.lowercase() }
            .thenBy { it.name }

    fun sorted(entries: List<ProjectEntry>): List<ProjectEntry> =
        entries
            .filter { it.name.isNotBlank() && it.name != "." && it.name != ".." }
            .distinctBy { it.relativePath }
            .sortedWith(order)

    fun visible(
        entries: List<ProjectEntry>,
        showHidden: Boolean,
    ): List<ProjectEntry> = if (showHidden) entries else entries.filterNot { it.isHidden }
}
