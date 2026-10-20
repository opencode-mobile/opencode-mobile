package dev.opencode.mobile.domain.session

import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.ActiveConnectionManager

internal class ProtocolSelectedSessionRemote(
    private val v1: SessionRemote,
    private val v2: SessionRemote,
    private val activeConnectionManager: ActiveConnectionManager?,
) : SessionRemote {
    private suspend fun selected(): SessionRemote = if (activeConnectionManager?.currentProtocolOrProbe() == ServerProtocol.V2) v2 else v1

    override suspend fun listPendingPermissions(directory: String?): List<PermissionRequest> = selected().listPendingPermissions(directory)

    override suspend fun listPendingQuestions(directory: String?): List<QuestionRequest> = selected().listPendingQuestions(directory)

    override suspend fun listSessions(directory: String?): List<Session> = selected().listSessions(directory)

    override suspend fun createSession(
        parentId: String?,
        title: String?,
        directory: String?,
    ): Session = selected().createSession(parentId, title, directory)

    override suspend fun getAllSessionStatuses(directory: String?): List<SessionStatus> = selected().getAllSessionStatuses(directory)

    override suspend fun getSession(
        id: String,
        directory: String?,
    ): Session = selected().getSession(id, directory)

    override suspend fun updateSession(
        id: String,
        title: String?,
        directory: String?,
    ): Session = selected().updateSession(id, title, directory)

    override suspend fun archiveSession(
        id: String,
        directory: String?,
    ): Session = selected().archiveSession(id, directory)

    override suspend fun deleteSession(
        id: String,
        directory: String?,
    ): Boolean = selected().deleteSession(id, directory)

    override suspend fun listChildSessions(
        id: String,
        directory: String?,
    ): List<Session> = selected().listChildSessions(id, directory)

    override suspend fun listSessionTodos(
        id: String,
        directory: String?,
    ): List<SessionTodo> = selected().listSessionTodos(id, directory)

    override suspend fun initializeSessionAgents(
        id: String,
        messageId: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ): Boolean = selected().initializeSessionAgents(id, messageId, providerId, modelId, directory)

    override suspend fun forkSession(
        id: String,
        atMessageId: String?,
        directory: String?,
    ): Session = selected().forkSession(id, atMessageId, directory)

    override suspend fun abortSession(
        id: String,
        directory: String?,
    ): Boolean = selected().abortSession(id, directory)

    override suspend fun shareSession(
        id: String,
        directory: String?,
    ): Session = selected().shareSession(id, directory)

    override suspend fun unshareSession(
        id: String,
        directory: String?,
    ): Session = selected().unshareSession(id, directory)

    override suspend fun getSessionDiff(
        id: String,
        messageId: String?,
        directory: String?,
    ): List<FileDiff> = selected().getSessionDiff(id, messageId, directory)

    override suspend fun getVcsInfo(directory: String?): VcsInfo = selected().getVcsInfo(directory)

    override suspend fun getVcsStatus(directory: String?): List<VcsFileStatus> = selected().getVcsStatus(directory)

    override suspend fun getVcsDiff(
        mode: VcsDiffMode,
        directory: String?,
        context: Int,
    ): List<FileDiff> = selected().getVcsDiff(mode, directory, context)

    override suspend fun summarizeSession(
        id: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ): Boolean = selected().summarizeSession(id, providerId, modelId, directory)

    override suspend fun compactSession(
        id: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ): CompactionStart = selected().compactSession(id, providerId, modelId, directory)

    override suspend fun revertSession(
        id: String,
        messageId: String,
        partId: String?,
        directory: String?,
    ): Boolean = selected().revertSession(id, messageId, partId, directory)

    override suspend fun unrevertSession(
        id: String,
        directory: String?,
    ): Boolean = selected().unrevertSession(id, directory)

    override suspend fun commitRevert(
        id: String,
        directory: String?,
    ): Boolean = selected().commitRevert(id, directory)

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        decision: PermissionDecision,
        directory: String?,
    ): Boolean = selected().respondToPermission(sessionId, permissionId, decision, directory)

    override suspend fun replyToQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Boolean = selected().replyToQuestion(requestId, answers, directory)

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
    ): Boolean = selected().replyToQuestion(sessionId, requestId, answers, questions, directory)

    override suspend fun rejectQuestion(
        requestId: String,
        directory: String?,
    ): Boolean = selected().rejectQuestion(requestId, directory)

    override suspend fun rejectQuestion(
        sessionId: String,
        requestId: String,
        directory: String?,
    ): Boolean = selected().rejectQuestion(sessionId, requestId, directory)

    override suspend fun cancelPendingPrompt(
        sessionId: String,
        inboxId: String,
        directory: String?,
    ): Boolean = selected().cancelPendingPrompt(sessionId, inboxId, directory)

    override suspend fun steerPendingPrompt(
        sessionId: String,
        inboxId: String,
        directory: String?,
    ): Boolean = selected().steerPendingPrompt(sessionId, inboxId, directory)

    override suspend fun queuePendingPrompt(
        sessionId: String,
        inboxId: String,
        directory: String?,
    ): Boolean = selected().queuePendingPrompt(sessionId, inboxId, directory)

    override suspend fun markSessionViewed(
        sessionId: String,
        idleAtEpochMs: Long,
    ) = selected().markSessionViewed(sessionId, idleAtEpochMs)
}
