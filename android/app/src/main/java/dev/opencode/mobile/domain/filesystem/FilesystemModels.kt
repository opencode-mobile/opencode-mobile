package dev.opencode.mobile.domain.filesystem

data class ServerPaths(
    val home: String,
    val directory: String,
    val worktree: String,
)

data class DirectoryNode(
    val name: String,
    val absolutePath: String,
    val isDirectory: Boolean,
)

/**
 * One folder's listing for Add project: every child folder (hidden ones included, sorted), and
 * whether the folder holds a `.git` entry (a folder in a repository, a file in a git worktree).
 */
data class DirectoryListing(
    val folders: List<DirectoryNode>,
    val isGitRepository: Boolean,
) {
    /** Child folders whose name does not start with `.`. */
    val visibleFolderCount: Int
        get() = folders.count { !DirectoryListingPolicy.isHidden(it) }

    companion object {
        /** Folders and the `.git` marker from a raw listing of files and folders. */
        fun from(nodes: List<DirectoryNode>): DirectoryListing =
            DirectoryListing(
                folders = DirectoryListingPolicy.directories(nodes),
                isGitRepository = nodes.any { it.name == ".git" },
            )
    }
}

/** The listed path does not exist, or is not a folder. */
class DirectoryNotFoundException(
    val path: String,
    cause: Throwable? = null,
) : Exception("Folder not found: $path", cause)
