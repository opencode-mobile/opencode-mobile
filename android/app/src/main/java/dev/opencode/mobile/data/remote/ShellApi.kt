package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.ShellInfoDto
import dev.opencode.mobile.data.remote.dto.ShellOutputDto
import dev.opencode.mobile.data.remote.dto.decodeDataEnvelope
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.applyLocationDirectory
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * v2 agent-run shells (`/api/shell`). v1 has no shell routes: the app reads running shells
 * from `bash` tool parts there. Every route takes `location[directory]` and answers a
 * `{location, data}` envelope.
 */
class ShellApi private constructor(
    private val httpClient: ClientProvider,
    private val json: Json,
) {
    constructor(httpClient: OpencodeHttpClient, json: Json) : this(ClientProvider(httpClient::get), json)

    internal constructor(httpClient: HttpClient, json: Json) : this(ClientProvider { httpClient }, json)

    /** Running shells in [directory]; exited ones are not listed. */
    suspend fun listV2(directory: String): List<ShellInfoDto> {
        val element =
            httpClient
                .get()
                .get("api/shell") {
                    applyLocationDirectory(directory)
                }.jsonOnSuccess()
        return json.decodeDataEnvelope(element)
    }

    /** One output page from byte [cursor]; null once the shell is removed (404). */
    suspend fun outputV2(
        id: String,
        directory: String,
        cursor: Long,
    ): ShellOutputDto? {
        val response =
            httpClient
                .get()
                .get("api/shell/$id/output") {
                    applyLocationDirectory(directory)
                    parameter("cursor", cursor)
                }
        if (response.status == HttpStatusCode.NotFound) {
            response.bodyAsText()
            return null
        }
        return json.decodeDataEnvelope(response.jsonOnSuccess())
    }

    /** Kills and removes the shell; the server then emits `shell.exited` (killed) and `shell.deleted`. */
    suspend fun deleteV2(
        id: String,
        directory: String,
    ) {
        val response =
            httpClient
                .get()
                .delete("api/shell/$id") {
                    applyLocationDirectory(directory)
                }
        if (!response.status.isSuccess()) {
            throw ResponseException(response, "HTTP ${response.status.value}: ${response.status.description}")
        }
        response.bodyAsText()
    }

    private suspend fun HttpResponse.jsonOnSuccess(): JsonElement {
        if (!status.isSuccess()) {
            throw ResponseException(this, "HTTP ${status.value}: ${status.description}")
        }
        return body()
    }

    private fun interface ClientProvider {
        fun get(): HttpClient
    }
}
