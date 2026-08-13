package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.ApiActiveSessionsEnvelope
import dev.opencode.mobile.data.remote.dto.ApiDataEnvelope
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
import dev.opencode.mobile.data.remote.dto.V2LocationDto
import dev.opencode.mobile.data.remote.dto.V2PermissionReplyRequestDto
import dev.opencode.mobile.data.remote.dto.V2PermissionRequestDto
import dev.opencode.mobile.data.remote.dto.V2RenameSessionRequestDto
import dev.opencode.mobile.data.remote.dto.V2RevertStageRequestDto
import dev.opencode.mobile.data.remote.dto.V2SessionDto
import dev.opencode.mobile.data.remote.dto.V2VcsInfoDto
import dev.opencode.mobile.data.remote.dto.VcsInfoDto
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.applyDirectory
import dev.opencode.mobile.network.applyLocationDirectory
import dev.opencode.mobile.network.setJsonBody
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
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
            }.body()

    suspend fun unrevertSession(
        id: String,
        directory: String? = null,
    ): Boolean =
        httpClient
            .get()
            .post("session/$id/unrevert") {
                applyDirectory(directory)
            }.body()

    suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        body: PermissionResponseRequestDto,
        directory: String? = null,
    ): Boolean =
        httpClient
            .get()
            .post("session/$sessionId/permissions/$permissionId") {
                applyDirectory(directory)
                setJsonBody(json, body)
            }.body()

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
            }.body()

    suspend fun rejectQuestion(
        requestId: String,
        directory: String? = null,
    ): Boolean =
        httpClient
            .get()
            .post("question/$requestId/reject") {
                applyDirectory(directory)
            }.body()

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

    suspend fun listPendingQuestionsV2(directory: String? = null): List<QuestionRequestDto> =
        httpClient
            .get()
            .get("api/question/request") {
                applyLocationDirectory(directory)
            }.body<ApiListEnvelope<QuestionRequestDto>>()
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

    // --- V2 home / session CRUD ---

    suspend fun listSessionsV2(directory: String? = null): List<V2SessionDto> {
        val sessions = mutableListOf<V2SessionDto>()
        var cursor: String? = null
        var pages = 0
        do {
            val envelope =
                httpClient
                    .get()
                    .get("api/session") {
                        applyDirectory(directory)
                        cursor?.let { parameter("cursor", it) }
                    }.body<ApiListEnvelope<V2SessionDto>>()
            sessions += envelope.data
            cursor = envelope.cursor?.next?.takeIf { it.isNotBlank() }
            pages++
        } while (cursor != null && pages < MAX_V2_SESSION_LIST_PAGES)
        return sessions
    }

    suspend fun createSessionV2(directory: String? = null): V2SessionDto =
        httpClient
            .get()
            .post("api/session") {
                setJsonBody(
                    json,
                    V2CreateSessionRequestDto(
                        location = directory?.let { V2LocationDto(directory = it) },
                    ),
                )
            }.body<ApiDataEnvelope<V2SessionDto>>()
            .data

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
            .body<ApiDataEnvelope<V2SessionDto>>()
            .data

    suspend fun renameSessionV2(
        id: String,
        title: String,
    ) {
        httpClient
            .get()
            .post("api/session/$id/rename") {
                setJsonBody(json, V2RenameSessionRequestDto(title = title))
            }.ensureSuccess()
    }

    suspend fun archiveSessionV2(id: String) {
        httpClient
            .get()
            .post("api/session/$id/archive")
            .ensureSuccess()
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
                setJsonBody(json, V2ForkSessionRequestDto(messageId = messageId))
            }.body<ApiDataEnvelope<V2SessionDto>>()
            .data

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
                setJsonBody(json, V2RevertStageRequestDto(messageId = messageId, partId = partId, files = true))
            }.ensureSuccess()

    suspend fun clearRevertV2(id: String): Boolean =
        httpClient
            .get()
            .post("api/session/$id/revert/clear")
            .ensureSuccess()

    suspend fun getVcsInfoV2(directory: String? = null): V2VcsInfoDto =
        httpClient
            .get()
            .get("api/vcs") {
                applyLocationDirectory(directory)
            }.body<ApiLocatedEnvelope<V2VcsInfoDto>>()
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

    private fun HttpResponse.ensureSuccess(): Boolean {
        check(status.isSuccess()) { "HTTP ${status.value}: ${status.description}" }
        return true
    }

    private fun interface ClientProvider {
        fun get(): HttpClient
    }

    private companion object {
        const val MAX_V2_SESSION_LIST_PAGES = 20
    }
}
