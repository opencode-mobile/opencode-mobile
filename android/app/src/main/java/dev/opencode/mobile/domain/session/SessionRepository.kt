package dev.opencode.mobile.domain.session

interface SessionRepository {
  suspend fun listSessions(directory: String? = null): List<Session>

  suspend fun createSession(parentId: String? = null, title: String? = null, directory: String? = null): Session

  suspend fun getAllSessionStatuses(directory: String? = null): List<SessionStatus>

  suspend fun getSession(id: String, directory: String? = null): Session

  suspend fun updateSession(id: String, title: String? = null, directory: String? = null): Session

  suspend fun deleteSession(id: String, directory: String? = null): Boolean

  suspend fun listChildSessions(id: String, directory: String? = null): List<Session>

  suspend fun listSessionTodos(id: String, directory: String? = null): List<SessionTodo>

  suspend fun initializeSessionAgents(
    id: String,
    messageId: String,
    providerId: String,
    modelId: String,
    directory: String? = null,
  ): Boolean

  suspend fun forkSession(id: String, atMessageId: String? = null, directory: String? = null): Session

  suspend fun abortSession(id: String, directory: String? = null): Boolean

  suspend fun shareSession(id: String, directory: String? = null): Session

  suspend fun unshareSession(id: String, directory: String? = null): Session

  suspend fun getSessionDiff(id: String, messageId: String? = null, directory: String? = null): List<FileDiff>

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

  suspend fun unrevertSession(id: String, directory: String? = null): Boolean

  suspend fun respondToPermission(
    sessionId: String,
    permissionId: String,
    decision: PermissionDecision,
    directory: String? = null,
  ): Boolean
}
