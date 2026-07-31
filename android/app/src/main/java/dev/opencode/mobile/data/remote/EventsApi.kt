package dev.opencode.mobile.data.remote

import dev.opencode.mobile.network.OpencodeSseHttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException

class EventsApi(
    private val httpClient: OpencodeSseHttpClient,
) {
    suspend fun streamGlobalEvents(
        onHTTPResponse: suspend (Int) -> Unit = {},
        onDataLine: suspend (String) -> Unit,
    ) {
        httpClient
            .get()
            .prepareGet("global/event") {
                accept(ContentType.Text.EventStream)
                header(HttpHeaders.CacheControl, "no-cache")
                // Match iOS: keep SSE uncompressed so framing stays line-oriented under burst.
                header(HttpHeaders.AcceptEncoding, "identity")
            }.execute { response ->
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
