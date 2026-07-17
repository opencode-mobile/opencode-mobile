package dev.opencode.mobile.domain.filesystem

interface FilesystemRepository {
    suspend fun getPaths(): ServerPaths

    suspend fun listDirectory(path: String): List<DirectoryNode>
}
