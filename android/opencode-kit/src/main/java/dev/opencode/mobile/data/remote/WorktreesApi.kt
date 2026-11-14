package dev.opencode.mobile.data.remote

import dev.opencode.mobile.domain.json.serverPayloadJson
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.applyDirectory
import dev.opencode.mobile.network.applyLocationDirectory
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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

/** A worktree route answered outside 2xx; [body] is the raw error body. */
class WorktreeHttpException(
    val statusCode: Int,
    val body: String,
) : Exception("worktree request failed with status $statusCode")

/** V1 `POST /experimental/worktree` answer; the server may have changed the name (slug, suffix). */
data class V1CreatedWorktree(
    val name: String,
    val branch: String?,
    val directory: String,
)

@Serializable
internal data class V1CreatedWorktreeDto(
    val name: String = "",
    val branch: String? = null,
    val directory: String,
)

interface WorktreeRemote {
    /** V2 `GET /api/worktree?projectID=`: worktrees with a strategy, never the project's own directory. */
    suspend fun listV2(projectId: String): List<String>

    suspend fun refreshV2(projectId: String)

    /** The sandboxes (extra git worktrees) of [directory]'s project; never the main checkout. */
    suspend fun listV1(directory: String): V1WorktreeHttp

    /**
     * V2 `POST /api/worktree` with `projectID`, `from`, and `name` and `branch` only when given; a blank
     * name lets the server pick one.
     * Returns the new checkout's directory. Throws [WorktreeHttpException] outside 2xx.
     */
    suspend fun createV2(
        projectId: String,
        from: String,
        name: String,
        branch: String?,
    ): String

    /**
     * V1 `POST /experimental/worktree?directory=` with `{"name"}`, or `{}` for a blank name so the
     * server picks one. Throws [WorktreeHttpException] outside 2xx.
     */
    suspend fun createV1(
        directory: String,
        name: String,
    ): V1CreatedWorktree

    /** V2 `GET /api/vcs/branch` at [directory]: local and remote branch names. */
    suspend fun branchesV2(directory: String): List<String>

    /** V2 `DELETE /api/worktree` with `{projectID, directory, force}`. Throws [WorktreeHttpException] outside 2xx. */
    suspend fun removeV2(
        projectId: String,
        directory: String,
        force: Boolean,
    )

    /**
     * V1 `DELETE /experimental/worktree?directory=` with `{"directory"}`; the server always forces.
     * Throws [WorktreeHttpException] outside 2xx.
     */
    suspend fun removeV1(
        projectDirectory: String,
        directory: String,
    )
}

class WorktreesApi private constructor(
    private val httpClient: ClientProvider,
) : WorktreeRemote {
    constructor(httpClient: OpencodeHttpClient) : this(ClientProvider(httpClient::get))

    constructor(httpClient: HttpClient) : this(ClientProvider { httpClient })

    override suspend fun listV2(projectId: String): List<String> =
        httpClient
            .get()
            .get("api/worktree") {
                parameter("projectID", projectId)
            }.body<List<WorktreeDirectoryDto>>()
            // The project's own directory comes without a strategy; the server cannot remove it,
            // and Home adds the main checkout itself.
            .filter { !it.strategy.isNullOrBlank() }
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

    override suspend fun createV2(
        projectId: String,
        from: String,
        name: String,
        branch: String?,
    ): String {
        val response =
            httpClient.get().post("api/worktree") {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("projectID", projectId)
                        put("from", from)
                        name.takeIf { it.isNotBlank() }?.let { put("name", it) }
                        branch?.let { put("branch", it) }
                    }.toString(),
                )
            }
        return lenientJson.decodeFromString<WorktreeDirectoryDto>(response.successText()).directory
    }

    override suspend fun createV1(
        directory: String,
        name: String,
    ): V1CreatedWorktree {
        val response =
            httpClient.get().post("experimental/worktree") {
                applyDirectory(directory)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { name.takeIf { it.isNotBlank() }?.let { put("name", it) } }.toString())
            }
        val dto = lenientJson.decodeFromString<V1CreatedWorktreeDto>(response.successText())
        return V1CreatedWorktree(name = dto.name, branch = dto.branch?.takeIf { it.isNotBlank() }, directory = dto.directory)
    }

    override suspend fun branchesV2(directory: String): List<String> {
        val response =
            httpClient.get().get("api/vcs/branch") {
                applyLocationDirectory(directory)
            }
        val root = lenientJson.parseToJsonElement(response.successText())
        // A location response (`{"location":…,"data":[…]}`), or a bare array.
        val list =
            when (root) {
                is JsonArray -> root
                is JsonObject -> root["data"] as? JsonArray
                else -> null
            } ?: error("unrecognized branch list")
        return list.mapNotNull(JsonElement::branchName).distinct()
    }

    override suspend fun removeV2(
        projectId: String,
        directory: String,
        force: Boolean,
    ) {
        httpClient
            .get()
            .delete("api/worktree") {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("projectID", projectId)
                        put("directory", directory)
                        put("force", force)
                    }.toString(),
                )
            }.successText()
    }

    override suspend fun removeV1(
        projectDirectory: String,
        directory: String,
    ) {
        httpClient
            .get()
            .delete("experimental/worktree") {
                applyDirectory(projectDirectory)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("directory", directory) }.toString())
            }.successText()
    }

    private suspend fun HttpResponse.successText(): String {
        val text = bodyAsText()
        if (!status.isSuccess()) throw WorktreeHttpException(status.value, text)
        return text
    }

    private fun interface ClientProvider {
        fun get(): HttpClient
    }
}

private val lenientJson = serverPayloadJson()

private fun JsonElement.branchName(): String? =
    (this as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
