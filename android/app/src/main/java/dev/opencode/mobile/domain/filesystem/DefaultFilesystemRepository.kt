package dev.opencode.mobile.domain.filesystem

import dev.opencode.mobile.data.remote.FilesystemApi
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.project.ProjectRepository
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

class DefaultFilesystemRepository
    @Inject
    constructor(
        private val api: FilesystemApi,
        private val adapter: FilesystemDomainAdapter,
        private val activeConnectionManager: ActiveConnectionManager? = null,
        private val projectRepository: ProjectRepository? = null,
    ) : FilesystemRepository {
        private val protocolMutex = Mutex()

        private suspend fun isV2(): Boolean {
            protocolMutex.withLock {
                activeConnectionManager?.currentProtocol()?.let { return it == ServerProtocol.V2 }
                val projectRepository = projectRepository ?: return false
                runCatching { projectRepository.probeServer() }
                return activeConnectionManager?.currentProtocol() == ServerProtocol.V2
            }
        }

        override suspend fun getPaths(): ServerPaths =
            if (isV2()) {
                adapter.toServerPaths(api.getLocationV2())
            } else {
                adapter.toServerPaths(api.getPaths())
            }

        override suspend fun listDirectory(path: String): List<DirectoryNode> {
            val nodes =
                if (isV2()) {
                    api.listFilesV2(absoluteDirectory = path).map { adapter.toDirectoryNode(it, path) }
                } else {
                    api.listFiles(absoluteDirectory = path).map(adapter::toDirectoryNode)
                }
            return DirectoryListingPolicy.prepare(nodes)
        }
    }
