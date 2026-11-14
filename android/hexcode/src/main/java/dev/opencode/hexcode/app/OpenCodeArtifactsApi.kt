package dev.opencode.hexcode.app

import dev.opencode.hexcode.firstrun.ModelChoice
import dev.opencode.hexcode.home.ArtifactFetcher
import dev.opencode.hexcode.home.ArtifactFile
import dev.opencode.hexcode.session.ApprovalFile
import dev.opencode.hexcode.session.ApprovalRequest
import dev.opencode.hexcode.session.ArtifactListAnswer
import dev.opencode.hexcode.session.ArtifactPrompts
import dev.opencode.hexcode.session.ArtifactsApi
import dev.opencode.hexcode.session.ArtifactsDeclared
import dev.opencode.hexcode.session.ConfigDocuments
import dev.opencode.hexcode.session.FileRead
import dev.opencode.hexcode.session.PendingItem
import dev.opencode.mobile.data.remote.AttachmentUploadApi
import dev.opencode.mobile.data.remote.SessionsApi
import dev.opencode.mobile.data.remote.dto.V2PermissionReplyRequestDto
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.applyLocationDirectory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * The artifact feature's calls over OpenCode v2.2.5 (app-plan.md, Artifacts (M8)): the list, config, `fs/read` and
 * view routes with the kit's signed-in client; Restore's write with the kit's upload client ([AttachmentUploadApi]);
 * the setup session, its prompt and approvals with [SessionsApi]. The config's documents can hold API keys: nothing
 * here logs a body.
 */
