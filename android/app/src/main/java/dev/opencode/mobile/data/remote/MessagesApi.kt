package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.ApiListEnvelope
import dev.opencode.mobile.data.remote.dto.MessageDto
import dev.opencode.mobile.data.remote.dto.SendMessageRequestDto
import dev.opencode.mobile.data.remote.dto.V2PromptRequestDto
import dev.opencode.mobile.data.remote.dto.V2SessionMessageDto
import dev.opencode.mobile.data.remote.dto.V2SwitchAgentRequestDto
import dev.opencode.mobile.data.remote.dto.V2SwitchModelRequestDto
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.applyDirectory
import dev.opencode.mobile.network.setJsonBody
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

class MessagesApi private constructor(
    private val httpClient: ClientProvider,
    private val json: Json,
) {
    constructor(httpClient: OpencodeHttpClient, json: Json) : this(ClientProvider(httpClient::get), json)

    internal constructor(httpClient: HttpClient, json: Json) : this(ClientProvider { httpClient }, json)

    suspend fun listMessages(
        sessionId: String,
        directory: String? = null,
    ): List<MessageDto> =
        httpClient
            .get()
            .get("session/$sessionId/message") {
                applyDirectory(directory)
            }.body()

    suspend fun listMessagesV2(
        sessionId: String,
        directory: String? = null,
    ): List<V2SessionMessageDto> {
        val messages = mutableListOf<V2SessionMessageDto>()
        var cursor: String? = null
        var pages = 0
        do {
            val envelope =
                httpClient
                    .get()
                    .get("api/session/$sessionId/message") {
                        // Cursor tokens already encode order. Sending `order` with
                        // `cursor` returns InvalidCursorError 400 on live v2.
                        if (cursor == null) {
                            parameter("order", "asc")
                        } else {
                            parameter("cursor", cursor)
                        }
                    }.body<ApiListEnvelope<V2SessionMessageDto>>()
            messages += envelope.data
            val next = envelope.cursor?.next?.takeIf { it.isNotBlank() }
            if (next == cursor) break
            cursor = next
            pages++
        } while (cursor != null && pages < MAX_V2_MESSAGE_LIST_PAGES)
        return messages
    }

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

    suspend fun sendPromptV2(
        sessionId: String,
        body: V2PromptRequestDto,
        directory: String? = null,
    ) {
        postSuccess("api/session/$sessionId/prompt", body, directory)
    }

    suspend fun switchSessionAgent(
        sessionId: String,
        agent: String,
        directory: String? = null,
    ) {
        postSuccess("api/session/$sessionId/agent", V2SwitchAgentRequestDto(agent), directory)
    }

    suspend fun switchSessionModel(
        sessionId: String,
        body: V2SwitchModelRequestDto,
        directory: String? = null,
    ) {
        postSuccess("api/session/$sessionId/model", body, directory)
    }

    private suspend inline fun <reified T> postSuccess(
        path: String,
        body: T,
        directory: String?,
    ) {
        val response =
            httpClient.get().post(path) {
                setJsonBody(json, body)
            }
        if (!response.status.isSuccess()) {
            throw ResponseException(response, "$path failed with status ${response.status.value}")
        }
        response.bodyAsText()
    }

    private fun interface ClientProvider {
        fun get(): HttpClient
    }

    private companion object {
        const val MAX_V2_MESSAGE_LIST_PAGES = 500
    }
}
