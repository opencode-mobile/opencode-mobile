package dev.opencode.mobile.domain.session

import android.util.Log
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
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.network.ActiveConnectionManager
import javax.inject.Inject

class DefaultSessionRepository
    @Inject
    constructor(
        private val api: SessionsApi,
        private val adapter: SessionDomainAdapter,
        private val sessionModelSelectionStore: SessionModelSelectionStore? = null,
        private val activeConnectionManager: ActiveConnectionManager? = null,
    ) : SessionRepository {
        private fun protocol(): ServerProtocol =
            activeConnectionManager?.currentProtocol() ?: ServerProtocol.V1

        private fun isV2(): Boolean = protocol() == ServerProtocol.V2

        override suspend fun listPendingPermissions(directory: String?): List<PermissionRequest> =
            runCatching { api.listPendingPermissions(directory).map(adapter::toPermissionRequest) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun listPendingQuestions(directory: String?): List<QuestionRequest> =
            runCatching { api.listPendingQuestions(directory).map(adapter::toQuestionRequest) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun listSessions(directory: String?): List<Session> =
            runCatching {
                if (isV2()) {
                    api.listSessionsV2(directory).map(adapter::toSession)
                } else {
                    api.listSessions(directory).map(adapter::toSession)
                }
            }.getOrElse { throw adapter.mapError(it) }

        override suspend fun createSession(
            parentId: String?,
            title: String?,
            directory: String?,
        ): Session =
            runCatching {
                if (isV2()) {
                    // V2 create is location-scoped; title/parent are applied via follow-up when needed.
                    var created = adapter.toSession(api.createSessionV2(directory))
                    if (!title.isNullOrBlank()) {
                        api.renameSessionV2(created.id, title)
                        created = created.copy(title = title)
                    }
                    created
                } else {
                    adapter.toSession(
                        api.createSession(CreateSessionRequestDto(parentId = parentId, title = title), directory),
                    )
                }
            }.getOrElse { throw adapter.mapError(it) }

        override suspend fun getAllSessionStatuses(directory: String?): List<SessionStatus> =
            runCatching {
                if (isV2()) {
                    adapter.toSessionStatusesFromActive(api.getActiveSessionsV2())
                } else {
                    api.getAllSessionStatuses(directory).map { (id, status) -> adapter.toSessionStatus(id, status) }
                }
            }.getOrElse { throw adapter.mapError(it) }

        override suspend fun getSession(
            id: String,
            directory: String?,
        ): Session =
            runCatching {
                if (isV2()) {
                    adapter.toSession(api.getSessionV2(id))
                } else {
                    adapter.toSession(api.getSession(id, directory))
                }
            }.getOrElse { throw adapter.mapError(it) }

        override suspend fun updateSession(
            id: String,
            title: String?,
            directory: String?,
        ): Session =
            runCatching {
                if (isV2()) {
                    require(!title.isNullOrBlank()) { "V2 rename requires a title" }
                    api.renameSessionV2(id, title)
                    adapter.toSession(api.getSessionV2(id)).let { session ->
                        if (session.title == title) session else session.copy(title = title)
                    }
                } else {
                    adapter.toSession(api.updateSession(id, UpdateSessionRequestDto(title = title), directory))
                }
            }.getOrElse { throw adapter.mapError(it) }

        override suspend fun archiveSession(
            id: String,
            directory: String?,
        ): Session =
            runCatching {
                if (isV2()) {
                    api.archiveSessionV2(id)
                    adapter.toSession(api.getSessionV2(id)).let { session ->
                        if (session.archivedAtEpochMs != null) {
                            session
                        } else {
                            session.copy(archivedAtEpochMs = System.currentTimeMillis())
                        }
                    }
                } else {
                    adapter.toSession(
                        api.updateSession(
                            id,
                            UpdateSessionRequestDto(
                                time = UpdateSessionTimeDto(archived = System.currentTimeMillis()),
                            ),
                            directory,
                        ),
                    )
                }
            }.getOrElse { throw adapter.mapError(it) }

        override suspend fun deleteSession(
            id: String,
            directory: String?,
        ): Boolean {
            val deleted =
                runCatching {
                    if (isV2()) {
                        api.deleteSessionV2(id)
                    } else {
                        api.deleteSession(id, directory)
                    }
                }.getOrElse { throw adapter.mapError(it) }
            val profile = activeConnectionManager?.currentProfile()
            if (deleted && profile != null) {
                runCatching {
                    sessionModelSelectionStore?.deleteSessionEntries(
                        ModelSelectionConnectionIdentity(profile.baseUrl, profile.username),
                        id,
                    )
                }.onFailure { Log.w("SessionRepository", "delete model selection failed", it) }
            }
            return deleted
        }

        override suspend fun listChildSessions(
            id: String,
            directory: String?,
        ): List<Session> =
            runCatching {
                if (isV2()) {
                    // No dedicated children route on published v2; filter the location list.
                    api.listSessionsV2(directory).filter { it.parentId == id }.map(adapter::toSession)
                } else {
                    api.listChildSessions(id, directory).map(adapter::toSession)
                }
            }.getOrElse { throw adapter.mapError(it) }

        override suspend fun listSessionTodos(
            id: String,
            directory: String?,
        ): List<SessionTodo> =
            runCatching { adapter.toTodos(api.listSessionTodos(id, directory)) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun initializeSessionAgents(
            id: String,
            messageId: String,
            providerId: String,
            modelId: String,
            directory: String?,
        ): Boolean =
            runCatching {
                api.initializeSessionAgents(
                    id,
                    InitSessionRequestDto(messageId = messageId, providerId = providerId, modelId = modelId),
                    directory,
                )
            }.getOrElse { throw adapter.mapError(it) }

        override suspend fun forkSession(
            id: String,
            atMessageId: String?,
            directory: String?,
        ): Session =
            runCatching {
                if (isV2()) {
                    adapter.toSession(api.forkSessionV2(id, atMessageId))
                } else {
                    adapter.toSession(api.forkSession(id, ForkSessionRequestDto(messageId = atMessageId), directory))
                }
            }.getOrElse { throw adapter.mapError(it) }

        override suspend fun abortSession(
            id: String,
            directory: String?,
        ): Boolean =
            runCatching { api.abortSession(id, directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun shareSession(
            id: String,
            directory: String?,
        ): Session =
            runCatching { adapter.toSession(api.shareSession(id, directory)) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun unshareSession(
            id: String,
            directory: String?,
        ): Session =
            runCatching { adapter.toSession(api.unshareSession(id, directory)) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun getSessionDiff(
            id: String,
            messageId: String?,
            directory: String?,
        ): List<FileDiff> =
            runCatching { api.getSessionDiff(id, messageId, directory).map(adapter::toFileDiff) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun getVcsInfo(directory: String?): VcsInfo =
            runCatching { adapter.toVcsInfo(api.getVcsInfo(directory)) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun getVcsDiff(
            mode: VcsDiffMode,
            directory: String?,
            context: Int,
        ): List<FileDiff> =
            runCatching { api.getVcsDiff(mode.wireValue, directory, context).map(adapter::toFileDiff) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun summarizeSession(
            id: String,
            providerId: String,
            modelId: String,
            directory: String?,
        ): Boolean =
            runCatching {
                api.summarizeSession(id, SummarizeSessionRequestDto(providerId = providerId, modelId = modelId), directory)
            }.getOrElse { throw adapter.mapError(it) }

        override suspend fun revertSession(
            id: String,
            messageId: String,
            partId: String?,
            directory: String?,
        ): Boolean =
            runCatching {
                api.revertSession(id, RevertSessionRequestDto(messageId = messageId, partId = partId), directory)
            }.getOrElse { throw adapter.mapError(it) }

        override suspend fun unrevertSession(
            id: String,
            directory: String?,
        ): Boolean =
            runCatching { api.unrevertSession(id, directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun respondToPermission(
            sessionId: String,
            permissionId: String,
            decision: PermissionDecision,
            directory: String?,
        ): Boolean =
            runCatching {
                api.respondToPermission(
                    sessionId,
                    permissionId,
                    PermissionResponseRequestDto(response = adapter.toPermissionResponse(decision)),
                    directory,
                )
            }.getOrElse { throw adapter.mapError(it) }

        override suspend fun replyToQuestion(
            requestId: String,
            answers: List<List<String>>,
            directory: String?,
        ): Boolean =
            runCatching {
                api.replyToQuestion(
                    requestId,
                    QuestionReplyRequestDto(answers = answers),
                    directory,
                )
            }.getOrElse { throw adapter.mapError(it) }

        override suspend fun rejectQuestion(
            requestId: String,
            directory: String?,
        ): Boolean =
            runCatching { api.rejectQuestion(requestId, directory) }
                .getOrElse { throw adapter.mapError(it) }
    }
