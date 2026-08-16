package dev.opencode.mobile.data.remote

import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.OpencodeSseHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException

class EventsApi private constructor(
    private val httpClient: ClientProvider,
) {
    constructor(httpClient: OpencodeSseHttpClient) : this(ClientProvider(httpClient::get))

    internal constructor(httpClient: HttpClient) : this(ClientProvider { httpClient })

    private fun interface ClientProvider {
        fun get(): HttpClient
    }

    suspend fun streamGlobalEvents(
        protocol: ServerProtocol = ServerProtocol.V1,
        onHTTPResponse: suspend (Int) -> Unit = {},
        onDataLine: suspend (String) -> Unit,
    ) {
        val path = if (protocol == ServerProtocol.V2) "api/event" else "global/event"
        httpClient
            .get()
            .prepareGet(path) {
                accept(ContentType.Text.EventStream)
                header(HttpHeaders.CacheControl, "no-cache")
                // Match iOS: keep SSE uncompressed so framing stays line-oriented under burst.
                header(HttpHeaders.AcceptEncoding, "identity")
            }.execute { response ->
                if (response.status.value !in 200..299) {
                    throw IllegalStateException("SSE HTTP ${response.status.value}")
                }
                onHTTPResponse(response.status.value)
                val channel = response.bodyAsChannel()
                val parser = SseDataLineParser()
                try {
                    while (!channel.isClosedForRead) {
                        val line = channel.readUTF8Line() ?: break
                        for (payload in parser.ingest(line)) {
                            onDataLine(payload)
                        }
                    }
                    for (payload in parser.finish()) {
                        onDataLine(payload)
                    }
                } catch (error: CancellationException) {
                    throw error
                }
            }
    }
}
