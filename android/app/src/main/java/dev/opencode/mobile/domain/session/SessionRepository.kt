package dev.opencode.mobile.domain.session

interface SessionRepository {
    suspend fun listPendingPermissions(directory: String? = null): List<PermissionRequest> = emptyList()

    suspend fun listPendingQuestions(directory: String? = null): List<QuestionRequest> = emptyList()

    suspend fun listSessions(directory: String? = null): List<Session>

    suspend fun createSession(
        parentId: String? = null,
        title: String? = null,
        directory: String? = null,
    ): Session

    suspend fun getAllSessionStatuses(directory: String? = null): List<SessionStatus>

    suspend fun getSession(
        id: String,
        directory: String? = null,
    ): Session

    suspend fun updateSession(
        id: String,
        title: String? = null,
        directory: String? = null,
    ): Session

    suspend fun archiveSession(
        id: String,
        directory: String? = null,
    ): Session

    suspend fun deleteSession(
        id: String,
        directory: String? = null,
    ): Boolean

    suspend fun listChildSessions(
        id: String,
        directory: String? = null,
    ): List<Session>

    suspend fun listSessionTodos(
        id: String,
        directory: String? = null,
    ): List<SessionTodo>

    suspend fun initializeSessionAgents(
        id: String,
        messageId: String,
        providerId: String,
        modelId: String,
        directory: String? = null,
    ): Boolean

    suspend fun forkSession(
        id: String,
        atMessageId: String? = null,
        directory: String? = null,
    ): Session

    suspend fun abortSession(
        id: String,
        directory: String? = null,
    ): Boolean

    suspend fun shareSession(
        id: String,
        directory: String? = null,
    ): Session

    suspend fun unshareSession(
        id: String,
        directory: String? = null,
    ): Session

    suspend fun getSessionDiff(
        id: String,
        messageId: String? = null,
        directory: String? = null,
    ): List<FileDiff>

    suspend fun getVcsInfo(directory: String? = null): VcsInfo = throw SessionError.Http(404, "VCS endpoints are unavailable")

    suspend fun getVcsDiff(
        mode: VcsDiffMode,
        directory: String? = null,
        context: Int = 12,
    ): List<FileDiff> = throw SessionError.Http(404, "VCS endpoints are unavailable")

    suspend fun summarizeSession(
        id: String,
        providerId: String,
        modelId: String,
        directory: String? = null,
    ): Boolean

    suspend fun revertSession(
        id: String,
        messageId: String,
        partId: String? = null,
        directory: String? = null,
    ): Boolean

    suspend fun unrevertSession(
        id: String,
        directory: String? = null,
    ): Boolean

    suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        decision: PermissionDecision,
        directory: String? = null,
    ): Boolean

    suspend fun replyToQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String? = null,
    ): Boolean = false

    suspend fun replyToQuestion(
        sessionId: String,
        requestId: String,
        answers: List<List<String>>,
        directory: String? = null,
    ): Boolean = replyToQuestion(requestId, answers, directory)

    suspend fun replyToQuestion(
        sessionId: String,
        requestId: String,
        answers: List<List<String>>,
        questions: List<QuestionInfo>,
        directory: String? = null,
    ): Boolean = replyToQuestion(sessionId, requestId, answers, directory)

    suspend fun rejectQuestion(
        requestId: String,
        directory: String? = null,
    ): Boolean = false

    suspend fun rejectQuestion(
        sessionId: String,
        requestId: String,
        directory: String? = null,
    ): Boolean = rejectQuestion(requestId, directory)
}
