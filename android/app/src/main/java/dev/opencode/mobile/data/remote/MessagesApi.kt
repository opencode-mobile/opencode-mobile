package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.MessageDto
import dev.opencode.mobile.data.remote.dto.SendMessageRequestDto
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.applyDirectory
import dev.opencode.mobile.network.setJsonBody
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

class MessagesApi(
    private val httpClient: OpencodeHttpClient,
    private val json: Json,
) {
    suspend fun listMessages(
        sessionId: String,
        directory: String? = null,
    ): List<MessageDto> =
        httpClient
            .get()
            .get("session/$sessionId/message") {
                applyDirectory(directory)
            }.body()

    suspend fun getMessage(
        sessionId: String,
        messageId: String,
        directory: String? = null,
    ): MessageDto =
        httpClient
            .get()
            .get("session/$sessionId/message/$messageId") {
                applyDirectory(directory)
            }.body()

    suspend fun sendMessage(
        sessionId: String,
        body: SendMessageRequestDto,
        directory: String? = null,
    ): MessageDto =
        httpClient
            .get()
            .post("session/$sessionId/message") {
                applyDirectory(directory)
                setJsonBody(json, body)
            }.body()

    suspend fun sendPromptAsync(
        sessionId: String,
        body: SendMessageRequestDto,
        directory: String? = null,
    ) {
        val response =
            httpClient.get().post("session/$sessionId/prompt_async") {
                applyDirectory(directory)
                setJsonBody(json, body)
            }
        if (!response.status.isSuccess()) {
            throw ResponseException(response, "prompt_async failed with status ${response.status.value}")
        }
        // Release the 204 response body so OkHttp can reuse the connection.
        response.bodyAsText()
    }
}
