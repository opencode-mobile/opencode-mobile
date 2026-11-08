package dev.opencode.mobile.data.remote

import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.applyLocationDirectory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onUpload
import io.ktor.client.plugins.timeout
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/** `POST /api/experimental/fs/write`: raw bytes to an absolute server path (v2, experimental). */
class AttachmentUploadApi private constructor(
    private val httpClient: ClientProvider,
) {
    constructor(httpClient: OpencodeHttpClient) : this(ClientProvider(httpClient::get))

    internal constructor(httpClient: HttpClient) : this(ClientProvider { httpClient })

    /** The raw outcome; [path] is the answer's `data.path` when the 200 body carried one. */
    class Response(
        val status: Int,
        val path: String?,
    ) {
        val isSuccess: Boolean get() = status in 200..299
    }

    suspend fun write(
        serverPath: String,
        file: File,
        directory: String,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): Response {
        val response: HttpResponse =
            httpClient.get().post("api/experimental/fs/write") {
                parameter("path", serverPath)
                applyLocationDirectory(directory)
                // Large files outlast the default REST ceiling; a stalled socket still fails.
                timeout {
                    requestTimeoutMillis = UPLOAD_TIMEOUT_MS
                    socketTimeoutMillis = SOCKET_TIMEOUT_MS
                }
                onUpload { sent, total ->
                    val expected = total ?: file.length()
                    if (expected > 0) onProgress(sent, expected)
                }
                contentType(ContentType.Application.OctetStream)
                setBody(FileContent(file))
            }
        val text = runCatching { response.bodyAsText() }.getOrDefault("")
        return Response(
            status = response.status.value,
            path = if (response.status.isSuccess()) resolvedPath(text) else null,
        )
    }

    private fun resolvedPath(body: String): String? =
        runCatching {
            val data = (json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonObject
            (data?.get("path") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }.getOrNull()

    private class FileContent(
        private val file: File,
    ) : OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType = ContentType.Application.OctetStream
        override val contentLength: Long = file.length()

        override suspend fun writeTo(channel: ByteWriteChannel) {
            file.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    channel.writeFully(buffer, 0, read)
                }
            }
        }
    }

    private fun interface ClientProvider {
        fun get(): HttpClient
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val UPLOAD_TIMEOUT_MS = 30L * 60L * 1_000L
        val SOCKET_TIMEOUT_MS = 60_000L
    }
}
