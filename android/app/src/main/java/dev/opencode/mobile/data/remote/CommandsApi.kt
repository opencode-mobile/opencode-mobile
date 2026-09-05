package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.ApiListEnvelope
import dev.opencode.mobile.data.remote.dto.CommandDto
import dev.opencode.mobile.data.remote.dto.RunCommandRequestDto
import dev.opencode.mobile.data.remote.dto.V2CommandDto
import dev.opencode.mobile.data.remote.dto.V2RunCommandRequestDto
import dev.opencode.mobile.data.remote.dto.V2SkillDto
import dev.opencode.mobile.domain.message.PromptSendProgressReporter
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.applyDirectory
import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.network.PromptJsonBodyWriter
import dev.opencode.mobile.network.applyDurableSend
import dev.opencode.mobile.network.applyLocationDirectory
import dev.opencode.mobile.network.setJsonBody
import dev.opencode.mobile.network.setJsonFileBody
import java.io.File
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.Json

class CommandsApi private constructor(
    private val httpClient: ClientProvider,
    private val json: Json,
) {
    constructor(httpClient: OpencodeHttpClient, json: Json) : this(ClientProvider(httpClient::get), json)

    internal constructor(httpClient: HttpClient, json: Json) : this(ClientProvider { httpClient }, json)

    suspend fun listCommands(directory: String? = null): List<CommandDto> =
        httpClient
            .get()
            .get("command") {
                applyDirectory(directory)
            }.body()

    suspend fun listCommandsV2(directory: String? = null): List<V2CommandDto> =
        httpClient
            .get()
            .get("api/command") {
                applyLocationDirectory(directory)
            }.body<ApiListEnvelope<V2CommandDto>>()
            .data

    suspend fun listSkillsV2(directory: String? = null): List<V2SkillDto> =
        httpClient
            .get()
            .get("api/skill") {
                applyLocationDirectory(directory)
            }.body<ApiListEnvelope<V2SkillDto>>()
            .data

    suspend fun runCommand(
        sessionId: String,
        body: RunCommandRequestDto,
        directory: String? = null,
        attachments: List<Attachment> = emptyList(),
    ) {
        postCommand("session/$sessionId/command", body, directory, attachments)
    }

    suspend fun runCommandV2(
        sessionId: String,
        body: V2RunCommandRequestDto,
        attachments: List<Attachment> = emptyList(),
    ) {
        val progress = coroutineContext[PromptSendProgressReporter]
        val file = promptFile(attachments) { PromptJsonBodyWriter.writeV2Command(body, attachments, promptTempDir()) }
        val response =
            try {
                httpClient.get().post("api/session/$sessionId/command") {
                    applyDurableSend(progress)
                    if (file != null) setJsonFileBody(file) else setJsonBody(json, body)
                }
            } finally {
                file?.delete()
            }
        if (!response.status.isSuccess()) {
            throw ResponseException(response, "session.command failed with status ${response.status.value}")
        }
        response.bodyAsText()
    }

    private suspend fun postCommand(
        path: String,
        body: RunCommandRequestDto,
        directory: String?,
        attachments: List<Attachment>,
    ) {
        val progress = coroutineContext[PromptSendProgressReporter]
        val file = promptFile(attachments) { PromptJsonBodyWriter.writeV1Command(body, attachments, promptTempDir()) }
        val response =
            try {
                httpClient.get().post(path) {
                    applyDurableSend(progress)
                    applyDirectory(directory)
                    if (file != null) setJsonFileBody(file) else setJsonBody(json, body)
                }
            } finally {
                file?.delete()
            }
        if (!response.status.isSuccess()) {
            throw ResponseException(response, "session.command failed with status ${response.status.value}")
        }
        response.bodyAsText()
    }

    private fun promptFile(
        attachments: List<Attachment>,
        fileBody: () -> File,
    ): File? = if (attachments.any { it.usesFileBackedBody }) fileBody() else null

    private fun promptTempDir(): File = File(System.getProperty("java.io.tmpdir"), "opencode-prompt-send")

    private fun interface ClientProvider {
        fun get(): HttpClient
    }
}
