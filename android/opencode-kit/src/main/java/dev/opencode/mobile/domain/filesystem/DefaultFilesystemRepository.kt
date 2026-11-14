package dev.opencode.mobile.domain.filesystem

import dev.opencode.mobile.data.remote.FilesystemApi
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.ActiveConnectionManager
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
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

        /**
         * v2 answers a missing folder with 404. v1 answers it, a file, and permission denied alike
         * with 500, so a failure is "not found" only when the nearest ancestor that lists has no
         * folder by the next name.
         */
        override suspend fun listDirectory(path: String): DirectoryListing {
            val protocol = activeConnectionManager?.currentProtocolOrProbe() ?: ServerProtocol.V1
            val nodes =
                try {
                    listNodes(path, protocol)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    if (error.isNotFound() || isMissingFromAncestors(path, protocol)) {
                        throw DirectoryNotFoundException(path, error)
                    }
                    throw error
                }
            return DirectoryListing.from(nodes)
        }

        // Locate at the folder's root so browsing never creates a server project or boots a
        // watcher, and servers that refuse paths outside the location still answer.
        private suspend fun listNodes(
            path: String,
            protocol: ServerProtocol,
        ): List<DirectoryNode> {
            val location = ServerPathPolicy.root(path).ifEmpty { path }
            return if (protocol == ServerProtocol.V2) {
                val envelope = api.listFilesV2(location = location, path = path)
                val base = envelope.location?.directory?.takeIf { it.isNotBlank() } ?: location
                envelope.data.map { adapter.toDirectoryNode(it, base) }
            } else {
                api.listFiles(location = location, path = path).map(adapter::toDirectoryNode)
            }
        }

        /**
         * Walks up from [path] to the nearest ancestor that lists. True when that ancestor has no
         * folder named like the next segment, or an ancestor answers 404; false when the segment
         * exists (a real failure such as permission denied) or no ancestor lists.
         */
        private suspend fun isMissingFromAncestors(
            path: String,
            protocol: ServerProtocol,
        ): Boolean {
            var child = ServerPathPolicy.canonical(path)
            var ancestor = ServerPathPolicy.parentPath(child)
            var levels = 0
            while (ancestor != null && levels < MAX_ANCESTOR_LEVELS) {
                levels++
                val nodes =
                    try {
                        listNodes(ancestor, protocol)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        if (error.isNotFound()) return true
                        child = ancestor
                        ancestor = ServerPathPolicy.parentPath(ancestor)
                        continue
                    }
                val segment = ServerPathPolicy.name(child)
                val ignoreCase = ServerPathPolicy.isWindows(child)
                return DirectoryListingPolicy.directories(nodes).none { it.name.equals(segment, ignoreCase = ignoreCase) }
            }
            return false
        }

        private fun Exception.isNotFound(): Boolean = this is ResponseException && response.status == HttpStatusCode.NotFound

        private companion object {
            const val MAX_ANCESTOR_LEVELS = 16
        }
    }
