package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.ApiListEnvelope
import dev.opencode.mobile.data.remote.dto.MessageDto
import dev.opencode.mobile.data.remote.dto.SendMessageRequestDto
import dev.opencode.mobile.data.remote.dto.V2InboxItemDto
import dev.opencode.mobile.data.remote.dto.V2PromptRequestDto
import dev.opencode.mobile.data.remote.dto.V2SessionMessageDto
import dev.opencode.mobile.data.remote.dto.V2SwitchAgentRequestDto
import dev.opencode.mobile.data.remote.dto.V2SwitchModelRequestDto
import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.PromptSendProgressReporter
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.PromptJsonBodyWriter
import dev.opencode.mobile.network.applyDirectory
import dev.opencode.mobile.network.applyDurableSend
import dev.opencode.mobile.network.setJsonBody
import dev.opencode.mobile.network.setJsonFileBody
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.coroutines.coroutineContext

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

    suspend fun listMessagesPage(
        sessionId: String,
        directory: String? = null,
        limit: Int,
        before: String? = null,
    ): V1MessageListHttpPage {
        val response =
            httpClient
                .get()
                .get("session/$sessionId/message") {
                    applyDirectory(directory)
                    parameter("limit", limit)
                    if (!before.isNullOrBlank()) {
                        parameter("before", before)
                    }
                }
        val messages = response.body<List<MessageDto>>()
        val olderCursor =
            MessageListLinkCursor.fromHeaders(
                nextCursor = response.headers["X-Next-Cursor"] ?: response.headers["x-next-cursor"],
                link = response.headers["Link"] ?: response.headers["link"],
            )
        return V1MessageListHttpPage(messages = messages, olderCursor = olderCursor)
    }

    suspend fun listMessagesV2(
        sessionId: String,
        directory: String? = null,
    ): List<V2SessionMessageDto> {
        val messages = mutableListOf<V2SessionMessageDto>()
        var cursor: String? = null
        var pages = 0
        do {
            val envelope = listMessagesV2Page(sessionId, directory, limit = null, newestFirst = false, cursor = cursor)
            messages += envelope.data
            val next = envelope.cursor?.next?.takeIf { it.isNotBlank() }
            if (next == cursor) break
            cursor = next
            pages++
        } while (cursor != null && pages < MAX_V2_MESSAGE_LIST_PAGES)
        return messages
    }

    suspend fun listMessagesV2Page(
        sessionId: String,
        directory: String? = null,
        limit: Int? = null,
        newestFirst: Boolean = false,
        cursor: String? = null,
    ): ApiListEnvelope<V2SessionMessageDto> =
        httpClient
            .get()
            .get("api/session/$sessionId/message") {
                if (limit != null) {
                    parameter("limit", limit)
                }
                // Cursor tokens already encode order. Sending `order` with
                // `cursor` returns InvalidCursorError 400 on live v2.
                if (cursor == null) {
                    parameter("order", if (newestFirst) "desc" else "asc")
                } else {
                    parameter("cursor", cursor)
                }
            }.body()

    suspend fun listInboxV2(sessionId: String): List<V2InboxItemDto> =
        httpClient
            .get()
            .get("api/session/$sessionId/inbox")
            .body<ApiListEnvelope<V2InboxItemDto>>()
            .data

    suspend fun sendPromptAsync(
        sessionId: String,
        body: SendMessageRequestDto,
        directory: String? = null,
        attachments: List<Attachment> = emptyList(),
    ) {
        val progress = coroutineContext[PromptSendProgressReporter]
        val file = promptFile(attachments) { PromptJsonBodyWriter.writeV1Prompt(body, attachments, promptTempDir()) }
        val response =
            try {
                httpClient.get().post("session/$sessionId/prompt_async") {
                    applyDurableSend(progress)
                    applyDirectory(directory)
                    if (file != null) setJsonFileBody(file) else setJsonBody(json, body)
                }
            } finally {
                file?.delete()
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
        attachments: List<Attachment> = emptyList(),
    ) {
        postSuccess(path = "api/session/$sessionId/prompt", body = body, attachments = attachments) {
            PromptJsonBodyWriter.writeV2Prompt(body, attachments, promptTempDir())
        }
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
        directory: String? = null,
        attachments: List<Attachment> = emptyList(),
        noinline fileBody: () -> File? = { null },
    ) {
        val progress = coroutineContext[PromptSendProgressReporter]
        val file = promptFile(attachments, fileBody)
        val response =
            try {
                httpClient.get().post(path) {
                    applyDurableSend(progress)
                    if (file != null) setJsonFileBody(file) else setJsonBody(json, body)
                }
            } finally {
                file?.delete()
            }
        if (!response.status.isSuccess()) {
            throw ResponseException(response, "$path failed with status ${response.status.value}")
        }
        response.bodyAsText()
    }

    private fun promptFile(
        attachments: List<Attachment>,
        fileBody: () -> File?,
    ): File? = if (attachments.any { it.usesFileBackedBody }) fileBody() else null

    private fun promptTempDir(): File = File(System.getProperty("java.io.tmpdir"), "opencode-prompt-send")

    private fun interface ClientProvider {
        fun get(): HttpClient
    }

    private companion object {
        const val MAX_V2_MESSAGE_LIST_PAGES = 500
    }
}

data class V1MessageListHttpPage(
    val messages: List<MessageDto>,
    val olderCursor: String?,
)
