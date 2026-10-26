package dev.opencode.mobile.domain.session

import dev.opencode.mobile.data.remote.SessionsApi
import dev.opencode.mobile.data.remote.dto.V2FormReplyRequestDto
import dev.opencode.mobile.data.remote.dto.V2LocationInfoDto
import dev.opencode.mobile.data.remote.dto.V2PermissionReplyRequestDto

internal class V2SessionRemote(
    private val api: SessionsApi,
    private val adapter: SessionDomainAdapter,
) : SessionRemote {
    override suspend fun listPendingPermissions(directory: String?): List<PermissionRequest> =
        api.listPendingPermissionsV2(directory).map(adapter::toPermissionRequest)

    override suspend fun listPendingQuestions(directory: String?): List<QuestionRequest> =
        api.listPendingFormsV2(directory).map(adapter::toQuestionRequest)

    override suspend fun listSessions(directory: String?): List<Session> {
        val projectId =
            directory?.let { path ->
                runCatching { listableProjectId(api.locationForSessionList(path)) }.getOrNull()
            }
        return api
            .listSessionsV2(
                directory = if (projectId == null) directory else null,
                projectId = projectId,
                archived = false,
            ).map(adapter::toSession)
    }

    override suspend fun createSession(
        parentId: String?,
        title: String?,
        directory: String?,
    ): Session {
        var created = adapter.toSession(api.createSessionV2(directory, title, parentId))
        if (!title.isNullOrBlank() && created.title != title) {
            created = created.copy(title = title)
        }
        return created
    }

    override suspend fun getAllSessionStatuses(directory: String?): List<SessionStatus> =
        adapter.toSessionStatusesFromActive(api.getActiveSessionsV2())

    override suspend fun getSession(
        id: String,
        directory: String?,
    ): Session = adapter.toSession(api.getSessionV2(id))

    override suspend fun updateSession(
        id: String,
        title: String?,
        directory: String?,
    ): Session {
        require(!title.isNullOrBlank()) { "V2 rename requires a title" }
        api.renameSessionV2(id, title)
        return adapter.toSession(api.getSessionV2(id)).let { session ->
            if (session.title == title) session else session.copy(title = title)
        }
    }

    /** Callers gate on `supportsSessionArchive`; servers before 2.1.0 would ignore the field. */
    override suspend fun archiveSession(
        id: String,
        directory: String?,
    ): Session {
        api.setSessionArchivedV2(id, archived = true)
        return adapter.toSession(api.getSessionV2(id)).let { session ->
            if (session.isArchived) session else session.copy(archivedAtEpochMs = System.currentTimeMillis())
        }
    }

    override suspend fun deleteSession(
        id: String,
        directory: String?,
    ): Boolean = api.deleteSessionV2(id)

    override suspend fun listChildSessions(
        id: String,
        directory: String?,
    ): List<Session> = api.listSessionsV2(directory, parentId = id).map(adapter::toSession)

    override suspend fun listSessionTodos(
        id: String,
        directory: String?,
    ): List<SessionTodo> = throw SessionError.Http(404, "Todos REST is unavailable on v2")

    override suspend fun initializeSessionAgents(
        id: String,
        messageId: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ): Boolean = throw SessionError.Http(404, "session init is not available")

    override suspend fun forkSession(
        id: String,
        atMessageId: String?,
        directory: String?,
    ): Session = adapter.toSession(api.forkSessionV2(id, atMessageId))

    override suspend fun moveSession(
        id: String,
        directory: String,
    ) {
        api.moveSessionV2(id, directory)
    }

    override suspend fun askSideQuestion(
        sessionId: String,
        question: String,
    ): String = api.generateSessionTextV2(sessionId, SideQuestion.prompt(question)).trim()

    override suspend fun abortSession(
        id: String,
        directory: String?,
    ): Boolean = api.interruptSessionV2(id)

    override suspend fun shareSession(
        id: String,
        directory: String?,
    ): Session = throw SessionError.Http(404, "session share is not available")

    override suspend fun unshareSession(
        id: String,
        directory: String?,
    ): Session = throw SessionError.Http(404, "session share is not available")

    override suspend fun getSessionDiff(
        id: String,
        messageId: String?,
        directory: String?,
    ): List<FileDiff> = api.getSessionDiffV2(id, from = messageId).map(adapter::toFileDiff)

    override suspend fun getVcsInfo(directory: String?): VcsInfo = adapter.toVcsInfo(api.getVcsInfoV2(directory))

    override suspend fun getVcsStatus(directory: String?): List<VcsFileStatus> = api.getVcsStatusV2(directory).map(adapter::toVcsFileStatus)

    override suspend fun getVcsDiff(
        mode: VcsDiffMode,
        directory: String?,
        context: Int,
    ): List<FileDiff> = api.getVcsDiffV2(mode.v2WireValue, directory, context).map(adapter::toFileDiff)

    override suspend fun summarizeSession(
        id: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ): Boolean = api.compactSessionV2(id)

    override suspend fun compactSession(
        id: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ): CompactionStart {
        api.compactSessionV2(id)
        return CompactionStart.Enqueued
    }

    override suspend fun revertSession(
        id: String,
        messageId: String,
        partId: String?,
        directory: String?,
    ): Boolean = api.stageRevertV2(id, messageId, partId)

    override suspend fun unrevertSession(
        id: String,
        directory: String?,
    ): Boolean = api.clearRevertV2(id)

    override suspend fun commitRevert(
        id: String,
        directory: String?,
    ): Boolean = api.commitRevertV2(id)

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        decision: PermissionDecision,
        directory: String?,
    ): Boolean =
        api.respondToPermissionV2(
            sessionId,
            permissionId,
            V2PermissionReplyRequestDto(decision = adapter.toPermissionResponse(decision)),
        )

    override suspend fun replyToQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Boolean = replyToQuestion(sessionId = "", requestId = requestId, answers = answers, directory = directory)

    override suspend fun replyToQuestion(
        sessionId: String,
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Boolean = replyToQuestion(sessionId, requestId, answers, emptyList(), directory)

    override suspend fun replyToQuestion(
        sessionId: String,
        requestId: String,
        answers: List<List<String>>,
        questions: List<QuestionInfo>,
        directory: String?,
    ): Boolean =
        api.replyToFormV2(
            sessionId,
            requestId,
            V2FormReplyRequestDto(answer = adapter.formAnswer(questions, answers)),
        )

    override suspend fun rejectQuestion(
        requestId: String,
        directory: String?,
    ): Boolean = rejectQuestion(sessionId = "", requestId = requestId, directory = directory)

    override suspend fun rejectQuestion(
        sessionId: String,
        requestId: String,
        directory: String?,
    ): Boolean = api.cancelFormV2(sessionId, requestId)

    override suspend fun cancelPendingPrompt(
        sessionId: String,
        inboxId: String,
        directory: String?,
    ): Boolean = api.cancelInboxV2(sessionId, inboxId)

    override suspend fun steerPendingPrompt(
        sessionId: String,
        inboxId: String,
        directory: String?,
    ): Boolean = api.steerInboxV2(sessionId, inboxId)

    override suspend fun queuePendingPrompt(
        sessionId: String,
        inboxId: String,
        directory: String?,
    ): Boolean = api.queueInboxV2(sessionId, inboxId)

    override suspend fun markSessionViewed(
        sessionId: String,
        idleAtEpochMs: Long,
    ) {
        api.viewSessionV2(sessionId, idleAtEpochMs)
    }
}

/** Project id to list, or null when the directory should stay a directory-scoped list. */
internal fun listableProjectId(location: V2LocationInfoDto): String? {
    val project = location.project ?: return null
    val id = project.id?.takeIf { it.isNotBlank() } ?: return null
    val root = project.canonical ?: project.directory
    // Non-git directories resolve to the global "/" project, which is not this folder's sessions.
    if (root.isNullOrBlank() || root == "/") return null
    return id
}
