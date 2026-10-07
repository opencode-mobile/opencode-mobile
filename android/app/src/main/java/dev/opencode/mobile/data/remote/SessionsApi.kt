package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.ApiActiveSessionsEnvelope
import dev.opencode.mobile.data.remote.dto.ApiListEnvelope
import dev.opencode.mobile.data.remote.dto.ApiLocatedEnvelope
import dev.opencode.mobile.data.remote.dto.ApiSessionActiveDto
import dev.opencode.mobile.data.remote.dto.CreateSessionRequestDto
import dev.opencode.mobile.data.remote.dto.FileDiffDto
import dev.opencode.mobile.data.remote.dto.ForkSessionRequestDto
import dev.opencode.mobile.data.remote.dto.InitSessionRequestDto
import dev.opencode.mobile.data.remote.dto.PermissionRequestDto
import dev.opencode.mobile.data.remote.dto.PermissionResponseRequestDto
import dev.opencode.mobile.data.remote.dto.QuestionReplyRequestDto
import dev.opencode.mobile.data.remote.dto.QuestionRequestDto
import dev.opencode.mobile.data.remote.dto.RevertSessionRequestDto
import dev.opencode.mobile.data.remote.dto.SessionDto
import dev.opencode.mobile.data.remote.dto.SummarizeSessionRequestDto
import dev.opencode.mobile.data.remote.dto.TodoDto
import dev.opencode.mobile.data.remote.dto.UpdateSessionRequestDto
import dev.opencode.mobile.data.remote.dto.V2CompactSessionRequestDto
import dev.opencode.mobile.data.remote.dto.V2CreateSessionRequestDto
import dev.opencode.mobile.data.remote.dto.V2ForkSessionRequestDto
import dev.opencode.mobile.data.remote.dto.V2FormInfoDto
import dev.opencode.mobile.data.remote.dto.V2FormReplyRequestDto
import dev.opencode.mobile.data.remote.dto.V2InboxDeliveryRequestDto
import dev.opencode.mobile.data.remote.dto.V2InboxItemDto
import dev.opencode.mobile.data.remote.dto.V2LocationDto
import dev.opencode.mobile.data.remote.dto.V2PermissionReplyRequestDto
import dev.opencode.mobile.data.remote.dto.V2PermissionRequestDto
import dev.opencode.mobile.data.remote.dto.V2RenameSessionRequestDto
import dev.opencode.mobile.data.remote.dto.V2RevertStageRequestDto
import dev.opencode.mobile.data.remote.dto.V2SessionDto
import dev.opencode.mobile.data.remote.dto.V2VcsInfoDto
import dev.opencode.mobile.data.remote.dto.VcsFileStatusDto
import dev.opencode.mobile.data.remote.dto.VcsInfoDto
import dev.opencode.mobile.data.remote.dto.decodeDataEnvelope
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.applyDirectory
import dev.opencode.mobile.network.applyLocationDirectory
import dev.opencode.mobile.network.setJsonBody
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

