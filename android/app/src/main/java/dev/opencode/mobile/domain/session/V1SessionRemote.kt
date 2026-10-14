package dev.opencode.mobile.domain.session

import dev.opencode.mobile.data.remote.SessionsApi
import dev.opencode.mobile.data.remote.dto.CreateSessionRequestDto
import dev.opencode.mobile.data.remote.dto.ForkSessionRequestDto
import dev.opencode.mobile.data.remote.dto.InitSessionRequestDto
import dev.opencode.mobile.data.remote.dto.PermissionResponseRequestDto
import dev.opencode.mobile.data.remote.dto.QuestionReplyRequestDto
import dev.opencode.mobile.data.remote.dto.RevertSessionRequestDto
import dev.opencode.mobile.data.remote.dto.SummarizeSessionRequestDto
import dev.opencode.mobile.data.remote.dto.UpdateSessionRequestDto
import dev.opencode.mobile.data.remote.dto.UpdateSessionTimeDto

internal class V1SessionRemote(
    private val api: SessionsApi,
    private val adapter: SessionDomainAdapter,
) : SessionRemote {
    override suspend fun listPendingPermissions(directory: String?): List<PermissionRequest> =
        api.listPendingPermissions(directory).map(adapter::toPermissionRequest)

    override suspend fun listPendingQuestions(directory: String?): List<QuestionRequest> =
        api.listPendingQuestions(directory).map(adapter::toQuestionRequest)

    override suspend fun listSessions(directory: String?): List<Session> = api.listSessions(directory).map(adapter::toSession)

    override suspend fun createSession(
        parentId: String?,
        title: String?,
        directory: String?,
    ): Session =
        adapter.toSession(
            api.createSession(CreateSessionRequestDto(parentId = parentId, title = title), directory),
        )

    override suspend fun getAllSessionStatuses(directory: String?): List<SessionStatus> =
        api.getAllSessionStatuses(directory).map { (id, status) -> adapter.toSessionStatus(id, status) }

    override suspend fun getSession(
        id: String,
        directory: String?,
    ): Session = adapter.toSession(api.getSession(id, directory))

    override suspend fun updateSession(
        id: String,
        title: String?,
        directory: String?,
    ): Session = adapter.toSession(api.updateSession(id, UpdateSessionRequestDto(title = title), directory))

    override suspend fun archiveSession(
        id: String,
        directory: String?,
    ): Session =
        adapter.toSession(
            api.updateSession(
                id,
                UpdateSessionRequestDto(
                    time = UpdateSessionTimeDto(archived = System.currentTimeMillis()),
                ),
                directory,
            ),
        )

    override suspend fun deleteSession(
        id: String,
        directory: String?,
    ): Boolean = api.deleteSession(id, directory)

    override suspend fun listChildSessions(
        id: String,
        directory: String?,
    ): List<Session> = api.listChildSessions(id, directory).map(adapter::toSession)

    override suspend fun listSessionTodos(
        id: String,
        directory: String?,
    ): List<SessionTodo> = adapter.toTodos(api.listSessionTodos(id, directory))

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
    ): Session = adapter.toSession(api.forkSession(id, ForkSessionRequestDto(messageId = atMessageId), directory))

    override suspend fun abortSession(
        id: String,
        directory: String?,
    ): Boolean = api.abortSession(id, directory)

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
    ): List<FileDiff> = api.getSessionDiff(id, messageId, directory).map(adapter::toFileDiff)

    override suspend fun getVcsInfo(directory: String?): VcsInfo = adapter.toVcsInfo(api.getVcsInfo(directory))

    override suspend fun getVcsStatus(directory: String?): List<VcsFileStatus> = api.getVcsStatus(directory).map(adapter::toVcsFileStatus)

    override suspend fun getVcsDiff(
        mode: VcsDiffMode,
        directory: String?,
        context: Int,
    ): List<FileDiff> = api.getVcsDiff(mode.wireValue, directory, context).map(adapter::toFileDiff)

    override suspend fun summarizeSession(
        id: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ): Boolean =
        api.summarizeSession(
            id,
            SummarizeSessionRequestDto(providerId = providerId, modelId = modelId),
            directory,
        )

    override suspend fun revertSession(
        id: String,
        messageId: String,
        partId: String?,
        directory: String?,
    ): Boolean = api.revertSession(id, RevertSessionRequestDto(messageId = messageId, partId = partId), directory)

    override suspend fun unrevertSession(
        id: String,
        directory: String?,
    ): Boolean = api.unrevertSession(id, directory)

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        decision: PermissionDecision,
        directory: String?,
    ): Boolean =
        api.respondToPermission(
            sessionId,
            permissionId,
            PermissionResponseRequestDto(reply = adapter.toPermissionResponse(decision)),
            directory,
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
    ): Boolean = api.replyToQuestion(requestId, QuestionReplyRequestDto(answers = answers), directory)

    override suspend fun rejectQuestion(
        requestId: String,
        directory: String?,
    ): Boolean = rejectQuestion(sessionId = "", requestId = requestId, directory = directory)

    override suspend fun rejectQuestion(
        sessionId: String,
        requestId: String,
        directory: String?,
    ): Boolean = api.rejectQuestion(requestId, directory)

    override suspend fun cancelPendingPrompt(
        sessionId: String,
        inboxId: String,
        directory: String?,
    ): Boolean = false

    override suspend fun steerPendingPrompt(
        sessionId: String,
        inboxId: String,
        directory: String?,
    ): Boolean = false

    override suspend fun queuePendingPrompt(
        sessionId: String,
        inboxId: String,
        directory: String?,
    ): Boolean = false

    /** v1 servers have no read state; never touches the network. */
    override suspend fun markSessionViewed(
        sessionId: String,
        idleAtEpochMs: Long,
    ) = Unit
}
