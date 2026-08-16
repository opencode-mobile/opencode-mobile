package dev.opencode.mobile.domain.session

import android.util.Log
import dev.opencode.mobile.data.remote.SessionsApi
import dev.opencode.mobile.domain.composer.ComposerDraftStore
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.network.ActiveConnectionManager
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
            runCatching { remote.listPendingPermissions(directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun listPendingQuestions(directory: String?): List<QuestionRequest> =
            runCatching { remote.listPendingQuestions(directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun listSessions(directory: String?): List<Session> =
            runCatching { remote.listSessions(directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun createSession(
            parentId: String?,
            title: String?,
            directory: String?,
        ): Session =
            runCatching { remote.createSession(parentId, title, directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun getAllSessionStatuses(directory: String?): List<SessionStatus> =
            runCatching { remote.getAllSessionStatuses(directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun getSession(
            id: String,
            directory: String?,
        ): Session =
            runCatching { remote.getSession(id, directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun updateSession(
            id: String,
            title: String?,
            directory: String?,
        ): Session =
            runCatching { remote.updateSession(id, title, directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun archiveSession(
            id: String,
            directory: String?,
        ): Session =
            runCatching { remote.archiveSession(id, directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun deleteSession(
            id: String,
            directory: String?,
        ): Boolean {
            val deleted =
                runCatching { remote.deleteSession(id, directory) }
                    .getOrElse { throw adapter.mapError(it) }
            val profile = activeConnectionManager?.currentProfile()
            if (deleted && profile != null) {
                val identity = ModelSelectionConnectionIdentity(profile.baseUrl, profile.username)
                runCatching {
                    sessionModelSelectionStore?.deleteSessionEntries(identity, id)
                }.onFailure { Log.w("SessionRepository", "delete model selection failed", it) }
                runCatching {
                    composerDraftStore?.deleteSessionEntries(identity, id)
                }.onFailure { Log.w("SessionRepository", "delete composer draft failed", it) }
            }
            return deleted
        }

        override suspend fun listChildSessions(
            id: String,
            directory: String?,
        ): List<Session> =
            runCatching { remote.listChildSessions(id, directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun listSessionTodos(
            id: String,
            directory: String?,
        ): List<SessionTodo> =
            runCatching { remote.listSessionTodos(id, directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun initializeSessionAgents(
            id: String,
            messageId: String,
            providerId: String,
            modelId: String,
            directory: String?,
        ): Boolean =
            runCatching { remote.initializeSessionAgents(id, messageId, providerId, modelId, directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun forkSession(
            id: String,
            atMessageId: String?,
            directory: String?,
        ): Session =
            runCatching { remote.forkSession(id, atMessageId, directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun abortSession(
            id: String,
            directory: String?,
        ): Boolean =
            runCatching { remote.abortSession(id, directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun shareSession(
            id: String,
            directory: String?,
        ): Session =
            runCatching { remote.shareSession(id, directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun unshareSession(
            id: String,
            directory: String?,
        ): Session =
            runCatching { remote.unshareSession(id, directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun getSessionDiff(
            id: String,
            messageId: String?,
            directory: String?,
        ): List<FileDiff> =
            runCatching { remote.getSessionDiff(id, messageId, directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun getVcsInfo(directory: String?): VcsInfo =
            runCatching { remote.getVcsInfo(directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun getVcsDiff(
            mode: VcsDiffMode,
            directory: String?,
            context: Int,
        ): List<FileDiff> =
            runCatching { remote.getVcsDiff(mode, directory, context) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun summarizeSession(
            id: String,
            providerId: String,
            modelId: String,
            directory: String?,
        ): Boolean =
            runCatching { remote.summarizeSession(id, providerId, modelId, directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun revertSession(
            id: String,
            messageId: String,
            partId: String?,
            directory: String?,
        ): Boolean =
            runCatching { remote.revertSession(id, messageId, partId, directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun unrevertSession(
            id: String,
            directory: String?,
        ): Boolean =
            runCatching { remote.unrevertSession(id, directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun respondToPermission(
            sessionId: String,
            permissionId: String,
            decision: PermissionDecision,
            directory: String?,
        ): Boolean =
            runCatching { remote.respondToPermission(sessionId, permissionId, decision, directory) }
                .getOrElse { throw adapter.mapError(it) }

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
        ): Boolean =
            runCatching { remote.replyToQuestion(sessionId, requestId, answers, directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun rejectQuestion(
            requestId: String,
            directory: String?,
        ): Boolean = rejectQuestion(sessionId = "", requestId = requestId, directory = directory)

        override suspend fun rejectQuestion(
            sessionId: String,
            requestId: String,
            directory: String?,
        ): Boolean =
            runCatching { remote.rejectQuestion(sessionId, requestId, directory) }
                .getOrElse { throw adapter.mapError(it) }
    }
