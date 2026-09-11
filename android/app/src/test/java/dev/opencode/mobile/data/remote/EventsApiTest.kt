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
import org.junit.Test
import java.net.ConnectException
import java.net.SocketException

class EventsApiTest {
    @Test
    fun streamEvents_connectFailureEndsWithoutThrowing() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        throw ConnectException("Failed to connect")
                    },
                )
            EventsApi(client).streamEvents(path = "global/event") { }
        }

    @Test
    fun streamEvents_socketAbortEndsWithoutThrowing() =
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
            val job =
                launch {
                    EventsApi(client).streamEvents(path = "global/event") { }
                }
            opened.await().cancel(SocketException("Software caused connection abort"))
            job.join()
        }
}
