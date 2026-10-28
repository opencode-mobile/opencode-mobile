package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.connection.ConnectionEngine
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.AttachmentKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class T3AttachmentUploaderTest {
    private val profile =
        ConnectionProfile(
            baseUrl = "https://t3.example.com/",
            username = ConnectionEngine.T3_USERNAME,
            password = "bearer-token",
            engine = ConnectionEngine.T3,
        )

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

    private fun image(
        id: String = "a1",
        name: String = "shot.png",
        mime: String = "image/png",
        kind: AttachmentKind = AttachmentKind.Image,
        byteCount: Long = png.size.toLong(),
    ) = Attachment(id = id, filename = name, mime = mime, kind = kind, dataUrl = "", byteCount = byteCount)

    @Test
    fun uploadSequenceMintsPostsAndReturnsChatAttachment() =
        runTest {
            val socket = RecordingSocket()
            val transport = RecordingTransport()
            val uploader = T3AttachmentUploader(socket, transport, readBytes = { png })

            val stored = uploader.upload(profile, listOf(image()))

            val mint = socket.requests.single()
            assertEquals(T3Assets.CREATE_UPLOAD_URL, mint.first)
            assertEquals("image", mint.second["type"]!!.jsonPrimitive.content)
            assertEquals("shot.png", mint.second["name"]!!.jsonPrimitive.content)
            assertEquals("image/png", mint.second["mimeType"]!!.jsonPrimitive.content)
            assertEquals(png.size.toLong(), mint.second["sizeBytes"]!!.jsonPrimitive.content.toLong())

            val post = transport.uploads.single()
            assertEquals("https://t3.example.com/api/attachments/upload/sig-1", post.url)
            assertEquals("image/png", post.mimeType)
            assertEquals("Bearer bearer-token", post.headers["Authorization"])
            assertTrue(post.bytes.contentEquals(png))

            assertEquals(
                listOf(T3Attachment(id = "pending-1", type = "image", name = "shot.png", mimeType = "image/png", sizeBytes = png.size.toLong())),
                stored,
            )
        }

    @Test
    fun dispatchPayloadCarriesUploadedAttachments() {
        val stored = T3Attachment(id = "pending-1", type = "image", name = "shot.png", mimeType = "image/png", sizeBytes = 7)
        val command =
            T3Commands.messageDispatch(
                threadId = "thread-1",
                text = "",
                modelSelection = null,
                running = false,
                attachments = listOf(stored),
            )
        val attachments = command["attachments"] as JsonArray
        val entry = attachments.single().jsonObject
        assertEquals("", command["text"]!!.jsonPrimitive.content)
        assertEquals("image", entry["type"]!!.jsonPrimitive.content)
        assertEquals("pending-1", entry["id"]!!.jsonPrimitive.content)
        assertEquals("shot.png", entry["name"]!!.jsonPrimitive.content)
        assertEquals("image/png", entry["mimeType"]!!.jsonPrimitive.content)
        assertEquals(7L, entry["sizeBytes"]!!.jsonPrimitive.content.toLong())
        assertNull(entry["dataUrl"])
    }

    @Test
    fun oversizeImageIsRejectedBeforeAnyUpload() =
        runTest {
            val socket = RecordingSocket()
            val transport = RecordingTransport()
            val uploader = T3AttachmentUploader(socket, transport, readBytes = { png })
            val big = image(name = "huge.png", byteCount = T3Assets.MAX_IMAGE_BYTES + 1L)

            try {
                uploader.upload(profile, listOf(image(), big))
                fail("expected rejection")
            } catch (error: T3AttachmentException) {
                assertEquals(T3AttachmentUploader.oversizeMessage("huge.png"), error.message)
            }
            assertTrue(socket.requests.isEmpty())
            assertTrue(transport.uploads.isEmpty())
        }

    @Test
    fun nonImageAttachmentIsRejectedWithUserFacingMessage() =
        runTest {
            val socket = RecordingSocket()
            val transport = RecordingTransport()
            val uploader = T3AttachmentUploader(socket, transport, readBytes = { png })
            val pdf = image(name = "spec.pdf", mime = "application/pdf", kind = AttachmentKind.Pdf)

            try {
                uploader.upload(profile, listOf(pdf))
                fail("expected rejection")
            } catch (error: T3AttachmentException) {
                assertEquals(T3AttachmentUploader.unsupportedMessage("spec.pdf"), error.message)
            }
            assertTrue(socket.requests.isEmpty())
            assertTrue(transport.uploads.isEmpty())
        }

    @Test
    fun pdfUploadsAsFileWhenTheServerAllowsFiles() =
        runTest {
            val socket = RecordingSocket()
            val transport = RecordingTransport()
            val uploader = T3AttachmentUploader(socket, transport, readBytes = { png })
            val pdf = image(name = "spec.pdf", mime = "application/pdf", kind = AttachmentKind.Pdf)

            val stored = uploader.upload(profile, listOf(pdf), maxFileBytes = 1024)

            val mint = socket.requests.single()
            assertEquals("file", mint.second["type"]!!.jsonPrimitive.content)
            assertEquals("spec.pdf", mint.second["name"]!!.jsonPrimitive.content)
            assertEquals("application/pdf", mint.second["mimeType"]!!.jsonPrimitive.content)
            assertEquals("file", stored.single().type)
            assertEquals("application/pdf", transport.uploads.single().mimeType)
        }

    @Test
    fun textFileUploadsAsFile() =
        runTest {
            val socket = RecordingSocket()
            val uploader = T3AttachmentUploader(socket, RecordingTransport(), readBytes = { "hello".toByteArray() })
            val note = image(name = "notes.txt", mime = "text/plain", kind = AttachmentKind.Text, byteCount = 5)

            val stored = uploader.upload(profile, listOf(note), maxFileBytes = 1024)

            assertEquals("file", stored.single().type)
            assertEquals("text/plain", stored.single().mimeType)
        }

    @Test
    fun oversizeFileIsRejectedBeforeAnyUpload() =
        runTest {
            val socket = RecordingSocket()
            val transport = RecordingTransport()
            val uploader = T3AttachmentUploader(socket, transport, readBytes = { png })
            val pdf = image(name = "spec.pdf", mime = "application/pdf", kind = AttachmentKind.Pdf, byteCount = 2048)

            try {
                uploader.upload(profile, listOf(pdf), maxFileBytes = 1024)
                fail("expected rejection")
            } catch (error: T3AttachmentException) {
                assertEquals(T3AttachmentUploader.fileOversizeMessage("spec.pdf", 1024), error.message)
            }
            assertTrue(socket.requests.isEmpty())
            assertTrue(transport.uploads.isEmpty())
        }

    @Test
    fun unsupportedImageMimeIsRejected() =
        runTest {
            val uploader = T3AttachmentUploader(RecordingSocket(), RecordingTransport(), readBytes = { png })
            try {
                uploader.upload(profile, listOf(image(name = "icon.svg", mime = "image/svg+xml")))
                fail("expected rejection")
            } catch (error: T3AttachmentException) {
                assertEquals(T3AttachmentUploader.unsupportedMessage("icon.svg"), error.message)
            }
        }

    @Test
    fun failedTransferDeletesPendingUploadAndSurfacesError() =
        runTest {
            val socket = RecordingSocket()
            val transport = RecordingTransport(status = 500)
            val uploader = T3AttachmentUploader(socket, transport, readBytes = { png })

            try {
                uploader.upload(profile, listOf(image()))
                fail("expected failure")
            } catch (error: T3AttachmentException) {
                assertEquals("Uploading 'shot.png' failed (500).", error.message)
            }
            assertEquals(listOf(T3Assets.CREATE_UPLOAD_URL, T3Assets.DELETE_UPLOAD), socket.requests.map { it.first })
            assertEquals("pending-1", socket.requests.last().second["attachmentId"]!!.jsonPrimitive.content)
        }

    @Test
    fun disconnectedSocketFailsFast() =
        runTest {
            val socket = RecordingSocket(connected = false)
            val uploader = T3AttachmentUploader(socket, RecordingTransport(), readBytes = { png })
            try {
                uploader.upload(profile, listOf(image()))
                fail("expected failure")
            } catch (error: T3RpcException) {
                assertTrue(error is T3ConnectionLostException)
                assertEquals(T3ConnectionLostException.USER_MESSAGE, error.message)
                assertEquals("T3 websocket is not connected", error.detail)
            }
        }

    private class RecordingSocket(
        connected: Boolean = true,
    ) : T3Socket {
        override val frames: Flow<T3RpcFrame> = emptyFlow()
        private val _state = MutableStateFlow(if (connected) T3SocketState.Connected else T3SocketState.Disconnected)
        override val state: StateFlow<T3SocketState> = _state.asStateFlow()
        val requests = mutableListOf<Pair<String, JsonObject>>()
        private var minted = 0

        override fun connect(url: String) = Unit

        override suspend fun request(
            tag: String,
            payload: JsonObject,
        ): JsonElement {
            requests += tag to payload
            return when (tag) {
                T3Assets.CREATE_UPLOAD_URL -> {
                    minted += 1
                    buildJsonObject {
                        put("attachmentId", "pending-$minted")
                        put("relativeUrl", "/api/attachments/upload/sig-$minted")
                        put("expiresAt", 1L)
                    }
                }
                else -> buildJsonObject { }
            }
        }

        override fun sendSubscribe(
            tag: String,
            payload: JsonObject,
        ): String = "sub"

        override fun disconnect() = Unit
    }

    private class RecordingTransport(
        private val status: Int = 204,
    ) : T3AttachmentTransport {
        data class Upload(
            val url: String,
            val bytes: ByteArray,
            val mimeType: String,
            val headers: Map<String, String>,
        )

        val uploads = mutableListOf<Upload>()

        override suspend fun upload(
            url: String,
            bytes: ByteArray,
            mimeType: String,
            headers: Map<String, String>,
        ): Int {
            uploads += Upload(url, bytes, mimeType, headers)
            return status
        }
    }
}
