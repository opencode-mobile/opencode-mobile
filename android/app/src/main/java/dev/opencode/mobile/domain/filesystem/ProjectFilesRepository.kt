package dev.opencode.mobile.domain.filesystem

import dev.opencode.mobile.data.remote.FilesystemApi
import dev.opencode.mobile.data.remote.V1FileContentResponse
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.ActiveConnectionManager
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Read-only File Browser over the Session Chat worktree. Paths are relative to [worktree];
 * failures surface as [ProjectFilesException].
 */
interface ProjectFilesRepository {
    /** Children of [relativeDirectory] (empty for the worktree root), sorted folders first. */
    suspend fun listEntries(
        worktree: String,
        relativeDirectory: String,
    ): List<ProjectEntry>

    /** Server fuzzy find over files only. */
    suspend fun findFiles(
        worktree: String,
        query: String,
        limit: Int = ProjectFileLimits.FIND_LIMIT,
    ): List<ProjectEntry>

    suspend fun readFile(
        worktree: String,
        relativePath: String,
    ): ProjectFileContent
}

class DefaultProjectFilesRepository
    @Inject
    constructor(
        private val api: FilesystemApi,
        private val adapter: FilesystemDomainAdapter,
        private val activeConnectionManager: ActiveConnectionManager? = null,
    ) : ProjectFilesRepository {
        private suspend fun isV2(): Boolean = activeConnectionManager?.currentProtocolOrProbe() == ServerProtocol.V2

        override suspend fun listEntries(
            worktree: String,
            relativeDirectory: String,
        ): List<ProjectEntry> =
            request("Couldn't load folder") {
                val directory = relativeDirectory.trim('/')
                val entries =
                    if (isV2()) {
                        api.listProjectEntriesV2(worktree, directory).map { dto ->
                            adapter.toProjectEntry(dto).withParent(directory)
                        }
                    } else {
                        api.listProjectEntries(worktree, directory.ifEmpty { "." }).map(adapter::toProjectEntry)
                    }
                ProjectFileListingPolicy.sorted(entries)
            }

        override suspend fun findFiles(
            worktree: String,
            query: String,
            limit: Int,
        ): List<ProjectEntry> =
            request("Couldn't search files") {
                if (isV2()) {
                    api
                        .findProjectFilesV2(worktree, query, limit)
                        .map(adapter::toProjectEntry)
                        .filterNot { it.isDirectory }
                } else {
                    api.findProjectFiles(worktree, query, limit).map(adapter::toProjectFileEntry)
                }.filter { it.relativePath.isNotEmpty() }
                    .distinctBy { it.relativePath }
            }

        override suspend fun readFile(
            worktree: String,
            relativePath: String,
        ): ProjectFileContent =
            request("Couldn't open file") {
                val path = relativePath.trim('/')
                if (isV2()) readV2(worktree, path) else readV1(worktree, path)
            }

        private suspend fun readV1(
            worktree: String,
            path: String,
        ): ProjectFileContent =
            when (val response = api.readProjectFile(worktree, path, ProjectFileLimits.V1_BODY_BYTES)) {
                V1FileContentResponse.TooLarge -> ProjectFileContentDecoder.v1TooLarge()
                is V1FileContentResponse.Content ->
                    ProjectFileContentDecoder.decodeV1(
                        path = path,
                        type = response.dto.type,
                        content = response.dto.content,
                        encoding = response.dto.encoding,
                        mimeType = response.dto.mimeType,
                    )
            }

        private suspend fun readV2(
            worktree: String,
            path: String,
        ): ProjectFileContent {
            val asImage = ProjectFileContentDecoder.isImagePath(path)
            val read =
                if (asImage) {
                    api.readProjectFileV2(worktree, path, byteLimit = ProjectFileLimits.IMAGE_PREVIEW_BYTES, rangeBytes = null)
                } else {
                    api.readProjectFileV2(
                        worktree,
                        path,
                        byteLimit = ProjectFileLimits.TEXT_PREVIEW_BYTES,
                        rangeBytes = ProjectFileLimits.TEXT_PREVIEW_BYTES,
                    )
                }
            // A 206 that covers the whole range still hides the rest of the file.
            val exceeded =
                read.exceededLimit ||
                    (read.isPartial && (read.totalBytes ?: 0L) > read.bytes.size)
            return ProjectFileContentDecoder.decodeV2(
                path = path,
                bytes = read.bytes,
                exceededLimit = exceeded,
                totalBytes = read.totalBytes,
                contentType = read.contentType,
                requestedAsImage = asImage,
            )
        }

        private inline fun <T> request(
            failure: String,
            block: () -> T,
        ): T =
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ProjectFilesException) {
                throw error
            } catch (error: Exception) {
                throw ProjectFilesException(failure, detail = describe(error), cause = error)
            }

        private fun describe(error: Exception): String =
            when (error) {
                is ResponseException -> "The server responded with ${error.response.status.value}."
                else -> "Check the connection to the server and try again."
            }

        private fun ProjectEntry.withParent(directory: String): ProjectEntry {
            // Older servers may return names only; newer ones return paths from the location root.
            if (directory.isEmpty() || relativePath.startsWith("$directory/")) return this
            return copy(relativePath = "$directory/$relativePath")
        }
    }
