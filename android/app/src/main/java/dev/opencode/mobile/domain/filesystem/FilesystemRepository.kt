package dev.opencode.mobile.domain.filesystem

interface FilesystemRepository {
    suspend fun getPaths(): ServerPaths

    /** Lists [path]'s child folders. Throws [DirectoryNotFoundException] when [path] is not a folder. */
    suspend fun listDirectory(path: String): DirectoryListing
}
