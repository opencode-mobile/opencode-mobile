package dev.opencode.mobile.domain.session

import dev.opencode.mobile.data.remote.SessionsApi
import dev.opencode.mobile.domain.composer.ComposerDraftStore
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class DefaultSessionRepository
    @Inject
    constructor(
        api: SessionsApi,
        private val adapter: SessionDomainAdapter,
        private val sessionModelSelectionStore: SessionModelSelectionStore? = null,
        private val composerDraftStore: ComposerDraftStore? = null,
        private val activeConnectionManager: ActiveConnectionManager? = null,
    ) : SessionRepository {
        private val remote: SessionRemote =
            ProtocolSelectedSessionRemote(
                v1 = V1SessionRemote(api, adapter),
                v2 = V2SessionRemote(api, adapter),
                activeConnectionManager = activeConnectionManager,
            )

        override suspend fun listPendingPermissions(directory: String?): List<PermissionRequest> =
            mapped { remote.listPendingPermissions(directory) }

        override suspend fun listPendingQuestions(directory: String?): List<QuestionRequest> =
            mapped { remote.listPendingQuestions(directory) }

        override suspend fun listSessions(directory: String?): List<Session> =
            mapped { remote.listSessions(directory) }

        override suspend fun listRootSessionsWindow(
            directory: String?,
            limit: Int,
        ): SessionWindow =
            mapped { remote.listRootSessionsWindow(directory, limit) }

        override suspend fun searchSessions(
            query: String,
            directory: String?,
        ): List<Session> =
            mapped { remote.searchSessions(query, directory) }

        override suspend fun createSession(
            parentId: String?,
            title: String?,
            directory: String?,
        ): Session =
            mapped { remote.createSession(parentId, title, directory) }

        override suspend fun getAllSessionStatuses(directory: String?): List<SessionStatus> =
            mapped { remote.getAllSessionStatuses(directory) }

        override suspend fun getSession(
            id: String,
            directory: String?,
        ): Session =
            mapped { remote.getSession(id, directory) }

        override suspend fun updateSession(
            id: String,
            title: String?,
            directory: String?,
        ): Session =
            mapped { remote.updateSession(id, title, directory) }

        override suspend fun archiveSession(
            id: String,
            directory: String?,
        ): Session =
            mapped { remote.archiveSession(id, directory) }

        override suspend fun listArchivedSessions(directory: String?): List<Session> =
            mapped { remote.listArchivedSessions(directory) }

        override suspend fun unarchiveSession(
            id: String,
            directory: String?,
        ): Session =
            mapped { remote.unarchiveSession(id, directory) }

        override suspend fun deleteSession(
            id: String,
            directory: String?,
        ): Boolean {
            val deleted =
                mapped { remote.deleteSession(id, directory) }
            val profile = activeConnectionManager?.currentProfile()
            if (deleted && profile != null) {
                val identity = ModelSelectionConnectionIdentity(profile.baseUrl, profile.username)
                runCatching {
                    sessionModelSelectionStore?.deleteSessionEntries(identity, id)
                }.onFailure { DiagnosticLog.warning(DiagnosticLogArea.Session, "delete model selection failed", it) }
                runCatching {
                    composerDraftStore?.deleteSessionEntries(identity, id)
                }.onFailure { DiagnosticLog.warning(DiagnosticLogArea.Session, "delete composer draft failed", it) }
            }
            return deleted
        }

        override suspend fun listChildSessions(
            id: String,
            directory: String?,
        ): List<Session> =
            mapped { remote.listChildSessions(id, directory) }

        override suspend fun listSessionTodos(
            id: String,
            directory: String?,
        ): List<SessionTodo> =
            mapped { remote.listSessionTodos(id, directory) }

        override suspend fun initializeSessionAgents(
            id: String,
            messageId: String,
            providerId: String,
            modelId: String,
            directory: String?,
        ): Boolean =
            mapped { remote.initializeSessionAgents(id, messageId, providerId, modelId, directory) }

        override suspend fun forkSession(
            id: String,
            atMessageId: String?,
            directory: String?,
        ): Session =
            mapped { remote.forkSession(id, atMessageId, directory) }

        override suspend fun moveSession(
            id: String,
            directory: String,
        ) = mapped { remote.moveSession(id, directory) }

        override suspend fun askSideQuestion(
            sessionId: String,
            question: String,
        ): String =
            mapped { remote.askSideQuestion(sessionId, question) }

        override suspend fun abortSession(
            id: String,
            directory: String?,
        ): Boolean =
            mapped { remote.abortSession(id, directory) }

        override suspend fun shareSession(
            id: String,
            directory: String?,
        ): Session =
            mapped { remote.shareSession(id, directory) }

        override suspend fun unshareSession(
            id: String,
            directory: String?,
        ): Session =
            mapped { remote.unshareSession(id, directory) }

        override suspend fun getSessionDiff(
            id: String,
            messageId: String?,
            directory: String?,
        ): List<FileDiff> =
            mapped { remote.getSessionDiff(id, messageId, directory) }

        override suspend fun getVcsInfo(directory: String?): VcsInfo =
            mapped { remote.getVcsInfo(directory) }

        override suspend fun getVcsStatus(directory: String?): List<VcsFileStatus> =
            mapped { remote.getVcsStatus(directory) }

        override suspend fun getVcsDiff(
            mode: VcsDiffMode,
            directory: String?,
            context: Int,
        ): List<FileDiff> =
            mapped { remote.getVcsDiff(mode, directory, context) }

        override suspend fun summarizeSession(
            id: String,
            providerId: String,
            modelId: String,
            directory: String?,
        ): Boolean =
            mapped { remote.summarizeSession(id, providerId, modelId, directory) }

        override suspend fun compactSession(
            id: String,
            providerId: String,
            modelId: String,
            directory: String?,
        ): CompactionStart =
            mapped { remote.compactSession(id, providerId, modelId, directory) }

        override suspend fun revertSession(
            id: String,
            messageId: String,
            partId: String?,
            directory: String?,
        ): Boolean =
            mapped { remote.revertSession(id, messageId, partId, directory) }

        override suspend fun unrevertSession(
            id: String,
            directory: String?,
        ): Boolean =
            mapped { remote.unrevertSession(id, directory) }

        override suspend fun commitRevert(
            id: String,
            directory: String?,
        ): Boolean =
            mapped { remote.commitRevert(id, directory) }

        override suspend fun respondToPermission(
            sessionId: String,
            permissionId: String,
            decision: PermissionDecision,
            directory: String?,
        ): Boolean =
            mapped { remote.respondToPermission(sessionId, permissionId, decision, directory) }

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
            mapped { remote.replyToQuestion(sessionId, requestId, answers, questions, directory) }

        override suspend fun rejectQuestion(
            requestId: String,
            directory: String?,
        ): Boolean = rejectQuestion(sessionId = "", requestId = requestId, directory = directory)

        override suspend fun rejectQuestion(
            sessionId: String,
            requestId: String,
            directory: String?,
        ): Boolean =
            mapped { remote.rejectQuestion(sessionId, requestId, directory) }

        override suspend fun cancelPendingPrompt(
            sessionId: String,
            inboxId: String,
            directory: String?,
        ): Boolean =
            mapped { remote.cancelPendingPrompt(sessionId, inboxId, directory) }

        override suspend fun steerPendingPrompt(
            sessionId: String,
            inboxId: String,
            directory: String?,
        ): Boolean =
            mapped { remote.steerPendingPrompt(sessionId, inboxId, directory) }

        override suspend fun queuePendingPrompt(
            sessionId: String,
            inboxId: String,
            directory: String?,
        ): Boolean =
            mapped { remote.queuePendingPrompt(sessionId, inboxId, directory) }

        override suspend fun markSessionViewed(
            sessionId: String,
            idleAtEpochMs: Long,
        ) {
            mapped { remote.markSessionViewed(sessionId, idleAtEpochMs) }
        }

        /** Maps a failed call to a [SessionError]; a cancellation stays a cancellation. */
        private inline fun <T> mapped(call: () -> T): T =
            try {
                call()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                throw adapter.mapError(error)
            }
    }
