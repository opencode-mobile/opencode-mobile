package dev.opencode.mobile.domain.filesystem

interface FilesystemRepository {
    suspend fun getPaths(): ServerPaths

    /** Lists [path]'s child folders. Throws [DirectoryNotFoundException] when [path] is not a folder. */
    suspend fun listDirectory(path: String): DirectoryListing
}

/** Session Chat tests that never open Add project. */
object UnavailableFilesystemRepository : FilesystemRepository {
    override suspend fun getPaths(): ServerPaths = throw IllegalStateException("No filesystem")

    override suspend fun listDirectory(path: String): DirectoryListing = throw IllegalStateException("No filesystem")
}
