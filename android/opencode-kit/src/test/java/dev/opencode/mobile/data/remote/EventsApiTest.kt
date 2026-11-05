package dev.opencode.mobile.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger

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

    @Test
    fun sessionEventsApi_budgetExceeded_invokesRebuildRestOnce() =
        runBlocking {
            val rebuilds = AtomicInteger(0)
            val client =
                HttpClient(
                    MockEngine {
                        awaitCancellation()
                    },
                )
            try {
                val outcome =
                    sessionEventsApi(
                        sse = client,
                        rebuildRest = { rebuilds.incrementAndGet() },
                        connectBudgetMs = 80L,
                    ).streamEvents(path = "global/event") { }
                assertTrue(outcome is SseAttemptOutcome.Transport)
                assertEquals(1, rebuilds.get())
            } finally {
                client.close()
            }
        }

    @Test
    fun streamEvents_headerBudgetExceeded_returnsTransportAndEvicts() =
        runBlocking {
            val evicted = AtomicInteger(0)
            val client =
                HttpClient(
                    MockEngine {
                        awaitCancellation()
                    },
                )
            try {
                val startedAt = System.currentTimeMillis()
                val outcome =
                    EventsApi(
                        httpClient = client,
                        onEvict = { evicted.incrementAndGet() },
                        connectBudgetMs = 80L,
                    ).streamEvents(path = "global/event") { }
                val elapsedMs = System.currentTimeMillis() - startedAt
                assertTrue(outcome is SseAttemptOutcome.Transport)
                assertEquals(1, evicted.get())
                assertTrue(elapsedMs < 1_500)
            } finally {
                client.close()
            }
        }

    @Test
    fun streamEvents_outerCancellationIsNotConnectBudget() =
        runBlocking {
            val evicted = AtomicInteger(0)
            val client =
                HttpClient(
                    MockEngine {
                        awaitCancellation()
                    },
                )
            try {
                var outcome: SseAttemptOutcome? = null
                val job =
                    launch {
                        outcome =
                            EventsApi(
                                httpClient = client,
                                onEvict = { evicted.incrementAndGet() },
                                connectBudgetMs = 2_000L,
                            ).streamEvents(path = "global/event") { }
                    }
                delay(50)
                job.cancel()
                job.join()
                assertTrue(job.isCancelled)
                assertNull(outcome)
                assertEquals(0, evicted.get())
            } finally {
                client.close()
            }
        }

    @Test
    fun markHeadersAndClaimBudgetAreExclusive() {
        val headersFirst = SseConnectRace()
        assertTrue(headersFirst.markHeaders())
        assertFalse(headersFirst.claimBudget())
        assertTrue(headersFirst.headersArrived())
        assertFalse(headersFirst.budgetClaimed())

        val budgetFirst = SseConnectRace()
        assertTrue(budgetFirst.claimBudget())
        assertFalse(budgetFirst.markHeaders())
        assertTrue(budgetFirst.budgetClaimed())
        assertFalse(budgetFirst.headersArrived())
    }

    @Test
    fun streamEvents_headersWin_budgetDoesNotEvictOrCancel() =
        runBlocking {
            val evicted = AtomicInteger(0)
            val race = SseConnectRace()
            assertTrue(race.markHeaders())
            val client =
                HttpClient(
                    MockEngine {
                        awaitCancellation()
                    },
                )
            try {
                val job =
                    launch {
                        EventsApi(
                            httpClient = client,
                            onEvict = { evicted.incrementAndGet() },
                            connectBudgetMs = 50L,
                            newConnectRace = { race },
                        ).streamEvents(path = "global/event") { }
                    }
                delay(200)
                assertTrue(job.isActive)
                assertEquals(0, evicted.get())
                job.cancel()
                job.join()
            } finally {
                client.close()
            }
        }
}
