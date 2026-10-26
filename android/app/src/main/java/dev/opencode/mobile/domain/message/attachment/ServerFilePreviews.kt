package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.FilesystemApi
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** Reads files that live on the server (an attachment named in a prompt) for timeline previews. */
interface ServerFilePreviews {
    /** The file's bytes, or null on any failure or when it is larger than [maxBytes]. */
    suspend fun read(
        serverPath: String,
        sessionDirectory: String,
        maxBytes: Int,
    ): ByteArray?
}

/** Previews and tests that have no server. */
object UnavailableServerFilePreviews : ServerFilePreviews {
    override suspend fun read(
        serverPath: String,
        sessionDirectory: String,
        maxBytes: Int,
    ): ByteArray? = null
}

@Singleton
class DefaultServerFilePreviews
    @Inject
    constructor(
        private val filesystemApi: FilesystemApi,
    ) : ServerFilePreviews {
        override suspend fun read(
            serverPath: String,
            sessionDirectory: String,
            maxBytes: Int,
        ): ByteArray? =
            try {
                val (location, relative) = previewLocation(serverPath, sessionDirectory)
                filesystemApi.readServerFileV2(location, relative, maxBytes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }

        internal companion object {
            /**
             * The v2 file read route only serves files inside its `location[directory]`, so a file
             * outside the project is read with the folder that holds it as the location.
             */
            fun previewLocation(
                serverPath: String,
                sessionDirectory: String,
            ): Pair<String, String> {
                val separator = maxOf(serverPath.lastIndexOf('/'), serverPath.lastIndexOf('\\'))
                if (separator <= 0) return sessionDirectory to serverPath
                return serverPath.substring(0, separator) to serverPath.substring(separator + 1)
            }
        }
    }
