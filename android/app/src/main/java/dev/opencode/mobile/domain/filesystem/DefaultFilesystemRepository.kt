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

        // Locate at the folder's root so browsing never creates a server project or boots a
        // watcher, and servers that refuse paths outside the location still answer.
        override suspend fun listDirectory(path: String): List<DirectoryNode> {
            val location = ServerPathPolicy.root(path).ifEmpty { path }
            val nodes =
                if (activeConnectionManager?.currentProtocolOrProbe() == ServerProtocol.V2) {
                    val envelope = api.listFilesV2(location = location, path = path)
                    val base = envelope.location?.directory?.takeIf { it.isNotBlank() } ?: location
                    envelope.data.map { adapter.toDirectoryNode(it, base) }
                } else {
                    api.listFiles(location = location, path = path).map(adapter::toDirectoryNode)
                }
            return DirectoryListingPolicy.prepare(nodes)
        }
    }
