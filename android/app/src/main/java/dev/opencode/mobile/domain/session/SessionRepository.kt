package dev.opencode.mobile.domain.session

interface SessionRepository {
  suspend fun listSessions(): List<Session>

  suspend fun createSession(parentId: String? = null, title: String? = null): Session

  suspend fun getAllSessionStatuses(): List<SessionStatus>

  suspend fun getSession(id: String): Session

  suspend fun updateSession(id: String, title: String? = null): Session

  suspend fun deleteSession(id: String): Boolean

  suspend fun listChildSessions(id: String): List<Session>

  suspend fun listSessionTodos(id: String): List<SessionTodo>

  suspend fun initializeSessionAgents(
    id: String,
    messageId: String,
    providerId: String,
    modelId: String,
  ): Boolean

  suspend fun forkSession(id: String, atMessageId: String? = null): Session

  suspend fun abortSession(id: String): Boolean

  suspend fun shareSession(id: String): Session

  suspend fun unshareSession(id: String): Session

  suspend fun getSessionDiff(id: String, messageId: String? = null): List<FileDiff>

  suspend fun summarizeSession(id: String, providerId: String, modelId: String): Boolean

  suspend fun revertSession(id: String, messageId: String, partId: String? = null): Boolean

  suspend fun unrevertSession(id: String): Boolean

  suspend fun respondToPermission(
    sessionId: String,
    permissionId: String,
    decision: PermissionDecision,
  ): Boolean
}
