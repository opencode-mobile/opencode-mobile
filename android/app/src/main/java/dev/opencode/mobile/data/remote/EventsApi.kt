package dev.opencode.mobile.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException

class EventsApi(
  private val httpClient: HttpClient,
) {
  suspend fun streamGlobalEvents(onDataLine: suspend (String) -> Unit) {
    httpClient.prepareGet("global/event") {
      accept(ContentType.Text.EventStream)
      header(HttpHeaders.CacheControl, "no-cache")
    }.execute { response ->
      val channel = response.bodyAsChannel()
      val dataBuffer = StringBuilder()
      try {
        while (!channel.isClosedForRead) {
          val line = channel.readUTF8Line() ?: break
          when {
            line.isEmpty() -> {
              if (dataBuffer.isNotEmpty()) {
                onDataLine(dataBuffer.toString())
                dataBuffer.clear()
              }
            }
            line.startsWith("data:") -> {
              val payload = line.removePrefix("data:").trimStart()
              if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
              dataBuffer.append(payload)
            }
          }
        }
      } catch (error: CancellationException) {
        throw error
      }
    }
  }
}
