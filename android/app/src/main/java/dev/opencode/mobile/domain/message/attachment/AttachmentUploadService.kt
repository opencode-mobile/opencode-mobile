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

/** How a failed write is read: a changed route falls back silently, anything else is "Upload failed". */
object AttachmentUploadFailure {
    private val contractMissStatuses = setOf(404, 405, 400, 415)

    /**
     * A non-2xx answer. Only 404, 405, 400 and 415 mean the route changed shape. Every other
     * status (401/403, 408, 409, 413, 422, 429, 5xx, ...) is a failure of this upload: "Upload
     * failed", with uploads left on.
     */
    fun classify(status: Int): AttachmentUploadResult =
        if (status in contractMissStatuses) AttachmentUploadResult.ContractMiss else AttachmentUploadResult.TransportFailure
}
