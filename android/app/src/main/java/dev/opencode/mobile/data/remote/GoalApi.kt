package dev.opencode.mobile.data.remote

import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.applyLocationDirectory
import dev.opencode.mobile.network.setJsonBody
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The v2 goal plugin: its RPC (`POST /api/rpc/opencode.goal/get`) and the `goal` session command. */
class GoalApi private constructor(
    private val httpClient: ClientProvider,
    private val json: Json,
) {
    constructor(httpClient: OpencodeHttpClient, json: Json) : this(ClientProvider(httpClient::get), json)

    internal constructor(httpClient: HttpClient, json: Json) : this(ClientProvider { httpClient }, json)

    /**
     * `{"input":{"sessionID":…}}` at the session's location → `{"output": Goal.State | null}`.
     * A server without the goal plugin answers with an error.
     */
    suspend fun getGoalV2(
        sessionId: String,
        directory: String?,
    ): JsonElement? {
        val response =
            httpClient.get().post("api/rpc/$RPC_ID/get") {
                applyLocationDirectory(directory)
                setJsonBody(
                    json,
                    buildJsonObject {
                        putJsonObject("input") { put("sessionID", sessionId) }
                    },
                )
            }
        val text = response.successText()
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        return root?.get("output")
    }

    /** `POST /api/session/{id}/command` `{"name":"goal","text":…}`; 4xx carries the command's error. */
    suspend fun runGoalCommandV2(
        sessionId: String,
        text: String,
    ) {
        httpClient
            .get()
            .post("api/session/$sessionId/command") {
                setJsonBody(
                    json,
                    buildJsonObject {
                        put("name", COMMAND)
                        put("text", text)
                    },
                )
            }.successText()
    }

    private suspend fun HttpResponse.successText(): String {
        val text = runCatching { bodyAsText() }.getOrDefault("")
        if (!status.isSuccess()) {
            throw HttpBodyResponseException(this, text.ifBlank { "HTTP ${status.value}: ${status.description}" })
        }
        return text
    }

    private fun interface ClientProvider {
        fun get(): HttpClient
    }

    private companion object {
        const val RPC_ID = "opencode.goal"
        const val COMMAND = "goal"
    }
}
