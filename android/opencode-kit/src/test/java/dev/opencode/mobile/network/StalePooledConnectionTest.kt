package dev.opencode.mobile.network

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

class StalePooledConnectionTest {
    @Test
    fun matchesZeroByteStatusLineEofFromTheSendLog() {
        val failure =
            IOException(
                "unexpected end of stream on https://tunnel.example/session/prompt",
                EOFException("\\n not found: limit=0 content="),
            )

        assertTrue(StalePooledConnection.isRetryable(failure))
    }

    @Test
    fun ignoresPartialStatusLineAndOtherTransportFailures() {
        assertFalse(
            StalePooledConnection.isRetryable(
                IOException(
                    "unexpected end of stream on https://tunnel.example/",
                    EOFException("\\n not found: limit=12 content=HTTP/1.1 500"),
                ),
            ),
        )
        assertFalse(StalePooledConnection.isRetryable(ConnectException("Failed to connect")))
        assertFalse(
            StalePooledConnection.isRetryable(
                CancellationException(
                    "cancelled",
                    EOFException("\\n not found: limit=0 content="),
                ),
            ),
        )
    }

    @Test(timeout = 15_000)
    fun postRetriesZeroByteEofThenSucceeds() {
        DroppingServer(dropsBeforeResponse = 2).use { server ->
            val client = restClient(server.port)
            try {
                val response =
                    runBlocking {
                        client.post("session/prompt") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"text":"hi"}""")
                        }
                    }
                assertEquals(204, response.status.value)
                assertEquals(3, server.attempts.get())
                assertEquals("""{"text":"hi"}""", server.bodies.last())
            } finally {
                client.close()
            }
        }
    }

    @Test(timeout = 15_000)
    fun filePostReplaysBodyAfterZeroByteEof() {
        val file = File.createTempFile("prompt", ".json")
        file.writeText("""{"text":"file"}""")
        try {
            DroppingServer(dropsBeforeResponse = 1).use { server ->
                val client = restClient(server.port)
                try {
                    val response =
                        runBlocking {
                            client.post("session/prompt") {
                                setJsonFileBody(file)
                            }
                        }
                    assertEquals(204, response.status.value)
                    assertEquals(listOf("""{"text":"file"}""", """{"text":"file"}"""), server.bodies)
                } finally {
                    client.close()
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test(timeout = 15_000)
    fun stopsAfterTheIdlePoolIsDrained() {
        DroppingServer(dropsBeforeResponse = Int.MAX_VALUE).use { server ->
            val client = restClient(server.port)
            try {
                val error =
                    runBlocking {
                        runCatching {
                            client.post("session/prompt") {
                                setBody("x")
                            }
                        }.exceptionOrNull()
                    }
                assertTrue(error != null && StalePooledConnection.isRetryable(error))
                assertEquals(StalePooledConnection.MAX_RETRIES + 1, server.attempts.get())
            } finally {
                client.close()
            }
        }
    }

    @Test(timeout = 15_000)
    fun httpErrorIsNotReplayed() {
        DroppingServer(dropsBeforeResponse = 0, statusCode = 500).use { server ->
            val client = restClient(server.port)
            try {
                val response: HttpResponse =
                    runBlocking {
                        client.post("session/prompt") { setBody("x") }
                    }
                assertEquals(500, response.status.value)
                assertEquals(1, server.attempts.get())
            } finally {
                client.close()
            }
        }
    }

    @Test(timeout = 15_000)
    fun sseClientDoesNotReplayADroppedSocket() {
        DroppingServer(dropsBeforeResponse = Int.MAX_VALUE).use { server ->
            val client =
                KtorClientFactory.create(
                    connection = ServerConnection(baseUrl = "http://127.0.0.1:${server.port}"),
                    json = Json,
                    contentNegotiation = false,
                    sse = true,
                )
            try {
                val error =
                    runBlocking {
                        runCatching { client.post("event") { setBody("x") } }.exceptionOrNull()
                    }
                assertTrue(error != null)
                assertEquals(1, server.attempts.get())
            } finally {
                client.close()
            }
        }
    }

    private fun restClient(port: Int) =
        KtorClientFactory.create(
            connection = ServerConnection(baseUrl = "http://127.0.0.1:$port"),
            json = Json,
            contentNegotiation = false,
        )

    private class DroppingServer(
        private val dropsBeforeResponse: Int,
        private val statusCode: Int = 204,
    ) : AutoCloseable {
        private val socket = ServerSocket(0)
        val port: Int = socket.localPort
        val attempts = AtomicInteger(0)
        val bodies = mutableListOf<String>()
        private val thread =
            Thread {
                while (!socket.isClosed) {
                    val connection =
                        try {
                            socket.accept()
                        } catch (_: SocketException) {
                            break
                        }
                    connection.soTimeout = 5_000
                    try {
                        val body = readRequest(connection)
                        val attempt = attempts.incrementAndGet()
                        synchronized(bodies) { bodies += body }
                        if (attempt <= dropsBeforeResponse) {
                            connection.close()
                        } else {
                            val reason = if (statusCode == 204) "No Content" else "Error"
                            val response =
                                "HTTP/1.1 $statusCode $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            connection.getOutputStream().write(response.toByteArray(Charsets.ISO_8859_1))
                            connection.getOutputStream().flush()
                            connection.close()
                        }
                    } catch (_: IOException) {
                        connection.close()
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

        override fun close() {
            socket.close()
            thread.join(2_000)
        }

        private fun readRequest(connection: Socket): String {
            val input = connection.getInputStream()
            val headerBytes = ByteArrayOutputStream()
            val window = ArrayDeque<Byte>()
            while (true) {
                val next = input.read()
                if (next < 0) break
                headerBytes.write(next)
                window.addLast(next.toByte())
                if (window.size > 4) window.removeFirst()
                if (window.size == 4 && window.joinToString("") { it.toInt().toChar().toString() } == "\r\n\r\n") {
                    break
                }
            }
            val headers = headerBytes.toString(Charsets.ISO_8859_1.name())
            if (headers.contains("Expect: 100-continue", ignoreCase = true)) {
                connection.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                connection.getOutputStream().flush()
            }
            val match = CONTENT_LENGTH.find(headers) ?: error("missing Content-Length in $headers")
            val length = match.groupValues[1].toInt()
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = input.read(body, read, length - read)
                if (count < 0) break
                read += count
            }
            return body.toString(Charsets.UTF_8)
        }
    }

    private companion object {
        val CONTENT_LENGTH = Regex("""(?i)Content-Length:\s*(\d+)""")
    }
}
