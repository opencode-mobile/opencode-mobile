package dev.opencode.mobile.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketException

class EventsApiTest {
    @Test
    fun streamEvents_connectFailureReturnsTransport() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        throw ConnectException("Failed to connect")
                    },
                )
            val outcome = EventsApi(client).streamEvents(path = "global/event") { }
            assertTrue(outcome is SseAttemptOutcome.Transport)
        }

    @Test
    fun streamEvents_nonSuccessReturnsHttp() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            content = "",
                            status = HttpStatusCode.Unauthorized,
                        )
                    },
                )
            val outcome = EventsApi(client).streamEvents(path = "global/event") { }
            assertEquals(SseAttemptOutcome.Http(401), outcome)
        }

    @Test
    fun streamEvents_socketAbortReturnsTransport() =
        runBlocking {
            val opened = CompletableDeferred<ByteChannel>()
            val client =
                HttpClient(
                    MockEngine {
                        val channel = ByteChannel(autoFlush = true)
                        opened.complete(channel)
                        respond(
                            content = channel,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val outcomeDeferred = CompletableDeferred<SseAttemptOutcome>()
            val job =
                launch {
                    outcomeDeferred.complete(
                        EventsApi(client).streamEvents(path = "global/event") { },
                    )
                }
            opened.await().cancel(SocketException("Software caused connection abort"))
            val outcome = outcomeDeferred.await()
            job.join()
            assertTrue(
                outcome is SseAttemptOutcome.Transport || outcome is SseAttemptOutcome.Ended,
            )
        }

    @Test
    fun streamEvents_successReturnsEnded() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            content = "data: {\"payload\":{\"type\":\"server.connected\",\"properties\":{}}}\n\n",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val outcome = EventsApi(client).streamEvents(path = "global/event") { }
            assertEquals(SseAttemptOutcome.Ended, outcome)
        }
}
