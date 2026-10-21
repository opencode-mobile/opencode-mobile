package dev.opencode.mobile.data.remote

import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.applyDirectory
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
internal data class WorktreeDirectoryDto(
    val directory: String,
    val strategy: String? = null,
)

/** V1 `GET /experimental/worktree` outcome. Servers without the route answer 404. */
sealed class V1WorktreeHttp {
    data class Ok(
        val body: String,
    ) : V1WorktreeHttp()

    data object NotFound : V1WorktreeHttp()

    data class Failed(
        val statusCode: Int,
    ) : V1WorktreeHttp()
}

interface WorktreeRemote {
    suspend fun listV2(projectId: String): List<String>

    suspend fun refreshV2(projectId: String)

    /** The sandboxes (extra git worktrees) of [directory]'s project; never the main checkout. */
    suspend fun listV1(directory: String): V1WorktreeHttp
}

class WorktreesApi private constructor(
    private val httpClient: ClientProvider,
) : WorktreeRemote {
    constructor(httpClient: OpencodeHttpClient) : this(ClientProvider(httpClient::get))

    internal constructor(httpClient: HttpClient) : this(ClientProvider { httpClient })

    override suspend fun listV2(projectId: String): List<String> =
        httpClient
            .get()
            .get("api/worktree") {
                parameter("projectID", projectId)
            }.body<List<WorktreeDirectoryDto>>()
            .map { it.directory }

    override suspend fun refreshV2(projectId: String) {
        val response =
            httpClient.get().post("api/worktree/refresh") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("projectID", projectId) }.toString())
            }
        if (!response.status.isSuccess()) {
            error("worktree refresh failed with status ${response.status.value}")
        }
    }

    override suspend fun listV1(directory: String): V1WorktreeHttp {
        val response =
            httpClient.get().get("experimental/worktree") {
                applyDirectory(directory)
            }
        return when (val status = response.status.value) {
            404 -> V1WorktreeHttp.NotFound
            in 200..299 -> V1WorktreeHttp.Ok(response.bodyAsText())
            else -> V1WorktreeHttp.Failed(status)
        }
    }

    private fun interface ClientProvider {
        fun get(): HttpClient
    }
}
