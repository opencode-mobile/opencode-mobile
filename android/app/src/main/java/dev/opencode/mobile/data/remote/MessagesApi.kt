package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.MessageDto
import dev.opencode.mobile.data.remote.dto.SendMessageRequestDto
import dev.opencode.mobile.network.applyDirectory
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
  suspend fun listMessages(sessionId: String, directory: String? = null): List<MessageDto> =
    httpClient.get("session/$sessionId/message") {
      applyDirectory(directory)
    }.body()

  suspend fun getMessage(sessionId: String, messageId: String, directory: String? = null): MessageDto =
    httpClient.get("session/$sessionId/message/$messageId") {
      applyDirectory(directory)
    }.body()

  suspend fun sendMessage(
    sessionId: String,
    body: SendMessageRequestDto,
    directory: String? = null,
  ): MessageDto =
    httpClient.post("session/$sessionId/message") {
      applyDirectory(directory)
      setBody(body)
    }.body()

  suspend fun sendPromptAsync(sessionId: String, body: SendMessageRequestDto, directory: String? = null) {
    val response: HttpResponse =
      httpClient.post("session/$sessionId/prompt_async") {
        applyDirectory(directory)
        setBody(body)
      }
    if (!response.status.isSuccess()) {
      error("prompt_async failed with status ${response.status.value}")
    }
  }
}
