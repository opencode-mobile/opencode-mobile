package dev.opencode.mobile.data.remote

import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.network.OpencodeSseHttpClient
import dev.opencode.mobile.network.applyDirectory
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class EventsApi private constructor(
    private val fallbackClient: HttpClient? = null,
    private val sseClient: OpencodeSseHttpClient? = null,
    private val onEvict: () -> Unit = {},
    private val connectBudgetMs: Long = CONNECT_BUDGET_MS,
    private val newConnectRace: () -> SseConnectRace = { SseConnectRace() },
) {
    constructor(httpClient: OpencodeSseHttpClient) : this(sseClient = httpClient)

    internal constructor(
        httpClient: HttpClient,
        onEvict: () -> Unit = {},
        connectBudgetMs: Long = CONNECT_BUDGET_MS,
        newConnectRace: () -> SseConnectRace = { SseConnectRace() },
    ) : this(
        fallbackClient = httpClient,
        onEvict = onEvict,
        connectBudgetMs = connectBudgetMs,
        newConnectRace = newConnectRace,
    )

    fun retire(olderThan: Long) {
        sseClient?.retire(olderThan)
    }

    suspend fun streamGlobalEvents(
        protocol: ServerProtocol = ServerProtocol.V1,
        onHTTPResponse: suspend (Int) -> Unit = {},
        onActivity: suspend () -> Unit = {},
        onDataLine: suspend (String) -> Unit,
    ): SseAttemptOutcome =
        streamEvents(
            path = if (protocol == ServerProtocol.V2) "api/event" else "global/event",
            directory = null,
            onHTTPResponse = onHTTPResponse,
            onActivity = onActivity,
            onDataLine = onDataLine,
        )

    suspend fun streamEvents(
        path: String,
        directory: String? = null,
        generation: Long = 0L,
        onHTTPResponse: suspend (Int) -> Unit = {},
        onActivity: suspend () -> Unit = {},
        onDataLine: suspend (String) -> Unit,
    ): SseAttemptOutcome {
        val connectRace = newConnectRace()
        val client = sseClient?.openDial(generation) ?: requireNotNull(fallbackClient)
        return try {
            coroutineScope {
                val attemptJob = coroutineContext[Job]!!
                val budgetJob =
                    launch {
                        delay(connectBudgetMs)
                        if (connectRace.claimBudget()) {
                            DiagnosticLog.warning(
                                DiagnosticLogArea.Sse,
                                "SSE connect budget exceeded path=$path budgetMs=$connectBudgetMs",
                            )
                            onEvict()
                            sseClient?.closeDial(client)
                            attemptJob.cancel(CancellationException(CONNECT_BUDGET_EXCEEDED))
                        }
                    }
                try {
                    client
                        .prepareGet(path) {
                            accept(ContentType.Text.EventStream)
                            header(HttpHeaders.CacheControl, "no-cache")
                            header(HttpHeaders.AcceptEncoding, "identity")
                            applyDirectory(directory)
                        }.execute { response ->
                            if (!connectRace.markHeaders()) {
                                throw CancellationException(CONNECT_BUDGET_EXCEEDED)
                            }
                            budgetJob.cancel()
                            val statusCode = response.status.value
                            onHTTPResponse(statusCode)
                            if (statusCode !in 200..299) {
                                return@execute SseAttemptOutcome.Http(statusCode)
                            }
                            val channel = response.bodyAsChannel()
                            val parser = SseDataLineParser()
                            try {
                                while (!channel.isClosedForRead) {
                                    val line = channel.readUTF8Line() ?: break
                                    onActivity()
                                    for (payload in parser.ingest(line)) {
                                        onDataLine(payload)
                                    }
                                }
                                for (payload in parser.finish()) {
                                    onDataLine(payload)
                                }
                                SseAttemptOutcome.Ended
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                SseAttemptOutcome.Transport(error)
                            }
                        }
                } finally {
                    budgetJob.cancel()
                }
            }
        } catch (error: CancellationException) {
            if (connectRace.budgetClaimed() &&
                !connectRace.headersArrived() &&
                currentCoroutineContext().isActive
            ) {
                SseAttemptOutcome.Transport(error)
            } else {
                throw error
            }
        } catch (error: Exception) {
            SseAttemptOutcome.Transport(error)
        } finally {
            sseClient?.closeDial(client)
        }
    }

    internal companion object {
        const val CONNECT_BUDGET_MS = 5_000L
        const val CONNECT_BUDGET_EXCEEDED = "SSE connect budget exceeded"
    }
}

internal class SseConnectRace {
    private val state = AtomicInteger(PENDING)

    /** True when headers won. A budget claim that already won leaves this false. */
    fun markHeaders(): Boolean = state.compareAndSet(PENDING, HEADERS)

    /** True when the budget won. Headers that already arrived leave this false. */
    fun claimBudget(): Boolean = state.compareAndSet(PENDING, BUDGET)

    fun headersArrived(): Boolean = state.get() == HEADERS

    fun budgetClaimed(): Boolean = state.get() == BUDGET

    private companion object {
        const val PENDING = 0
        const val HEADERS = 1
        const val BUDGET = 2
    }
}
