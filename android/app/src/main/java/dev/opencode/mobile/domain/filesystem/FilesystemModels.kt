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
