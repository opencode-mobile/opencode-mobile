package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.session.PermissionDecision
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
import dev.opencode.mobile.domain.session.VcsInfo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class T3SessionRepository
    @Inject
    constructor(
        private val runtime: T3Runtime,
    ) : SessionRepository {
        override suspend fun listSessions(directory: String?): List<Session> {
            val snapshot = runtime.refreshShell()
            return snapshot.threads
                .filter(T3Activity::isActiveListRow)
                .filter { thread -> directory.isNullOrBlank() || matchesDirectory(thread, snapshot, directory) }
                .map { thread -> T3SessionMapper.toSession(thread, directory ?: thread.worktreePath) }
        }

        override suspend fun createSession(
            parentId: String?,
            title: String?,
            directory: String?,
        ): Session {
            val snapshot = runtime.refreshShell()
            val project =
                snapshot.projects.firstOrNull { project ->
                    directory.isNullOrBlank() ||
                        project.workspaceRoot == directory ||
                        snapshot.threads.any { it.projectId == project.id && it.worktreePath == directory }
                } ?: snapshot.projects.firstOrNull()
                    ?: throw SessionError.Http(400, "This T3 environment has no projects")
            val model =
                T3ModelSelection(
                    instanceId = project.defaultInstanceId.orEmpty(),
                    model = project.defaultModel,
                )
            if (model.instanceId.isBlank()) {
                throw SessionError.Http(400, "This T3 project has no model selection")
            }
            val threadId =
                runtime.launchThread(
                    T3Commands.launchThread(
                        projectId = project.id,
                        title = title ?: "New thread",
                        modelSelection = model,
                    ),
                )
            val thread = runtime.thread(threadId)
            return thread?.let { T3SessionMapper.toSession(it, directory ?: project.workspaceRoot) }
                ?: Session(id = threadId, title = title ?: "New thread", directory = directory ?: project.workspaceRoot)
        }

        override suspend fun getAllSessionStatuses(directory: String?): List<SessionStatus> {
            val snapshot = runtime.shell.value ?: runtime.refreshShell()
            return snapshot.threads
                .filter(T3Activity::isActiveListRow)
                .filter { thread -> directory.isNullOrBlank() || matchesDirectory(thread, snapshot, directory) }
                .map(T3SessionMapper::toStatus)
        }

        override suspend fun getSession(
            id: String,
            directory: String?,
        ): Session {
            val thread =
                runtime.thread(id)
                    ?: runtime.refreshShell().threads.firstOrNull { it.id == id }
                    ?: throw SessionError.Http(404, "Thread not found")
            return T3SessionMapper.toSession(thread, directory ?: thread.worktreePath)
        }

        override suspend fun updateSession(
            id: String,
            title: String?,
            directory: String?,
        ): Session {
            if (!title.isNullOrBlank()) {
                runtime.dispatch(T3Commands.threadMetadataUpdate(id, title))
            }
            return getSession(id, directory).let { session ->
                if (title.isNullOrBlank()) session else session.copy(title = title)
            }
        }

        override suspend fun archiveSession(
            id: String,
            directory: String?,
        ): Session {
            runtime.dispatch(T3Commands.threadArchive(id))
            return getSession(id, directory)
        }

        override suspend fun deleteSession(
            id: String,
            directory: String?,
        ): Boolean {
            runtime.dispatch(T3Commands.threadDelete(id))
            return true
        }

        override suspend fun abortSession(
            id: String,
            directory: String?,
        ): Boolean {
            val thread = runtime.thread(id) ?: runtime.refreshShell().threads.firstOrNull { it.id == id }
            val runId = thread?.activeRunId
            if (runId.isNullOrBlank()) return true
            runtime.dispatch(T3Commands.runInterrupt(id, runId))
            return true
        }

        override suspend fun settleSession(
            id: String,
            directory: String?,
        ): Boolean {
            runtime.dispatch(T3Commands.threadSettle(id))
            return true
        }

        override suspend fun listChildSessions(
            id: String,
            directory: String?,
        ): List<Session> = unsupported("Child sessions")

        override suspend fun listSessionTodos(
            id: String,
            directory: String?,
        ) = emptyList<dev.opencode.mobile.domain.session.SessionTodo>()

        override suspend fun initializeSessionAgents(
            id: String,
            messageId: String,
            providerId: String,
            modelId: String,
            directory: String?,
        ): Boolean = unsupported("Agents")

        override suspend fun forkSession(
            id: String,
            atMessageId: String?,
            directory: String?,
        ): Session = unsupported("Fork")

        override suspend fun shareSession(
            id: String,
            directory: String?,
        ): Session = unsupported("Share")

        override suspend fun unshareSession(
            id: String,
            directory: String?,
        ): Session = unsupported("Share")

        override suspend fun getSessionDiff(
            id: String,
            messageId: String?,
            directory: String?,
        ) = emptyList<dev.opencode.mobile.domain.session.FileDiff>()

        override suspend fun summarizeSession(
            id: String,
            providerId: String,
            modelId: String,
            directory: String?,
        ): Boolean = unsupported("Compact")

        override suspend fun revertSession(
            id: String,
            messageId: String,
            partId: String?,
            directory: String?,
        ): Boolean = unsupported("Undo")

        override suspend fun unrevertSession(
            id: String,
            directory: String?,
        ): Boolean = unsupported("Undo")

        override suspend fun listPendingPermissions(directory: String?): List<PermissionRequest> {
            val snapshot = runtime.refreshShell()
            return snapshot.threads
                .filter(T3Activity::isActiveListRow)
                .filter { thread -> directory.isNullOrBlank() || matchesDirectory(thread, snapshot, directory) }
                .mapNotNull(T3SessionMapper::toPermission)
        }

        override suspend fun respondToPermission(
            sessionId: String,
            permissionId: String,
            decision: PermissionDecision,
            directory: String?,
        ): Boolean {
            val thread =
                runtime.thread(sessionId)
                    ?: runtime.refreshShell().threads.firstOrNull { it.id == sessionId }
                    ?: return false
            if (T3SessionMapper.approvalId(thread) != permissionId) return false
            runtime.dispatch(T3Commands.runtimeRequestRespond(sessionId, permissionId, decision))
            runCatching { runtime.refreshShell() }
            return true
        }

        override suspend fun getVcsInfo(directory: String?): VcsInfo = VcsInfo(currentBranch = null, defaultBranch = null)

        private fun matchesDirectory(
            thread: T3Thread,
            snapshot: T3ShellSnapshot,
            directory: String,
        ): Boolean {
            if (thread.worktreePath == directory) return true
            val project = snapshot.projects.firstOrNull { it.id == thread.projectId }
            return project?.workspaceRoot == directory
        }

        private fun unsupported(label: String): Nothing = throw SessionError.Http(400, "$label is not available on T3")
    }
