package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.AttachmentKind
import dev.opencode.mobile.domain.message.AttachmentDataUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A user-facing reason an outbound attachment could not be sent to a T3 thread. */
class T3AttachmentException(
    message: String,
) : Exception(message)

/** Moves attachment bytes to a signed T3 upload URL; returns the HTTP status code. */
fun interface T3AttachmentTransport {
    suspend fun upload(
        url: String,
        bytes: ByteArray,
        mimeType: String,
        headers: Map<String, String>,
    ): Int
}

@Singleton
class OkHttpT3AttachmentTransport
    @Inject
    constructor() : T3AttachmentTransport {
        private val client =
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()

        override suspend fun upload(
            url: String,
            bytes: ByteArray,
            mimeType: String,
            headers: Map<String, String>,
        ): Int =
            withContext(Dispatchers.IO) {
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .apply { headers.forEach { (name, value) -> header(name, value) } }
                        .post(bytes.toRequestBody(mimeType.toMediaType()))
                        .build()
                client.newCall(request).execute().use { response -> response.code }
            }
    }

/**
 * Outbound T3 attachment sequence: `attachments.createUploadUrl` over the RPC
 * socket, POST the exact bytes to the signed relative URL on the environment
 * origin, then reference the stored id as a `ChatAttachment` in
 * `message.dispatch`. Images are `type: image`. A PDF or text file is `type: file`
 * when the environment advertised `fileAttachments`; otherwise it is rejected.
 */
@Singleton
class T3AttachmentUploader internal constructor(
    private val socket: T3Socket,
    private val transport: T3AttachmentTransport,
    private val readBytes: (Attachment) -> ByteArray?,
    private val connectTimeoutMs: Long,
) {
    @Inject
    constructor(
        socket: T3Socket,
        transport: OkHttpT3AttachmentTransport,
    ) : this(socket, transport, ::defaultBytes, CONNECT_WAIT_MS)

    internal constructor(
        socket: T3Socket,
        transport: T3AttachmentTransport,
        readBytes: (Attachment) -> ByteArray? = ::defaultBytes,
    ) : this(socket, transport, readBytes, 0L)

    suspend fun upload(
        profile: ConnectionProfile,
        attachments: List<Attachment>,
        maxFileBytes: Long? = null,
    ): List<T3Attachment> {
        if (attachments.isEmpty()) return emptyList()
        val prepared = attachments.map { attachment -> prepare(attachment, maxFileBytes) }
        awaitConnected()
        val uploaded = mutableListOf<T3Attachment>()
        for (item in prepared) {
            val stored =
                runCatching { uploadOne(profile, item) }.getOrElse { error ->
                    uploaded.forEach { discard(it.id) }
                    throw error
                }
            uploaded += stored
        }
        return uploaded
    }

    /**
     * An image stays on the image contract. A PDF or text file uses the file
     * contract when [maxFileBytes] is the server's advertised limit.
     */
    private fun prepare(
        attachment: Attachment,
        maxFileBytes: Long?,
    ): PreparedUpload {
        val mime = attachment.mime.trim().lowercase()
        val image = attachment.kind == AttachmentKind.Image && mime in T3Assets.SUPPORTED_IMAGE_MIMES
        if (image) {
            if (attachment.byteCount > T3Assets.MAX_IMAGE_BYTES) {
                throw T3AttachmentException(oversizeMessage(attachment.filename))
            }
            val bytes = read(attachment)
            if (bytes.size > T3Assets.MAX_IMAGE_BYTES) throw T3AttachmentException(oversizeMessage(attachment.filename))
            return PreparedUpload(attachment.filename.ifBlank { "image" }, mime, bytes, "image")
        }
        if (maxFileBytes == null || mime.isEmpty() ||
            (attachment.kind != AttachmentKind.Pdf && attachment.kind != AttachmentKind.Text)
        ) {
            throw T3AttachmentException(unsupportedMessage(attachment.filename))
        }
        if (attachment.byteCount > maxFileBytes) {
            throw T3AttachmentException(fileOversizeMessage(attachment.filename, maxFileBytes))
        }
        val bytes = read(attachment)
        if (bytes.size.toLong() > maxFileBytes) {
            throw T3AttachmentException(fileOversizeMessage(attachment.filename, maxFileBytes))
        }
        return PreparedUpload(attachment.filename.ifBlank { "file" }, mime, bytes, "file")
    }

    private fun read(attachment: Attachment): ByteArray {
        val bytes = readBytes(attachment) ?: throw T3AttachmentException("Could not read '${attachment.filename}'.")
        if (bytes.isEmpty()) throw T3AttachmentException("'${attachment.filename}' is empty.")
        return bytes
    }

    private suspend fun uploadOne(
        profile: ConnectionProfile,
        item: PreparedUpload,
    ): T3Attachment {
        val reply =
            socket.request(
                T3Assets.CREATE_UPLOAD_URL,
                T3Assets.createUploadUrlPayload(
                    name = item.name,
                    mimeType = item.mimeType,
                    sizeBytes = item.bytes.size.toLong(),
                    type = item.type,
                ),
            )
        val target = T3Assets.uploadTarget(reply) ?: throw T3RpcException("T3 did not return an upload URL.")
        val status =
            runCatching {
                transport.upload(
                    url = T3Urls.join(profile.baseUrl, target.relativeUrl),
                    bytes = item.bytes,
                    mimeType = item.mimeType,
                    headers = mapOf("Authorization" to "Bearer ${profile.password}"),
                )
            }.getOrElse { error ->
                discard(target.attachmentId)
                throw T3AttachmentException("Uploading '${item.name}' failed: ${error.message ?: "network error"}")
            }
        if (status !in 200..299) {
            discard(target.attachmentId)
            if (status == 401 || status == 403) throw T3HttpException.from(status, "")
            throw T3AttachmentException("Uploading '${item.name}' failed ($status).")
        }
        return T3Attachment(
            id = target.attachmentId,
            type = item.type,
            name = item.name,
            mimeType = item.mimeType,
            sizeBytes = item.bytes.size.toLong(),
        )
    }

    private data class PreparedUpload(
        val name: String,
        val mimeType: String,
        val bytes: ByteArray,
        val type: String,
    )

    private suspend fun discard(attachmentId: String) {
        runCatching { socket.request(T3Assets.DELETE_UPLOAD, T3Assets.deleteUploadPayload(attachmentId)) }
    }

    private suspend fun awaitConnected() {
        if (socket.state.value == T3SocketState.Connected) return
        val connected =
            withTimeoutOrNull(connectTimeoutMs) { socket.state.first { it == T3SocketState.Connected } } != null
        if (!connected) throw T3ConnectionLostException("T3 websocket is not connected")
    }

    companion object {
        private const val CONNECT_WAIT_MS = 10_000L

        fun unsupportedMessage(filename: String): String =
            "'$filename' is not a supported image. T3 threads accept PNG, JPEG, GIF, or WebP images only."

        fun oversizeMessage(filename: String): String = "'$filename' exceeds the 10 MB image limit."

        fun fileOversizeMessage(
            filename: String,
            maxBytes: Long,
        ): String = "'$filename' exceeds the ${T3Assets.fileLimitLabel(maxBytes)} file limit."

        private fun defaultBytes(attachment: Attachment): ByteArray? {
            attachment.localPath?.let { path ->
                return runCatching { File(path).readBytes() }.getOrNull()
            }
            return AttachmentDataUrl.decode(attachment.dataUrl, attachment.mime)
        }
    }
}
