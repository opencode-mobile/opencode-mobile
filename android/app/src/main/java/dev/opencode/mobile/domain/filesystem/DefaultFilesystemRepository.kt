package dev.opencode.mobile.domain.filesystem

import dev.opencode.mobile.data.remote.FilesystemApi
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.ActiveConnectionManager
import javax.inject.Inject

class DefaultFilesystemRepository
    @Inject
    constructor(
        private val api: FilesystemApi,
        private val adapter: FilesystemDomainAdapter,
        private val activeConnectionManager: ActiveConnectionManager? = null,
    ) : FilesystemRepository {
        override suspend fun getPaths(): ServerPaths =
            if (activeConnectionManager?.currentProtocolOrProbe() == ServerProtocol.V2) {
                adapter.toServerPaths(api.getLocationV2())
            } else {
                adapter.toServerPaths(api.getPaths())
            }

        override suspend fun listDirectory(path: String): List<DirectoryNode> {
            val nodes =
                if (activeConnectionManager?.currentProtocolOrProbe() == ServerProtocol.V2) {
                    api.listFilesV2(absoluteDirectory = path).map { adapter.toDirectoryNode(it, path) }
                } else {
                    api.listFiles(absoluteDirectory = path).map(adapter::toDirectoryNode)
                }
            return DirectoryListingPolicy.prepare(nodes)
        }
    }
