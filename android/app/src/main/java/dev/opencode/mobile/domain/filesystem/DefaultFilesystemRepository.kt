package dev.opencode.mobile.domain.filesystem

import dev.opencode.mobile.data.remote.FilesystemApi
import javax.inject.Inject

class DefaultFilesystemRepository
    @Inject
    constructor(
        private val api: FilesystemApi,
        private val adapter: FilesystemDomainAdapter,
    ) : FilesystemRepository {
        override suspend fun getPaths(): ServerPaths = adapter.toServerPaths(api.getPaths())

        override suspend fun listDirectory(path: String): List<DirectoryNode> =
            DirectoryListingPolicy.prepare(api.listFiles(path).map(adapter::toDirectoryNode))
    }
