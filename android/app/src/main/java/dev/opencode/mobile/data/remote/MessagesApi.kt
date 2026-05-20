package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.MessageDto
import dev.opencode.mobile.data.remote.dto.SendMessageRequestDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess

class MessagesApi(
  private val httpClient: HttpClient,
) {
  suspend fun listMessages(sessionId: String): List<MessageDto> =
    httpClient.get("session/$sessionId/message").body()

  suspend fun getMessage(sessionId: String, messageId: String): MessageDto =
    httpClient.get("session/$sessionId/message/$messageId").body()

  suspend fun sendMessage(sessionId: String, body: SendMessageRequestDto): MessageDto =
    httpClient.post("session/$sessionId/message") { setBody(body) }.body()

  suspend fun sendPromptAsync(sessionId: String, body: SendMessageRequestDto) {
    val response: HttpResponse = httpClient.post("session/$sessionId/prompt_async") { setBody(body) }
    if (!response.status.isSuccess()) {
      error("prompt_async failed with status ${response.status.value}")
    }
  }
}
