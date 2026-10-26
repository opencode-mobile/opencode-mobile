package dev.opencode.mobile.domain.message

import java.io.File

sealed interface AttachmentUploadResult {
    /** The server wrote the file; [serverPath] is its resolved absolute path. */
    data class Uploaded(
        val serverPath: String,
    ) : AttachmentUploadResult

    /** The route is not what the app sends to (renamed, removed, reshaped): fall back to data URLs. */
    data object ContractMiss : AttachmentUploadResult

    /** Timeout, lost connection, 5xx, or rejected credentials: the file may be fine, the upload failed. */
    data object TransportFailure : AttachmentUploadResult
}

/** Streams attachments to the server (`POST /api/experimental/fs/write`, v2 releases that declare it). */
interface AttachmentUploadService {
    /** The connected server declared the write route and a tmp directory; off until a probe says so. */
    fun isAvailable(): Boolean

    /**
     * Streams [file] to a fresh server path under the server's tmp directory. Cancelling the
     * calling coroutine aborts the request. [onProgress] gets `(sent, total)` bytes.
     */
    suspend fun upload(
        file: File,
        filename: String,
        directory: String,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): AttachmentUploadResult

    /** A live call showed the route is not what the contract promised: stop offering uploads. */
    fun onContractMiss()
}

/** v1 servers, previews, and tests: nothing can upload. */
object UnavailableAttachmentUploadService : AttachmentUploadService {
    override fun isAvailable(): Boolean = false

    override suspend fun upload(
        file: File,
        filename: String,
        directory: String,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): AttachmentUploadResult = AttachmentUploadResult.ContractMiss

    override fun onContractMiss() = Unit
}

object AttachmentUploadFailure {
    private val transportStatuses = setOf(401, 403, 408, 429)

    /**
     * A non-2xx answer. 404, 405, 400 and 415 mean the route changed shape; any other 4xx short of
     * auth, timeout and rate limiting is read the same way, since the app only fails loudly when
     * the file itself could not get through.
     */
    fun classify(status: Int): AttachmentUploadResult =
        when {
            status in transportStatuses || status >= 500 -> AttachmentUploadResult.TransportFailure
            status in 400..499 -> AttachmentUploadResult.ContractMiss
            else -> AttachmentUploadResult.TransportFailure
        }

    /** `<tmp>/uploads/<uuid>/<filename>`, one directory per file so names never collide. */
    fun serverPath(
        tmp: String,
        uuid: String,
        filename: String,
    ): String {
        val separator = if (tmp.contains('\\') && !tmp.contains('/')) '\\' else '/'
        val safeName = filename.replace('/', '_').replace('\\', '_').ifBlank { "attachment" }
        return "${tmp.trimEnd('/', '\\')}${separator}uploads$separator$uuid$separator$safeName"
    }

    /** Prompt text line naming an uploaded file, shared by both apps. */
    fun promptLine(serverPath: String): String = "Attached file: `$serverPath`"

    /** [text] followed by one [promptLine] per path, separated by a newline. */
    fun promptText(
        text: String,
        serverPaths: List<String>,
    ): String {
        if (serverPaths.isEmpty()) return text
        val lines = serverPaths.joinToString("\n", transform = ::promptLine)
        return if (text.isBlank()) lines else "$text\n$lines"
    }
}