class SessionsApi private constructor(
    private val httpClient: ClientProvider,
    private val json: Json,
) {
    @Inject
    constructor(httpClient: OpencodeHttpClient, json: Json) : this(ClientProvider(httpClient::get), json)

    internal constructor(httpClient: HttpClient, json: Json) : this(ClientProvider { httpClient }, json)

    suspend fun listPendingPermissions(directory: String? = null): List<PermissionRequestDto> =
        httpClient
            .get()
            .get("permission") {
                applyDirectory(directory)
            }.body()

    suspend fun listPendingQuestions(directory: String? = null): List<QuestionRequestDto> =
        httpClient
            .get()
            .get("question") {
                applyDirectory(directory)
            }.body()

    suspend fun listSessions(directory: String? = null): List<SessionDto> =
        httpClient
            .get()
            .get("session") {
                applyDirectory(directory)
            }.body()

    suspend fun createSession(
        body: CreateSessionRequestDto,
        directory: String? = null,
    ): SessionDto =
        httpClient
            .get()
            .post("session") {
                applyDirectory(directory)
                setJsonBody(json, body)
            }.body()

    suspend fun getAllSessionStatuses(directory: String? = null): Map<String, JsonObject> =
        httpClient
            .get()
            .get("session/status") {
                applyDirectory(directory)
            }.body()

    suspend fun getSession(
        id: String,
        directory: String? = null,
    ): SessionDto =
        httpClient
            .get()
            .get("session/$id") {
                applyDirectory(directory)
            }.body()

    suspend fun updateSession(
        id: String,
        body: UpdateSessionRequestDto,
        directory: String? = null,
    ): SessionDto =
        httpClient
            .get()
            .patch("session/$id") {
                applyDirectory(directory)
                setJsonBody(json, body)
            }.body()

    suspend fun deleteSession(
        id: String,
        directory: String? = null,
    ): Boolean =
        httpClient
            .get()
            .delete("session/$id") {
                applyDirectory(directory)
            }.body()

    suspend fun listChildSessions(
        id: String,
        directory: String? = null,
    ): List<SessionDto> =
        httpClient
            .get()
            .get("session/$id/children") {
                applyDirectory(directory)
            }.body()

    suspend fun listSessionTodos(
        id: String,
        directory: String? = null,
    ): List<TodoDto> =
        httpClient
            .get()
            .get("session/$id/todo") {
                applyDirectory(directory)
            }.body()

    suspend fun initializeSessionAgents(
        id: String,
        body: InitSessionRequestDto,
        directory: String? = null,
    ): Boolean =
        httpClient
            .get()
            .post("session/$id/init") {
                applyDirectory(directory)
                setJsonBody(json, body)
            }.body()

    suspend fun forkSession(
        id: String,
        body: ForkSessionRequestDto,
        directory: String? = null,
    ): SessionDto =
        httpClient
            .get()
            .post("session/$id/fork") {
                applyDirectory(directory)
                setJsonBody(json, body)
            }.body()

    suspend fun abortSession(
        id: String,
        directory: String? = null,
    ): Boolean =
        httpClient
            .get()
            .post("session/$id/abort") {
                applyDirectory(directory)
            }.body()

    suspend fun interruptSessionV2(id: String): Boolean =
        httpClient
            .get()
            .post("api/session/$id/interrupt")
            .ensureSuccess()

    suspend fun listInboxV2(sessionId: String): List<V2InboxItemDto> =
        httpClient
            .get()
            .get("api/session/$sessionId/inbox")
            .body<ApiListEnvelope<V2InboxItemDto>>()
            .data

    suspend fun cancelInboxV2(
        sessionId: String,
        inboxId: String,
    ): Boolean =
        httpClient
            .get()
            .delete("api/session/$sessionId/inbox/$inboxId")
            .ensureSuccess()

    suspend fun steerInboxV2(
        sessionId: String,
        inboxId: String,
    ): Boolean =
        httpClient
            .get()
            .patch("api/session/$sessionId/inbox/$inboxId") {
                setJsonBody(json, V2InboxDeliveryRequestDto(delivery = "steer"))
            }.ensureSuccess()

    suspend fun queueInboxV2(
        sessionId: String,
        inboxId: String,
    ): Boolean =
        httpClient
            .get()
            .patch("api/session/$sessionId/inbox/$inboxId") {
                setJsonBody(json, V2InboxDeliveryRequestDto(delivery = "queue"))
            }.ensureSuccess()

    suspend fun shareSession(
        id: String,
        directory: String? = null,
    ): SessionDto =
        httpClient
            .get()
            .post("session/$id/share") {
                applyDirectory(directory)
            }.body()

    suspend fun unshareSession(
        id: String,
        directory: String? = null,
    ): SessionDto =
        httpClient
            .get()
            .delete("session/$id/share") {
                applyDirectory(directory)
            }.body()

    suspend fun getSessionDiff(
        id: String,
        messageId: String?,
        directory: String? = null,
    ): List<FileDiffDto> =
        httpClient
            .get()
            .get("session/$id/diff") {
                applyDirectory(directory)
                messageId?.let { parameter("messageID", it) }
            }.body()

    suspend fun getVcsInfo(directory: String? = null): VcsInfoDto =
        httpClient
            .get()
            .get("vcs") {
                applyDirectory(directory)
            }.body()

    suspend fun getVcsStatus(directory: String? = null): List<VcsFileStatusDto> =
        httpClient
            .get()
            .get("vcs/status") {
                applyDirectory(directory)
            }.body()

    suspend fun getVcsDiff(
        mode: String,
        directory: String? = null,
        context: Int = 12,
    ): List<FileDiffDto> =
        httpClient
            .get()
            .get("vcs/diff") {
                applyDirectory(directory)
                parameter("mode", mode)
                parameter("context", context)
            }.body()

    suspend fun summarizeSession(
        id: String,
        body: SummarizeSessionRequestDto,
        directory: String? = null,
    ): Boolean =
        httpClient
            .get()
            .post("session/$id/summarize") {
                // Compaction can stream for minutes; OkHttp's default 10s read was aborting
                // the POST while SSE still delivered session.compacted (false failure toast).
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                }
                applyDirectory(directory)
                setJsonBody(json, body)
            }.body()

    suspend fun revertSession(
        id: String,
        body: RevertSessionRequestDto,
        directory: String? = null,
    ): Boolean =
        httpClient
            .get()
            .post("session/$id/revert") {
                applyDirectory(directory)
                setJsonBody(json, body)
            }.ensureSuccess()

    suspend fun unrevertSession(
        id: String,
        directory: String? = null,
    ): Boolean =
        httpClient
            .get()
            .post("session/$id/unrevert") {
                applyDirectory(directory)
            }.ensureSuccess()

    suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        body: PermissionResponseRequestDto,
        directory: String? = null,
    ): Boolean =
        httpClient
            .get()
            .post("permission/$permissionId/reply") {
                applyDirectory(directory)
                setJsonBody(json, body)
            }.bodyOnSuccess()

    suspend fun replyToQuestion(
        requestId: String,
        body: QuestionReplyRequestDto,
        directory: String? = null,
    ): Boolean =
        httpClient
            .get()
            .post("question/$requestId/reply") {
                applyDirectory(directory)
                setJsonBody(json, body)
            }.bodyOnSuccess()

    suspend fun rejectQuestion(
        requestId: String,
        directory: String? = null,
    ): Boolean =
        httpClient
            .get()
            .post("question/$requestId/reject") {
                applyDirectory(directory)
            }.bodyOnSuccess()

    suspend fun listPendingPermissionsV2(directory: String? = null): List<V2PermissionRequestDto> =
        httpClient
            .get()
            .get("api/permission/request") {
                applyLocationDirectory(directory)
            }.body<ApiListEnvelope<V2PermissionRequestDto>>()
            .data

    suspend fun respondToPermissionV2(
        sessionId: String,
        permissionId: String,
        body: V2PermissionReplyRequestDto,
    ): Boolean =
        httpClient
            .get()
            .post("api/session/$sessionId/permission/$permissionId/reply") {
                setJsonBody(json, body)
            }.ensureSuccess()

    suspend fun listPendingFormsV2(directory: String? = null): List<V2FormInfoDto> =
        httpClient
            .get()
            .get("api/form") {
                applyLocationDirectory(directory)
            }.bodyOnSuccess<ApiListEnvelope<V2FormInfoDto>>()
            .data

    suspend fun replyToQuestionV2(
        sessionId: String,
        requestId: String,
        body: QuestionReplyRequestDto,
    ): Boolean =
        httpClient
            .get()
            .post("api/session/$sessionId/question/$requestId/reply") {
                setJsonBody(json, body)
            }.ensureSuccess()

    suspend fun rejectQuestionV2(
        sessionId: String,
        requestId: String,
    ): Boolean =
        httpClient
            .get()
            .post("api/session/$sessionId/question/$requestId/reject")
            .ensureSuccess()

    suspend fun replyToFormV2(
        sessionId: String,
        formId: String,
        body: V2FormReplyRequestDto,
    ): Boolean =
        httpClient
            .get()
            .post("api/session/$sessionId/form/$formId/reply") {
                setJsonBody(json, body)
            }.ensureSuccess()

    suspend fun cancelFormV2(
        sessionId: String,
        formId: String,
    ): Boolean =
        httpClient
            .get()
            .delete("api/session/$sessionId/form/$formId")
            .ensureSuccess()

    // --- V2 home / session CRUD ---

    suspend fun listSessionsV2(
        directory: String? = null,
        parentId: String? = null,
    ): List<V2SessionDto> {
        val sessions = mutableListOf<V2SessionDto>()
        var cursor: String? = null
        var pages = 0
        do {
            val envelope =
                httpClient
                    .get()
                    .get("api/session") {
                        applyDirectory(directory)
                        parentId?.let { parameter("parentID", it) }
                        cursor?.let { parameter("cursor", it) }
                    }.body<ApiListEnvelope<V2SessionDto>>()
            sessions += envelope.data
            val next = envelope.cursor?.next?.takeIf { it.isNotBlank() }
            if (next == cursor) break
            cursor = next
            pages++
        } while (cursor != null && pages < MAX_V2_SESSION_LIST_PAGES)
        return sessions
    }

    suspend fun createSessionV2(
        directory: String? = null,
        title: String? = null,
    ): V2SessionDto =
        httpClient
            .get()
            .post("api/session") {
                setJsonBody(
                    json,
                    V2CreateSessionRequestDto(
                        title = title?.takeIf { it.isNotBlank() },
                        location = directory?.let { V2LocationDto(directory = it) },
                    ),
                )
            }.decodeV2Session()

    suspend fun getActiveSessionsV2(): Map<String, ApiSessionActiveDto> =
        httpClient
            .get()
            .get("api/session/active")
            .body<ApiActiveSessionsEnvelope>()
            .data

    suspend fun getSessionV2(id: String): V2SessionDto =
        httpClient
            .get()
            .get("api/session/$id")
            .decodeV2Session()

    suspend fun renameSessionV2(
        id: String,
        title: String,
    ) {
        httpClient
            .get()
            .patch("api/session/$id") {
                setJsonBody(json, V2RenameSessionRequestDto(title = title))
            }.ensureSuccess()
    }

    suspend fun deleteSessionV2(id: String): Boolean =
        httpClient
            .get()
            .delete("api/session/$id")
            .ensureSuccess()

    suspend fun forkSessionV2(
        id: String,
        messageId: String?,
    ): V2SessionDto =
        httpClient
            .get()
            .post("api/session/$id/fork") {
                val before = messageId?.takeIf { it.isNotBlank() }
                if (before == null) {
                    setJsonBody(json, JsonObject(emptyMap()))
                } else {
                    setJsonBody(json, V2ForkSessionRequestDto(before = before))
                }
            }.decodeV2Session()

    suspend fun compactSessionV2(id: String): Boolean =
        httpClient
            .get()
            .post("api/session/$id/compact") {
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                }
                setJsonBody(json, V2CompactSessionRequestDto(delivery = "steer"))
            }.ensureSuccess()

    suspend fun stageRevertV2(
        id: String,
        messageId: String,
        partId: String?,
    ): Boolean =
        httpClient
            .get()
            .post("api/session/$id/revert/stage") {
                setJsonBody(json, V2RevertStageRequestDto(messageId = messageId, files = true))
            }.ensureSuccess()

    suspend fun clearRevertV2(id: String): Boolean =
        httpClient
            .get()
            .delete("api/session/$id/revert")
            .ensureSuccess()

    suspend fun getVcsInfoV2(directory: String? = null): V2VcsInfoDto =
        httpClient
            .get()
            .get("api/vcs") {
                applyLocationDirectory(directory)
            }.body<ApiLocatedEnvelope<V2VcsInfoDto>>()
            .data

    suspend fun getVcsStatusV2(directory: String? = null): List<VcsFileStatusDto> =
        httpClient
            .get()
            .get("api/vcs/status") {
                applyLocationDirectory(directory)
            }.body<ApiLocatedEnvelope<List<VcsFileStatusDto>>>()
            .data

    suspend fun getVcsDiffV2(
        mode: String,
        directory: String? = null,
        context: Int = 12,
    ): List<FileDiffDto> =
        httpClient
            .get()
            .get("api/vcs/diff") {
                applyLocationDirectory(directory)
                parameter("mode", mode)
                parameter("context", context)
            }.body<ApiLocatedEnvelope<List<FileDiffDto>>>()
            .data

    private suspend fun HttpResponse.decodeV2Session(): V2SessionDto = json.decodeDataEnvelope(body<JsonElement>())

    /**
     * Live v2 404s can omit Content-Type. Decoding first then throws
     * [io.ktor.client.call.NoTransformationFoundException] instead of HTTP 404,
     * which skips the question→form fallback.
     */
    private suspend inline fun <reified T> HttpResponse.bodyOnSuccess(): T {
        if (!status.isSuccess()) throw failure()
        return body()
    }

    private suspend fun HttpResponse.ensureSuccess(): Boolean {
        if (!status.isSuccess()) throw failure()
        bodyAsText()
        return true
    }

    /** Keeps the error body (v2 sends `{"_tag":…,"message":…}`) for user-facing messages. */
    private suspend fun HttpResponse.failure(): ResponseException {
        val text = runCatching { bodyAsText() }.getOrDefault("")
        return HttpBodyResponseException(this, text.ifBlank { "HTTP ${status.value}: ${status.description}" })
    }

    private fun interface ClientProvider {
        fun get(): HttpClient
    }

    private companion object {
        const val MAX_V2_SESSION_LIST_PAGES = 500
    }
}
