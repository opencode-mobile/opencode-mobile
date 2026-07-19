package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.CommandDto
import dev.opencode.mobile.data.remote.dto.RunCommandRequestDto
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

class CommandsApi(
    private val httpClient: OpencodeHttpClient,
    private val json: Json,
) {
    suspend fun listCommands(directory: String? = null): List<CommandDto> =
        httpClient
            .get()
            .get("command") {
                applyDirectory(directory)
            }.body()

    suspend fun runCommand(
        sessionId: String,
        body: RunCommandRequestDto,
        directory: String? = null,
    ) {
        val response =
            httpClient.get().post("session/$sessionId/command") {
                applyDirectory(directory)
                setJsonBody(json, body)
            }
        if (!response.status.isSuccess()) {
            throw ResponseException(response, "session.command failed with status ${response.status.value}")
        }
        response.bodyAsText()
    }
}
