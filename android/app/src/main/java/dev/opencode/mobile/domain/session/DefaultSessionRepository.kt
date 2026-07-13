package dev.opencode.mobile.domain.session

import dev.opencode.mobile.data.remote.SessionsApi
import dev.opencode.mobile.data.remote.dto.CreateSessionRequestDto
import dev.opencode.mobile.data.remote.dto.ForkSessionRequestDto
import dev.opencode.mobile.data.remote.dto.InitSessionRequestDto
import dev.opencode.mobile.data.remote.dto.PermissionResponseRequestDto
import dev.opencode.mobile.data.remote.dto.RevertSessionRequestDto
import dev.opencode.mobile.data.remote.dto.SummarizeSessionRequestDto
import dev.opencode.mobile.data.remote.dto.UpdateSessionRequestDto
import javax.inject.Inject

class DefaultSessionRepository
    @Inject
    constructor(
        private val api: SessionsApi,
        private val adapter: SessionDomainAdapter,
    ) : SessionRepository {
        override suspend fun listPendingPermissions(directory: String?): List<PermissionRequest> =
            runCatching { api.listPendingPermissions(directory).map(adapter::toPermissionRequest) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun listSessions(directory: String?): List<Session> =
            runCatching { api.listSessions(directory).map(adapter::toSession) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun createSession(
            parentId: String?,
            title: String?,
            directory: String?,
        ): Session =
            runCatching {
                adapter.toSession(
                    api.createSession(CreateSessionRequestDto(parentId = parentId, title = title), directory),
                )
            }.getOrElse { throw adapter.mapError(it) }

        override suspend fun getAllSessionStatuses(directory: String?): List<SessionStatus> =
            runCatching {
                api.getAllSessionStatuses(directory).map { (id, status) -> adapter.toSessionStatus(id, status) }
            }.getOrElse { throw adapter.mapError(it) }

        override suspend fun getSession(
            id: String,
            directory: String?,
        ): Session =
            runCatching { adapter.toSession(api.getSession(id, directory)) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun updateSession(
            id: String,
            title: String?,
            directory: String?,
        ): Session =
            runCatching {
                adapter.toSession(api.updateSession(id, UpdateSessionRequestDto(title = title), directory))
            }.getOrElse { throw adapter.mapError(it) }

        override suspend fun deleteSession(
            id: String,
            directory: String?,
        ): Boolean =
            runCatching { api.deleteSession(id, directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun listChildSessions(
            id: String,
            directory: String?,
        ): List<Session> =
            runCatching { api.listChildSessions(id, directory).map(adapter::toSession) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun listSessionTodos(
            id: String,
            directory: String?,
        ): List<SessionTodo> =
            runCatching { api.listSessionTodos(id, directory).map(adapter::toTodo) }
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
                adapter.toSession(api.forkSession(id, ForkSessionRequestDto(messageId = atMessageId), directory))
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
    }
