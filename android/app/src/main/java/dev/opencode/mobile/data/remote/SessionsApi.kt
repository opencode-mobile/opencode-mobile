package dev.opencode.mobile.data.remote

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
import dev.opencode.mobile.data.remote.dto.VcsInfoDto
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.applyDirectory
import dev.opencode.mobile.network.setJsonBody
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
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

    private fun interface ClientProvider {
        fun get(): HttpClient
    }
}
