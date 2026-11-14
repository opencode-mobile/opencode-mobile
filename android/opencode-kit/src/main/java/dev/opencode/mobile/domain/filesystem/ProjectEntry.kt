package dev.opencode.mobile.domain.filesystem

/** One File Browser row: a file or folder under the Session Chat worktree. */
data class ProjectEntry(
    val name: String,
    /** Relative to the worktree, `/`-separated, no trailing slash. */
    val relativePath: String,
    val isDirectory: Boolean,
    /** v1 `.gitignore` match. v2 has no ignored flag, so it is always false there. */
    val isIgnored: Boolean = false,
) {
    val isHidden: Boolean get() = name.startsWith(".")

    /** Parent folder relative path, or empty for a root child. */
    val parentPath: String get() = relativePath.substringBeforeLast('/', "")
}