class OpenCodeArtifactsApi(
    private val http: OpencodeHttpClient,
    private val sessions: SessionsApi,
    private val uploads: AttachmentUploadApi,
    private val json: Json,
    /** Where Restore's bytes wait while they upload. */
    private val cache: File,
    private val ready: suspend () -> Unit = {},
) : ArtifactsApi {
    private suspend fun client(): HttpClient {
        ready()
        return http.get()
    }

    override suspend fun declared(): Boolean? =
        quietly {
            val response = client().get("openapi.json")
            if (!response.status.isSuccess()) return@quietly null
            (json.parseToJsonElement(response.bodyAsText()) as? JsonObject)?.let(ArtifactsDeclared::of)
        }

    override suspend fun list(directory: String): ArtifactListAnswer =
        quietly {
            val response =
                client().get("api/experimental/artifacts") {
                    applyLocationDirectory(directory)
                    timeout { requestTimeoutMillis = CHECK_TIMEOUT_MS }
                }
            ArtifactListAnswer.of(response.status.value, response.bodyAsText())
        } ?: ArtifactListAnswer.Unknown

    override suspend fun config(directory: String): ConfigDocuments? =
        quietly {
            val response =
                client().get("api/config") {
                    applyLocationDirectory(directory)
                    timeout { requestTimeoutMillis = CHECK_TIMEOUT_MS }
                }
            if (!response.status.isSuccess()) return@quietly null
            ConfigDocuments.read(json.parseToJsonElement(response.bodyAsText()))
        }

    /** The file's own folder is the location, since `fs/read` is confined to it (artifacts-enable-spike.md §1.5). */
    override suspend fun readFile(path: String): FileRead =
        quietly {
            val folder = path.substringBeforeLast('/')
            val name = path.substringAfterLast('/')
            val response =
                client().get("api/fs/read/${name.encodeURLPathPart()}") {
                    applyLocationDirectory(folder)
                    timeout { requestTimeoutMillis = CHECK_TIMEOUT_MS }
                }
            when {
                response.status.isSuccess() -> FileRead.Exists(response.readRawBytes())
                response.status.value == 404 -> FileRead.Missing
                else -> FileRead.Unknown
            }
        } ?: FileRead.Unknown

    override suspend fun writeFile(
        path: String,
        bytes: ByteArray,
        directory: String,
    ): Boolean {
        ready()
        val file =
            withContext(Dispatchers.IO) {
                File(cache, "restore-${System.nanoTime()}").apply {
                    parentFile?.mkdirs()
                    writeBytes(bytes)
                }
            }
        return try {
            uploads.write(path, file, directory) { _, _ -> }.isSuccess
        } finally {
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    override suspend fun putRules(sessionId: String) {
        ready()
        sessions.putInstructionEntryV2(sessionId, ArtifactPrompts.RULES_ID, ArtifactPrompts.RULES)
    }

    override suspend fun deleteRules(sessionId: String) {
        val response = client().delete("api/experimental/session/$sessionId/instructions/entries/${ArtifactPrompts.RULES_ID}")
        check(response.status.isSuccess() || response.status.value == 404) { "HTTP ${response.status.value}" }
    }

    override suspend fun runStart(sessionId: String): Long? {
        ready()
        return sessions.getSessionV2(sessionId).time?.busy
    }

    override suspend fun sessionExists(sessionId: String): Boolean? {
        ready()
        return try {
            sessions.getSessionV2(sessionId)
            true
        } catch (error: ResponseException) {
            if (error.response.status.value == 404) false else null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    /** `POST /api/session` with the lockdown as `permissions`, which come last and so win. */
    override suspend fun createSetupSession(
        directory: String,
        model: ModelChoice?,
        rules: List<ArtifactPrompts.Rule>,
    ): String {
        val body =
            buildJsonObject {
                put("title", ArtifactPrompts.SETUP_TITLE)
                putJsonObject("location") { put("directory", directory) }
                if (model != null) {
                    putJsonObject("model") {
                        put("id", model.modelId)
                        put("providerID", model.providerId)
                        model.variant?.let { put("variant", it) }
                    }
                }
                putJsonArray("permissions") {
                    rules.forEach { rule ->
                        addJsonObject {
                            put("action", rule.action)
                            put("resource", rule.resource)
                            put("effect", rule.effect)
                        }
                    }
                }
            }
        val response =
            client().post("api/session") {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        check(response.status.isSuccess()) { "POST /api/session: HTTP ${response.status.value}" }
        val data = (json.parseToJsonElement(response.bodyAsText()) as? JsonObject)?.get("data") as? JsonObject
        return (data?.get("id") as? JsonPrimitive)?.content ?: error("No session id")
    }

    override suspend fun prompt(
        sessionId: String,
        text: String,
    ) {
        ready()
        sessions.promptTextV2(sessionId, text, "queue")
    }

    override suspend fun approvals(sessionId: String): List<PendingItem> {
        ready()
        return sessions.listSessionPermissionsV2(sessionId).map { request ->
            PendingItem(
                request.id,
                request.sessionId,
                PendingItem.Kind.Approval,
                PendingItem.approvalText(request.action, request.resources, request.message),
                approval =
                    ApprovalRequest(
                        action = request.action,
                        resources = request.resources,
                        save = request.save,
                        message = request.message?.ifBlank { null },
                        files = ApprovalFile.read(request.metadata),
                    ),
            )
        }
    }

    /** A reject carries no note, so the setup run ends there. */
    override suspend fun reply(
        sessionId: String,
        requestId: String,
        allow: Boolean,
    ) {
        ready()
        sessions.respondToPermissionV2(sessionId, requestId, V2PermissionReplyRequestDto(if (allow) "once" else "reject"))
    }

    /** The in-app browser's files for today's project ([directory]); null when the computer couldn't be reached. */
    fun fetcher(directory: String): ArtifactFetcher =
        ArtifactFetcher { path ->
            quietly {
                val response = client().get(path) { applyLocationDirectory(directory) }
                val headers = response.headers.entries().associate { (name, values) -> name.lowercase() to values.last() }
                ArtifactFile(response.status.value, headers, response.readRawBytes())
            }
        }

    /** [call]'s value, or null when it threw (unreachable, timed out, an unreadable body). */
    private suspend fun <T> quietly(call: suspend () -> T?): T? =
        try {
            call()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

    private companion object {
        /** The 2 s check's calls give up after this. */
        const val CHECK_TIMEOUT_MS = 10_000L
    }
}
