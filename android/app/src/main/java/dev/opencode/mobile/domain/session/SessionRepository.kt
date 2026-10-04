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

    /**
     * Creates a session with an explicit model and runtime mode. Engines without
     * creation-time choices ignore [options] and create as [createSession] does.
     */
    suspend fun createSession(
        parentId: String?,
        title: String?,
        directory: String?,
        options: SessionCreateOptions,
    ): Session = createSession(parentId, title, directory)

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

    /** Uncommitted working-copy changes for the project. 404 when the server has no VCS routes. */
    suspend fun getVcsStatus(directory: String? = null): List<VcsFileStatus> = throw SessionError.Http(404, "VCS endpoints are unavailable")

    suspend fun summarizeSession(
        id: String,
        providerId: String,
        modelId: String,
        directory: String? = null,
    ): Boolean

    /**
     * Requests compaction and says whether the summary already exists when this returns.
     * v1 `summarize` returns after compacting; v2 `compact` only enqueues it, and completion
     * arrives live as the compaction item reaching completed.
     */
    suspend fun compactSession(
        id: String,
        providerId: String,
        modelId: String,
        directory: String? = null,
    ): CompactionStart {
        summarizeSession(id, providerId, modelId, directory)
        return CompactionStart.Finished
    }

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

    suspend fun cancelPendingPrompt(
        sessionId: String,
        inboxId: String,
        directory: String? = null,
    ): Boolean = false

    suspend fun steerPendingPrompt(
        sessionId: String,
        inboxId: String,
        directory: String? = null,
    ): Boolean = false

    suspend fun queuePendingPrompt(
        sessionId: String,
        inboxId: String,
        directory: String? = null,
    ): Boolean = false

    suspend fun settleSession(
        id: String,
        directory: String? = null,
    ): Boolean = false

    /** Archived or settled sessions hidden from [listSessions]; engines without the concept return nothing. */
    suspend fun listArchivedSessions(directory: String? = null): List<Session> = emptyList()

    suspend fun unarchiveSession(
        id: String,
        directory: String? = null,
    ): Session = throw SessionError.Http(400, "Unarchive is not available")

    suspend fun unsettleSession(
        id: String,
        directory: String? = null,
    ): Boolean = false

    suspend fun setRuntimeMode(
        id: String,
        runtimeMode: String,
        directory: String? = null,
    ): Session = throw SessionError.Http(400, "Runtime mode is not available")

    suspend fun setModelSelection(
        id: String,
        providerInstanceId: String,
        modelId: String,
        directory: String? = null,
    ): Session = throw SessionError.Http(400, "Model selection is not available")
}

/** Creation-time choices for a new session; null fields fall back to the server's defaults. */
data class SessionCreateOptions(
    val providerId: String? = null,
    val modelId: String? = null,
    val runtimeMode: String? = null,
)

/** How the server handled a compact request. */
enum class CompactionStart {
    /** The server returned after the summary was written. */
    Finished,

    /** The server only enqueued compaction; it completes later, live. */
    Enqueued,
}
