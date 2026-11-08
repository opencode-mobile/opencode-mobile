package dev.opencode.mobile.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AttachmentUploadApiTest {
    private val staged = File(System.getProperty("java.io.tmpdir"), "upload-api-test.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Test
    fun writePostsRawBytesWithPathAndLocationQuery() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                uploadApi { request ->
                    captured += request
                    respond(
                        """{"location":{"directory":"/repo"},"data":{"path":"/srv/tmp/uploads/u1/a bin"}}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val response = api.write("/srv/tmp/uploads/u1/a bin", staged, "/repo") { _, _ -> }

            val request = captured.single()
            assertEquals("POST", request.method.value)
            assertTrue(request.url.encodedPath.endsWith("/api/experimental/fs/write"))
            assertEquals("/srv/tmp/uploads/u1/a bin", request.url.parameters["path"])
            assertEquals("/repo", request.url.parameters["location[directory]"])
            assertEquals("application/octet-stream", request.body.contentType?.toString())
            assertEquals(3L, (request.body as OutgoingContent).contentLength)
            assertTrue(response.isSuccess)
            assertEquals("/srv/tmp/uploads/u1/a bin", response.path)
        }

    @Test
    fun okBodyWithoutDataPathHasNoPath() =
        runTest {
            val api =
                uploadApi {
                    respond("""{"data":{}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }

            val response = api.write("/tmp/x", staged, "/repo") { _, _ -> }

            assertTrue(response.isSuccess)
            assertNull(response.path)
        }

    @Test
    fun errorStatusIsReportedForClassification() =
        runTest {
            val api = uploadApi { respond("nope", HttpStatusCode.UnsupportedMediaType) }

            val response = api.write("/tmp/x", staged, "/repo") { _, _ -> }

            assertEquals(415, response.status)
            assertNull(response.path)
        }

    private fun uploadApi(handler: MockRequestHandler): AttachmentUploadApi =
        AttachmentUploadApi(
            HttpClient(MockEngine(handler)) {
                install(HttpTimeout)
            },
        )
}
