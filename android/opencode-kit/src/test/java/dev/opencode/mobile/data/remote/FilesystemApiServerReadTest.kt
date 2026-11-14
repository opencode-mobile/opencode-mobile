package dev.opencode.mobile.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FilesystemApiServerReadTest {
    @Test
    fun readsARelativePathUnderTheGivenLocation() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        captured += request
                        respond(byteArrayOf(9, 8, 7), HttpStatusCode.OK)
                    },
                )

            val bytes = FilesystemApi(client).readServerFileV2("/srv/tmp", "uploads/u 1/a#b.png", 1024)

            assertNotNull(bytes)
            assertEquals(listOf<Byte>(9, 8, 7), bytes!!.toList())
            assertEquals("/api/fs/read/uploads/u%201/a%23b.png", captured.single().url.encodedPath)
            assertEquals("/srv/tmp", captured.single().url.parameters["location[directory]"])
        }

    @Test
    fun aFileLargerThanTheLimitIsNotReturned() =
        runTest {
            val client = HttpClient(MockEngine { respond(ByteArray(2048), HttpStatusCode.OK) })

            assertNull(FilesystemApi(client).readServerFileV2("/tmp", "big.png", 1024))
        }
}
