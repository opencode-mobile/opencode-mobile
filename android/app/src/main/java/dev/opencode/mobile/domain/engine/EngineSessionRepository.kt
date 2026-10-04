package dev.opencode.mobile.domain.engine

import dev.opencode.mobile.domain.connection.ConnectionEngine
import dev.opencode.mobile.domain.session.CompactionStart
import dev.opencode.mobile.domain.session.DefaultSessionRepository
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.PermissionDecision
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.QuestionInfo
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionCreateOptions
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
import dev.opencode.mobile.domain.session.SessionTodo
import dev.opencode.mobile.domain.session.VcsDiffMode
import dev.opencode.mobile.domain.session.VcsFileStatus
import dev.opencode.mobile.domain.session.VcsInfo
import dev.opencode.mobile.domain.t3.T3SessionRepository
import dev.opencode.mobile.network.ActiveConnectionManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EngineSessionRepository private constructor(
    private val opencode: SessionRepository,
    private val t3: SessionRepository,
    private val activeConnectionManager: ActiveConnectionManager,
) : SessionRepository {
    @Inject
    constructor(
        opencode: DefaultSessionRepository,
        t3: T3SessionRepository,
        activeConnectionManager: ActiveConnectionManager,
    ) : this(
        opencode as SessionRepository,
        t3 as SessionRepository,
        activeConnectionManager,
    )

    private fun delegate(): SessionRepository =
        if (activeConnectionManager.currentProfile()?.engine == ConnectionEngine.T3) t3 else opencode

    companion object {
        fun forTest(
            opencode: SessionRepository,
            t3: SessionRepository,
            activeConnectionManager: ActiveConnectionManager,
        ): EngineSessionRepository = EngineSessionRepository(opencode, t3, activeConnectionManager)
    }

        override suspend fun listPendingPermissions(directory: String?): List<PermissionRequest> =
            delegate().listPendingPermissions(directory)

        override suspend fun listPendingQuestions(directory: String?): List<QuestionRequest> =
            delegate().listPendingQuestions(directory)

        override suspend fun listSessions(directory: String?): List<Session> = delegate().listSessions(directory)

        override suspend fun createSession(
            parentId: String?,
            title: String?,
            directory: String?,
        ): Session = delegate().createSession(parentId, title, directory)

        override suspend fun createSession(
            parentId: String?,
            title: String?,
            directory: String?,
            options: SessionCreateOptions,
        ): Session = delegate().createSession(parentId, title, directory, options)

        override suspend fun getAllSessionStatuses(directory: String?): List<SessionStatus> =
            delegate().getAllSessionStatuses(directory)

        override suspend fun getSession(
            id: String,
            directory: String?,
        ): Session = delegate().getSession(id, directory)

        override suspend fun updateSession(
            id: String,
            title: String?,
            directory: String?,
        ): Session = delegate().updateSession(id, title, directory)

        override suspend fun archiveSession(
            id: String,
            directory: String?,
        ): Session = delegate().archiveSession(id, directory)

        override suspend fun deleteSession(
            id: String,
            directory: String?,
        ): Boolean = delegate().deleteSession(id, directory)

        override suspend fun listChildSessions(
            id: String,
            directory: String?,
        ): List<Session> = delegate().listChildSessions(id, directory)

        override suspend fun listSessionTodos(
            id: String,
            directory: String?,
        ): List<SessionTodo> = delegate().listSessionTodos(id, directory)

        override suspend fun initializeSessionAgents(
            id: String,
            messageId: String,
            providerId: String,
            modelId: String,
            directory: String?,
        ): Boolean = delegate().initializeSessionAgents(id, messageId, providerId, modelId, directory)

        override suspend fun forkSession(
            id: String,
            atMessageId: String?,
            directory: String?,
        ): Session = delegate().forkSession(id, atMessageId, directory)

        override suspend fun abortSession(
            id: String,
            directory: String?,
        ): Boolean = delegate().abortSession(id, directory)

        override suspend fun shareSession(
            id: String,
            directory: String?,
        ): Session = delegate().shareSession(id, directory)

        override suspend fun unshareSession(
            id: String,
            directory: String?,
        ): Session = delegate().unshareSession(id, directory)

        override suspend fun getSessionDiff(
            id: String,
            messageId: String?,
            directory: String?,
        ): List<FileDiff> = delegate().getSessionDiff(id, messageId, directory)

        override suspend fun getVcsInfo(directory: String?): VcsInfo = delegate().getVcsInfo(directory)

        override suspend fun getVcsDiff(
            mode: VcsDiffMode,
            directory: String?,
            context: Int,
        ): List<FileDiff> = delegate().getVcsDiff(mode, directory, context)

        override suspend fun getVcsStatus(directory: String?): List<VcsFileStatus> = delegate().getVcsStatus(directory)

        override suspend fun summarizeSession(
            id: String,
            providerId: String,
            modelId: String,
            directory: String?,
        ): Boolean = delegate().summarizeSession(id, providerId, modelId, directory)

        override suspend fun compactSession(
            id: String,
            providerId: String,
            modelId: String,
            directory: String?,
        ): CompactionStart = delegate().compactSession(id, providerId, modelId, directory)

        override suspend fun revertSession(
            id: String,
            messageId: String,
            partId: String?,
            directory: String?,
        ): Boolean = delegate().revertSession(id, messageId, partId, directory)

        override suspend fun unrevertSession(
            id: String,
            directory: String?,
        ): Boolean = delegate().unrevertSession(id, directory)

        override suspend fun respondToPermission(
            sessionId: String,
            permissionId: String,
            decision: PermissionDecision,
            directory: String?,
        ): Boolean = delegate().respondToPermission(sessionId, permissionId, decision, directory)

        override suspend fun replyToQuestion(
            requestId: String,
            answers: List<List<String>>,
            directory: String?,
        ): Boolean = delegate().replyToQuestion(requestId, answers, directory)

        override suspend fun replyToQuestion(
            sessionId: String,
            requestId: String,
            answers: List<List<String>>,
            directory: String?,
        ): Boolean = delegate().replyToQuestion(sessionId, requestId, answers, directory)

        override suspend fun replyToQuestion(
            sessionId: String,
            requestId: String,
            answers: List<List<String>>,
            questions: List<QuestionInfo>,
            directory: String?,
        ): Boolean = delegate().replyToQuestion(sessionId, requestId, answers, questions, directory)

        override suspend fun rejectQuestion(
            requestId: String,
            directory: String?,
        ): Boolean = delegate().rejectQuestion(requestId, directory)

        override suspend fun rejectQuestion(
            sessionId: String,
            requestId: String,
            directory: String?,
        ): Boolean = delegate().rejectQuestion(sessionId, requestId, directory)

        override suspend fun cancelPendingPrompt(
            sessionId: String,
            inboxId: String,
            directory: String?,
        ): Boolean = delegate().cancelPendingPrompt(sessionId, inboxId, directory)

        override suspend fun steerPendingPrompt(
            sessionId: String,
            inboxId: String,
            directory: String?,
        ): Boolean = delegate().steerPendingPrompt(sessionId, inboxId, directory)

        override suspend fun queuePendingPrompt(
            sessionId: String,
            inboxId: String,
            directory: String?,
        ): Boolean = delegate().queuePendingPrompt(sessionId, inboxId, directory)

        override suspend fun settleSession(
            id: String,
            directory: String?,
        ): Boolean = delegate().settleSession(id, directory)

        override suspend fun listArchivedSessions(directory: String?): List<Session> = delegate().listArchivedSessions(directory)

        override suspend fun unarchiveSession(
            id: String,
            directory: String?,
        ): Session = delegate().unarchiveSession(id, directory)

        override suspend fun unsettleSession(
            id: String,
            directory: String?,
        ): Boolean = delegate().unsettleSession(id, directory)

        override suspend fun setRuntimeMode(
            id: String,
            runtimeMode: String,
            directory: String?,
        ): Session = delegate().setRuntimeMode(id, runtimeMode, directory)

        override suspend fun setModelSelection(
            id: String,
            providerInstanceId: String,
            modelId: String,
            directory: String?,
        ): Session = delegate().setModelSelection(id, providerInstanceId, modelId, directory)
    }
