package dev.opencode.mobile.domain.session

import dev.opencode.mobile.data.remote.SessionsApi
import dev.opencode.mobile.data.remote.dto.InitSessionRequestDto
import dev.opencode.mobile.data.remote.dto.QuestionReplyRequestDto
import dev.opencode.mobile.data.remote.dto.V2PermissionReplyRequestDto

internal class V2SessionRemote(
    private val api: SessionsApi,
    private val adapter: SessionDomainAdapter,
) : SessionRemote {
    override suspend fun listPendingPermissions(directory: String?): List<PermissionRequest> =
        api.listPendingPermissionsV2(directory).map(adapter::toPermissionRequest)

    override suspend fun listPendingQuestions(directory: String?): List<QuestionRequest> =
        api.listPendingQuestionsV2(directory).map(adapter::toQuestionRequest)

    override suspend fun listSessions(directory: String?): List<Session> = api.listSessionsV2(directory).map(adapter::toSession)

    override suspend fun createSession(
        parentId: String?,
        title: String?,
        directory: String?,
    ): Session {
        var created = adapter.toSession(api.createSessionV2(directory))
        if (!title.isNullOrBlank()) {
            api.renameSessionV2(created.id, title)
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

    override suspend fun archiveSession(
        id: String,
        directory: String?,
    ): Session {
        api.archiveSessionV2(id)
        return adapter.toSession(api.getSessionV2(id)).let { session ->
            if (session.archivedAtEpochMs != null) {
                session
            } else {
                session.copy(archivedAtEpochMs = System.currentTimeMillis())
            }
        }
    }

    override suspend fun deleteSession(
        id: String,
        directory: String?,
    ): Boolean = api.deleteSessionV2(id)

    override suspend fun listChildSessions(
        id: String,
        directory: String?,
    ): List<Session> = api.listSessionsV2(directory).filter { it.parentId == id }.map(adapter::toSession)

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
    ): Boolean =
        api.initializeSessionAgents(
            id,
            InitSessionRequestDto(messageId = messageId, providerId = providerId, modelId = modelId),
            directory,
        )

    override suspend fun forkSession(
        id: String,
        atMessageId: String?,
        directory: String?,
    ): Session = adapter.toSession(api.forkSessionV2(id, atMessageId))

    override suspend fun abortSession(
        id: String,
        directory: String?,
    ): Boolean = api.interruptSessionV2(id)

    override suspend fun shareSession(
        id: String,
        directory: String?,
    ): Session = adapter.toSession(api.shareSession(id, directory))

    override suspend fun unshareSession(
        id: String,
        directory: String?,
    ): Session = adapter.toSession(api.unshareSession(id, directory))

    override suspend fun getSessionDiff(
        id: String,
        messageId: String?,
        directory: String?,
    ): List<FileDiff> = throw SessionError.Http(404, "session message diff is not available")

    override suspend fun getVcsInfo(directory: String?): VcsInfo = adapter.toVcsInfo(api.getVcsInfoV2(directory))

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

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        decision: PermissionDecision,
        directory: String?,
    ): Boolean =
        api.respondToPermissionV2(
            sessionId,
            permissionId,
            V2PermissionReplyRequestDto(reply = adapter.toPermissionResponse(decision)),
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
    ): Boolean = api.replyToQuestionV2(sessionId, requestId, QuestionReplyRequestDto(answers = answers))

    override suspend fun rejectQuestion(
        requestId: String,
        directory: String?,
    ): Boolean = rejectQuestion(sessionId = "", requestId = requestId, directory = directory)

    override suspend fun rejectQuestion(
        sessionId: String,
        requestId: String,
        directory: String?,
    ): Boolean = api.rejectQuestionV2(sessionId, requestId)
}
