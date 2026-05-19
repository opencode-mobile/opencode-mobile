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
  override suspend fun listSessions(): List<Session> =
    runCatching { api.listSessions().map(adapter::toSession) }
      .getOrElse { throw adapter.mapError(it) }

  override suspend fun createSession(parentId: String?, title: String?): Session =
    runCatching {
      adapter.toSession(
        api.createSession(CreateSessionRequestDto(parentId = parentId, title = title)),
      )
    }.getOrElse { throw adapter.mapError(it) }

  override suspend fun getAllSessionStatuses(): List<SessionStatus> =
    runCatching {
      api.getAllSessionStatuses().map { (id, status) -> adapter.toSessionStatus(id, status) }
    }.getOrElse { throw adapter.mapError(it) }

  override suspend fun getSession(id: String): Session =
    runCatching { adapter.toSession(api.getSession(id)) }
      .getOrElse { throw adapter.mapError(it) }

  override suspend fun updateSession(id: String, title: String?): Session =
    runCatching {
      adapter.toSession(api.updateSession(id, UpdateSessionRequestDto(title = title)))
    }.getOrElse { throw adapter.mapError(it) }

  override suspend fun deleteSession(id: String): Boolean =
    runCatching { api.deleteSession(id) }
      .getOrElse { throw adapter.mapError(it) }

  override suspend fun listChildSessions(id: String): List<Session> =
    runCatching { api.listChildSessions(id).map(adapter::toSession) }
      .getOrElse { throw adapter.mapError(it) }

  override suspend fun listSessionTodos(id: String): List<SessionTodo> =
    runCatching { api.listSessionTodos(id).map(adapter::toTodo) }
      .getOrElse { throw adapter.mapError(it) }

  override suspend fun initializeSessionAgents(
    id: String,
    messageId: String,
    providerId: String,
    modelId: String,
  ): Boolean =
    runCatching {
      api.initializeSessionAgents(
        id,
        InitSessionRequestDto(messageId = messageId, providerId = providerId, modelId = modelId),
      )
    }.getOrElse { throw adapter.mapError(it) }

  override suspend fun forkSession(id: String, atMessageId: String?): Session =
    runCatching {
      adapter.toSession(api.forkSession(id, ForkSessionRequestDto(messageId = atMessageId)))
    }.getOrElse { throw adapter.mapError(it) }

  override suspend fun abortSession(id: String): Boolean =
    runCatching { api.abortSession(id) }
      .getOrElse { throw adapter.mapError(it) }

  override suspend fun shareSession(id: String): Session =
    runCatching { adapter.toSession(api.shareSession(id)) }
      .getOrElse { throw adapter.mapError(it) }

  override suspend fun unshareSession(id: String): Session =
    runCatching { adapter.toSession(api.unshareSession(id)) }
      .getOrElse { throw adapter.mapError(it) }

  override suspend fun getSessionDiff(id: String, messageId: String?): List<FileDiff> =
    runCatching { api.getSessionDiff(id, messageId).map(adapter::toFileDiff) }
      .getOrElse { throw adapter.mapError(it) }

  override suspend fun summarizeSession(id: String, providerId: String, modelId: String): Boolean =
    runCatching {
      api.summarizeSession(id, SummarizeSessionRequestDto(providerId = providerId, modelId = modelId))
    }.getOrElse { throw adapter.mapError(it) }

  override suspend fun revertSession(id: String, messageId: String, partId: String?): Boolean =
    runCatching {
      api.revertSession(id, RevertSessionRequestDto(messageId = messageId, partId = partId))
    }.getOrElse { throw adapter.mapError(it) }

  override suspend fun unrevertSession(id: String): Boolean =
    runCatching { api.unrevertSession(id) }
      .getOrElse { throw adapter.mapError(it) }

  override suspend fun respondToPermission(
    sessionId: String,
    permissionId: String,
    decision: PermissionDecision,
  ): Boolean =
    runCatching {
      api.respondToPermission(
        sessionId,
        permissionId,
        PermissionResponseRequestDto(response = adapter.toPermissionResponse(decision)),
      )
    }.getOrElse { throw adapter.mapError(it) }
}
