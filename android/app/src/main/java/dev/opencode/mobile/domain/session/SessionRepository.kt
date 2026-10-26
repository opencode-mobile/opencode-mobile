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

    /**
     * Moves session [id] to the worktree [directory]. v2 only: callers gate on
     * `supportsSessionMove`; other servers throw.
     */
    suspend fun moveSession(
        id: String,
        directory: String,
    ): Unit = throw SessionError.Http(404, SESSION_MOVE_UNAVAILABLE)

    /**
     * Answers a `/btw` side question from the session's context and model without adding to the
     * conversation, and returns the trimmed markdown answer. v2 only: callers gate on
     * `supportsSideQuestion`; other servers throw.
     */
    suspend fun askSideQuestion(
        sessionId: String,
        question: String,
    ): String = throw SessionError.Http(404, SIDE_QUESTION_UNAVAILABLE)

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

    /**
     * Settles a pending revert before the next prompt. v2 only stages a revert and needs an
     * explicit commit; v1 applies the revert itself when the next prompt arrives, so it has
     * nothing to commit.
     */
    suspend fun commitRevert(
        id: String,
        directory: String? = null,
    ): Boolean = true

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

    /**
     * Acknowledges that the run which ended at [idleAtEpochMs] was seen. Only
     * servers with `supportsSessionViewed` track this; elsewhere it is a no-op.
     * Throws on transport or HTTP failure.
     */
    suspend fun markSessionViewed(
        sessionId: String,
        idleAtEpochMs: Long,
    ) = Unit
}

/** How the server handled a compact request. */
enum class CompactionStart {
    /** The server returned after the summary was written. */
    Finished,

    /** The server only enqueued compaction; it completes later, live. */
    Enqueued,
}

internal const val SESSION_MOVE_UNAVAILABLE = "session move is not available"
internal const val SIDE_QUESTION_UNAVAILABLE = "side questions need an opencode v2 server"
