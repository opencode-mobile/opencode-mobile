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
import dev.opencode.mobile.data.remote.dto.V2ArchiveSessionRequestDto
import dev.opencode.mobile.data.remote.dto.V2CompactSessionRequestDto
import dev.opencode.mobile.data.remote.dto.V2CreateSessionRequestDto
import dev.opencode.mobile.data.remote.dto.V2ForkSessionRequestDto
import dev.opencode.mobile.data.remote.dto.V2FormInfoDto
import dev.opencode.mobile.data.remote.dto.V2FormReplyRequestDto
import dev.opencode.mobile.data.remote.dto.V2GoalGetRequestDto
import dev.opencode.mobile.data.remote.dto.V2GoalGetResponseDto
import dev.opencode.mobile.data.remote.dto.V2GoalSessionInputDto
import dev.opencode.mobile.data.remote.dto.V2GoalStateDto
import dev.opencode.mobile.data.remote.dto.V2InboxDeliveryRequestDto
import dev.opencode.mobile.data.remote.dto.V2InboxItemDto
import dev.opencode.mobile.data.remote.dto.V2InstructionEntryRequestDto
import dev.opencode.mobile.data.remote.dto.V2InterruptResultDto
import dev.opencode.mobile.data.remote.dto.V2LocationDto
import dev.opencode.mobile.data.remote.dto.V2LocationInfoDto
import dev.opencode.mobile.data.remote.dto.V2ModelRefDto
import dev.opencode.mobile.data.remote.dto.V2MoveSessionRequestDto
import dev.opencode.mobile.data.remote.dto.V2PermissionReplyRequestDto
import dev.opencode.mobile.data.remote.dto.V2PermissionRequestDto
import dev.opencode.mobile.data.remote.dto.V2PromptFileDto
import dev.opencode.mobile.data.remote.dto.V2RenameSessionRequestDto
import dev.opencode.mobile.data.remote.dto.V2RevertStageRequestDto
import dev.opencode.mobile.data.remote.dto.V2SessionCommandRequestDto
import dev.opencode.mobile.data.remote.dto.V2SessionDto
import dev.opencode.mobile.data.remote.dto.V2SessionGenerateRequestDto
import dev.opencode.mobile.data.remote.dto.V2SessionGenerateResponseDto
import dev.opencode.mobile.data.remote.dto.V2SessionViewRequestDto
import dev.opencode.mobile.data.remote.dto.V2ShellDto
import dev.opencode.mobile.data.remote.dto.V2SwitchModelRequestDto
import dev.opencode.mobile.data.remote.dto.V2TextPromptRequestDto
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
import io.ktor.client.request.put
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

    constructor(httpClient: HttpClient, json: Json) : this(ClientProvider { httpClient }, json)

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

    /**
     * [search] filters by title, [rootsOnly] drops child sessions, and [limit] caps the newest
     * rows (opencode 1.x; older servers ignore all three).
     */
    suspend fun listSessions(
        directory: String? = null,
        search: String? = null,
        rootsOnly: Boolean = false,
        limit: Int? = null,
    ): List<SessionDto> =
        httpClient
            .get()
            .get("session") {
                applyDirectory(directory)
                search?.let { parameter("search", it) }
                if (rootsOnly) parameter("roots", "true")
                limit?.let { parameter("limit", it) }
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

    /**
     * `POST /api/session/{id}/interrupt`, reading its answer: true when a run was stopped, false when the session was
     * already idle (`{"interrupted": false}`). It answers at once; the run's cleanup and
     * `session.execution.interrupted` follow.
     */
    suspend fun interruptSessionResultV2(id: String): Boolean =
        httpClient
            .get()
            .post("api/session/$id/interrupt")
            .bodyOnSuccess<V2InterruptResultDto>()
            .interrupted

    /**
     * `GET /api/shell?location[directory]=…`: the agent shells running in [directory] (exited ones aren't listed),
     * for every session there; each one's `metadata.sessionID` says whose it is.
     */
    suspend fun listShellsV2(directory: String): List<V2ShellDto> =
        json.decodeDataEnvelope(
            httpClient
                .get()
                .get("api/shell") {
                    applyLocationDirectory(directory)
                }.bodyOnSuccess<JsonElement>(),
        )

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

    /** `POST /api/session/{id}/view` → 204; acknowledges the run that ended at [idle]. */
    suspend fun viewSessionV2(
        sessionId: String,
        idle: Long,
    ): Boolean =
        httpClient
            .get()
            .post("api/session/$sessionId/view") {
                setJsonBody(json, V2SessionViewRequestDto(idle = idle))
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

    /**
     * `DELETE /api/session/{id}/form/{form}`. With a [message] (`?message=`) the agent reads it as the question tool's
     * error and carries on; without one the run ends (OpenCode v2.2.5).
     */
    suspend fun cancelFormV2(
        sessionId: String,
        formId: String,
        message: String? = null,
    ): Boolean =
        httpClient
            .get()
            .delete("api/session/$sessionId/form/$formId") {
                message?.let { parameter("message", it) }
            }.ensureSuccess()

    // --- V2 home / session CRUD ---

    suspend fun locationForSessionList(directory: String): V2LocationInfoDto =
        httpClient
            .get()
            .get("api/location") {
                applyLocationDirectory(directory)
            }.body()

    /**
     * [projectId] lists every worktree of that project. Passing [directory] as well would
     * hide those worktree sessions, so a project list omits the directory query.
     */
    suspend fun listSessionsV2(
        directory: String? = null,
        parentId: String? = null,
        projectId: String? = null,
        archived: Boolean? = null,
        /** Only sessions without a parent (`parentID=null`); ignored when [parentId] is set. */
        rootsOnly: Boolean = false,
        /** Title filter. */
        search: String? = null,
    ): List<V2SessionDto> {
        val sessions = mutableListOf<V2SessionDto>()
        var cursor: String? = null
        var pages = 0
        do {
            val envelope =
                httpClient
                    .get()
                    .get("api/session") {
                        if (projectId != null) {
                            parameter("project", projectId)
                        } else {
                            applyDirectory(directory)
                        }
                        when {
                            parentId != null -> parameter("parentID", parentId)
                            rootsOnly -> parameter("parentID", "null")
                        }
                        archived?.let { parameter("archived", it.toString()) }
                        search?.let { parameter("search", it) }
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

    /**
     * One page of active root sessions, newest first, without following the cursor (#981). The
     * server returns a next cursor even on its last page, so callers compare the row count with
     * [limit] instead. Scoped like [listSessionsV2].
     */
    suspend fun listRootSessionPageV2(
        directory: String? = null,
        projectId: String? = null,
        limit: Int,
    ): List<V2SessionDto> =
        httpClient
            .get()
            .get("api/session") {
                if (projectId != null) {
                    parameter("project", projectId)
                } else {
                    applyDirectory(directory)
                }
                parameter("parentID", "null")
                parameter("archived", "false")
                parameter("order", "desc")
                parameter("limit", limit)
            }.body<ApiListEnvelope<V2SessionDto>>()
            .data

    /** A [parentId] creates a linked child at the parent's location, so no location is sent with it. */
    suspend fun createSessionV2(
        directory: String? = null,
        title: String? = null,
        parentId: String? = null,
        /** The session's model and level; null leaves it to the server. */
        model: V2ModelRefDto? = null,
    ): V2SessionDto =
        httpClient
            .get()
            .post("api/session") {
                setJsonBody(
                    json,
                    V2CreateSessionRequestDto(
                        parentId = parentId,
                        title = title?.takeIf { it.isNotBlank() },
                        location = if (parentId == null) directory?.let { V2LocationDto(directory = it) } else null,
                        model = model,
                    ),
                )
            }.decodeV2Session()

    /**
     * One page of `GET /api/session`, newest first (by last update). A [cursor] from an earlier page carries that
     * page's filters, so it is sent alone. The server keeps `cursor.next` set even on the last page; a page that
     * comes back empty is the end.
     */
    suspend fun listSessionsPageV2(
        directory: String? = null,
        search: String? = null,
        rootsOnly: Boolean = false,
        archived: Boolean? = null,
        limit: Int? = null,
        cursor: String? = null,
    ): ApiListEnvelope<V2SessionDto> =
        httpClient
            .get()
            .get("api/session") {
                if (cursor != null) {
                    parameter("cursor", cursor)
                } else {
                    applyDirectory(directory)
                    search?.let { parameter("search", it) }
                    if (rootsOnly) parameter("parentID", "null")
                    archived?.let { parameter("archived", it.toString()) }
                    limit?.let { parameter("limit", it) }
                }
            }.bodyOnSuccess()

    /**
     * `GET /api/session/{id}/message` (v2): a page of the session's messages as the server sends them (each with its
     * `content` parts), only [type] messages (`assistant`), in [order] (`desc`: newest first). A later page sends
     * [cursor] with [type] and [limit] again, which the cursor doesn't carry, but never [order]: the cursor carries
     * that, and the server refuses the two together (`InvalidCursorError`, OpenCode v2.2.5).
     */
    suspend fun listSessionMessagesPageV2(
        sessionId: String,
        type: String? = null,
        order: String? = null,
        limit: Int? = null,
        cursor: String? = null,
    ): ApiListEnvelope<JsonObject> =
        httpClient
            .get()
            .get("api/session/$sessionId/message") {
                cursor?.let { parameter("cursor", it) }
                type?.let { parameter("type", it) }
                if (cursor == null) order?.let { parameter("order", it) }
                limit?.let { parameter("limit", it) }
            }.bodyOnSuccess()

    /**
     * `POST /api/session/{id}/prompt` with [text], [delivery] and optional [files]: every prompt goes through the session's inbox,
     * so the reply is the inbox item, whose id cancels it (`DELETE /api/session/{id}/inbox/{item}`) until delivered.
     */
    suspend fun promptTextV2(
        id: String,
        text: String,
        delivery: String,
        /** Images and other files that go with the text, in the same request. */
        files: List<V2PromptFileDto> = emptyList(),
        /** The prompt's `metadata` (uploaded files' `attachments`, `displayText`); left out when null. */
        metadata: JsonObject? = null,
    ): V2InboxItemDto =
        httpClient
            .get()
            .post("api/session/$id/prompt") {
                setJsonBody(
                    json,
                    V2TextPromptRequestDto(text = text, delivery = delivery, files = files.ifEmpty { null }, metadata = metadata),
                )
            }.bodyOnSuccess<ApiDataEnvelope<V2InboxItemDto>>()
            .data

    /**
     * `POST /api/session/{id}/command` with [name] and [text] (and any [files], in the same request): runs a slash
     * command such as `/goal` on the session. `204` with no body; a failing command is a `500 CommandExecutionError`
     * whose message says why ("No active goal to pause.").
     */
    suspend fun runSessionCommandV2(
        id: String,
        name: String,
        text: String,
        files: List<V2PromptFileDto> = emptyList(),
    ): Boolean =
        httpClient
            .get()
            .post("api/session/$id/command") {
                setJsonBody(json, V2SessionCommandRequestDto(name = name, text = text, files = files.ifEmpty { null }))
            }.ensureSuccess()

    /**
     * `POST /api/rpc/opencode.goal/get` with `{"input": {"sessionID": …}}`: the session's `/goal` state, or null when it
     * has none. It answers the same with or without [directory] (`location[directory]`).
     */
    suspend fun goalV2(
        sessionId: String,
        directory: String?,
    ): V2GoalStateDto? =
        httpClient
            .get()
            .post("api/rpc/opencode.goal/get") {
                applyLocationDirectory(directory)
                setJsonBody(json, V2GoalGetRequestDto(V2GoalSessionInputDto(sessionId)))
            }.bodyOnSuccess<V2GoalGetResponseDto>()
            .output

    /** `POST /api/session/{id}/model`: the model and level the session's prompts and generate use from now on. */
    suspend fun switchSessionModelV2(
        id: String,
        model: V2ModelRefDto,
    ): Boolean =
        httpClient
            .get()
            .post("api/session/$id/model") {
                setJsonBody(json, V2SwitchModelRequestDto(model = model))
            }.ensureSuccess()

    /** Pending approvals owned by session [id] (not its subagents, which have their own sessions). */
    suspend fun listSessionPermissionsV2(id: String): List<V2PermissionRequestDto> =
        httpClient
            .get()
            .get("api/session/$id/permission")
            .bodyOnSuccess<ApiListEnvelope<V2PermissionRequestDto>>()
            .data

    /** Pending questions (forms) owned by session [id]. */
    suspend fun listSessionFormsV2(id: String): List<V2FormInfoDto> =
        httpClient
            .get()
            .get("api/session/$id/form")
            .bodyOnSuccess<ApiListEnvelope<V2FormInfoDto>>()
            .data

    /**
     * `PUT /api/experimental/session/{id}/instructions/entries/{key}`: adds or replaces one instruction entry. The
     * same value again changes nothing on the server. 204 with no body.
     */
    suspend fun putInstructionEntryV2(
        id: String,
        key: String,
        value: String,
    ): Boolean =
        httpClient
            .get()
            .put("api/experimental/session/$id/instructions/entries/$key") {
                setJsonBody(json, V2InstructionEntryRequestDto(value = value))
            }.ensureSuccess()

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

    /** Archives or restores a session (opencode 2.1.0+). Repeating the current state is a no-op. */
    suspend fun setSessionArchivedV2(
        id: String,
        archived: Boolean,
    ) {
        httpClient
            .get()
            .patch("api/session/$id") {
                setJsonBody(json, V2ArchiveSessionRequestDto(archived = archived))
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

    /**
     * `POST /api/session/{id}/move`: moves the session to [directory] at the next delivery
     * boundary. 204 with no body; 400 / 404 carry the server's message.
     */
    suspend fun moveSessionV2(
        id: String,
        directory: String,
    ): Boolean =
        httpClient
            .get()
            .post("api/session/$id/move") {
                setJsonBody(json, V2MoveSessionRequestDto(directory = directory))
            }.ensureSuccess()

    /**
     * `POST /api/session/{id}/generate` → `{ data: { text } }`: answers [prompt] from the session's
     * context and model without adding a message; nothing streams. One model call can outlast the
     * default request timeout, so this waits up to [SIDE_QUESTION_TIMEOUT_MS].
     */
    suspend fun generateSessionTextV2(
        id: String,
        prompt: String,
        timeoutMs: Long = SIDE_QUESTION_TIMEOUT_MS,
    ): String =
        httpClient
            .get()
            .post("api/session/$id/generate") {
                timeout {
                    requestTimeoutMillis = timeoutMs
                    socketTimeoutMillis = timeoutMs
                }
                setJsonBody(json, V2SessionGenerateRequestDto(prompt = prompt))
            }.bodyOnSuccess<ApiDataEnvelope<V2SessionGenerateResponseDto>>()
            .data
            .text

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

    suspend fun commitRevertV2(id: String): Boolean =
        httpClient
            .get()
            .post("api/session/$id/revert/commit")
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

    /**
     * `GET /api/session/{id}/diff`: the files the turn opened by user message [from] changed.
     * Servers before opencode 2.1.0 have no route and answer 404.
     */
    suspend fun getSessionDiffV2(
        id: String,
        from: String?,
        context: Int = 12,
    ): List<FileDiffDto> =
        httpClient
            .get()
            .get("api/session/$id/diff") {
                from?.let { parameter("from", it) }
                parameter("context", context)
            }.bodyOnSuccess<ApiListEnvelope<FileDiffDto>>()
            .data

    private suspend fun HttpResponse.decodeV2Session(): V2SessionDto = json.decodeDataEnvelope(body<JsonElement>())

    /**
     * Live v2 404s can omit Content-Type. Decoding first then throws
     * [io.ktor.client.call.NoTransformationFoundException] instead of HTTP 404.
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

        /** One `/btw` model call; longer than the default request timeout. */
        const val SIDE_QUESTION_TIMEOUT_MS = 300_000L
    }
}
