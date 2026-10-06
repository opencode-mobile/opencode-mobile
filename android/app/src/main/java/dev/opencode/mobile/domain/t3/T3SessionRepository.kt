package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.session.PermissionDecision
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.QuestionInfo
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionCreateOptions
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
        private val api: T3Api,
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
        ): Session = createSession(parentId, title, directory, SessionCreateOptions())

        /**
         * Launches a thread in the project owning [directory]. The model is the explicit
         * [options] choice when its harness is usable, else the project override, the
         * global default, and the first usable harness (see [T3ModelSelectionResolver]).
         */
        override suspend fun createSession(
            parentId: String?,
            title: String?,
            directory: String?,
            options: SessionCreateOptions,
        ): Session {
            val snapshot = runtime.refreshShell()
            val project =
                snapshot.projects.firstOrNull { project ->
                    directory.isNullOrBlank() ||
                        project.workspaceRoot == directory ||
                        snapshot.threads.any { it.projectId == project.id && it.worktreePath == directory }
                } ?: snapshot.projects.firstOrNull()
                    ?: throw SessionError.Http(400, "This T3 environment has no projects")
            val projectDefault =
                project.defaultInstanceId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { T3ModelSelection(instanceId = it, model = project.defaultModel) }
            val explicit =
                options.providerId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { T3ModelSelection(instanceId = it, model = options.modelId?.takeIf { model -> model.isNotBlank() }) }
            val config = runCatching { runtime.serverConfig() }.getOrNull()
            val model =
                if (config == null) {
                    // An older server without a catalogue: trust the explicit choice, then the project.
                    explicit ?: projectDefault
                } else {
                    T3ModelSelectionResolver.resolveExplicit(
                        harnesses = config.harnesses,
                        explicit = explicit,
                        projectDefault = projectDefault,
                        globalDefault = config.defaultModelSelection,
                    )
                } ?: throw SessionError.Http(400, T3ModelSelectionResolver.NO_HARNESS_MESSAGE)
            val runtimeMode = launchRuntimeMode(options.runtimeMode, config?.harness(model.instanceId))
            val threadId =
                runtime.launchThread(
                    T3Commands.launchThread(
                        projectId = project.id,
                        title = title ?: "New thread",
                        modelSelection = model,
                        runtimeMode = runtimeMode,
                    ),
                )
            val thread = runtime.thread(threadId)
            return thread?.let { T3SessionMapper.toSession(it, directory ?: project.workspaceRoot) }
                ?: Session(
                    id = threadId,
                    title = title ?: "New thread",
                    directory = directory ?: project.workspaceRoot,
                    providerInstanceId = model.instanceId,
                    modelId = model.model,
                    runtimeMode = runtimeMode,
                )
        }

        /** The requested mode when the harness accepts it, else supervised, else the harness's first mode. */
        private fun launchRuntimeMode(
            requested: String?,
            harness: T3Harness?,
        ): String {
            val allowed = harness?.runtimeModes ?: T3RuntimeModeLabels.allModes
            return requested?.takeIf { it in allowed }
                ?: T3Commands.DEFAULT_RUNTIME_MODE.takeIf { it in allowed }
                ?: allowed.first()
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
            val thread = findThread(id) ?: throw SessionError.Http(404, "Thread not found")
            return T3SessionMapper.toSession(thread, directory ?: thread.worktreePath)
        }

        override suspend fun listArchivedSessions(directory: String?): List<Session> {
            val snapshot = runtime.refreshShell()
            return snapshot.allThreads
                .filter(T3Activity::isArchivedListRow)
                .filter { thread -> directory.isNullOrBlank() || matchesDirectory(thread, snapshot, directory) }
                .map { thread -> T3SessionMapper.toSession(thread, directory ?: thread.worktreePath) }
        }

        override suspend fun unarchiveSession(
            id: String,
            directory: String?,
        ): Session {
            runtime.dispatch(T3Commands.threadUnarchive(id))
            val session = refreshedSession(id, directory)
            return session.copy(archivedAtEpochMs = null)
        }

        override suspend fun unsettleSession(
            id: String,
            directory: String?,
        ): Boolean {
            runtime.dispatch(T3Commands.threadUnsettle(id))
            runCatching { runtime.refreshShell() }
            return true
        }

        override suspend fun setRuntimeMode(
            id: String,
            runtimeMode: String,
            directory: String?,
        ): Session {
            if (runtimeMode !in T3RuntimeModeLabels.allModes) {
                throw SessionError.Http(400, "Unknown runtime mode: $runtimeMode")
            }
            runtime.dispatch(T3Commands.threadRuntimeModeSet(id, runtimeMode))
            return refreshedSession(id, directory).copy(runtimeMode = runtimeMode)
        }

        override suspend fun setModelSelection(
            id: String,
            providerInstanceId: String,
            modelId: String,
            directory: String?,
        ): Session {
            if (providerInstanceId.isBlank() || modelId.isBlank()) {
                throw SessionError.Http(400, "A provider instance and model are required")
            }
            val selection = T3ModelSelection(instanceId = providerInstanceId, model = modelId)
            runtime.dispatch(T3Commands.threadModelSelectionSet(id, selection))
            return refreshedSession(id, directory).copy(providerInstanceId = providerInstanceId, modelId = modelId)
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
            val thread = findThread(id)
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
            runCatching { runtime.refreshShell() }
            return true
        }

        override suspend fun snoozeSession(
            id: String,
            snoozedUntil: String,
            directory: String?,
        ): Boolean {
            runtime.dispatch(T3Commands.threadSnooze(id, snoozedUntil))
            runCatching { runtime.refreshShell() }
            return true
        }

        override suspend fun wakeSession(
            id: String,
            directory: String?,
        ): Boolean {
            runtime.dispatch(T3Commands.threadUnsnooze(id))
            runCatching { runtime.refreshShell() }
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
                .filter { thread -> T3SessionMapper.approvalId(thread) != null }
                .mapNotNull { thread -> T3SessionMapper.toPermission(thread, turnItems(thread.id)) }
        }

        override suspend fun listPendingQuestions(directory: String?): List<QuestionRequest> {
            val snapshot = runtime.refreshShell()
            return snapshot.threads
                .filter(T3Activity::isActiveListRow)
                .filter { thread -> directory.isNullOrBlank() || matchesDirectory(thread, snapshot, directory) }
                .filter { thread -> T3SessionMapper.questionId(thread) != null }
                .mapNotNull { thread -> T3SessionMapper.toQuestionRequest(thread, turnItems(thread.id)) }
        }

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
        ): Boolean {
            val thread = questionThread(sessionId, requestId) ?: return false
            val known =
                questions.takeIf { list -> list.isNotEmpty() && list.all { !it.fieldKey.isNullOrBlank() } }
                    ?: T3SessionMapper.toQuestionRequest(thread, turnItems(thread.id))?.questions
                    ?: return false
            val encoded = T3QuestionCommands.encodeAnswers(known, answers)
            if (encoded.isEmpty()) return false
            runtime.dispatch(T3QuestionCommands.userInputRespond(thread.id, requestId, encoded))
            runCatching { runtime.refreshShell() }
            return true
        }

        override suspend fun rejectQuestion(
            requestId: String,
            directory: String?,
        ): Boolean = rejectQuestion(sessionId = "", requestId = requestId, directory = directory)

        override suspend fun rejectQuestion(
            sessionId: String,
            requestId: String,
            directory: String?,
        ): Boolean {
            val thread = questionThread(sessionId, requestId) ?: return false
            runtime.dispatch(T3QuestionCommands.userInputDismiss(thread.id, requestId))
            runCatching { runtime.refreshShell() }
            return true
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

        /** The thread by id from the cached shell, including archived ones, else after a refresh. */
        private suspend fun findThread(id: String): T3Thread? =
            runtime.thread(id)
                ?: runtime.shell.value?.archivedThreads?.firstOrNull { it.id == id }
                ?: runtime.refreshShell().thread(id)

        /** The session after a command: refreshed from the shell, else from the cached thread. */
        private suspend fun refreshedSession(
            id: String,
            directory: String?,
        ): Session {
            val thread =
                runCatching { runtime.refreshShell().thread(id) }.getOrNull()
                    ?: findThread(id)
                    ?: throw SessionError.Http(404, "Thread not found")
            return T3SessionMapper.toSession(thread, directory ?: thread.worktreePath)
        }

        /** The thread waiting on [requestId]; found by id, else by its pending question. */
        private suspend fun questionThread(
            sessionId: String,
            requestId: String,
        ): T3Thread? {
            val snapshot = runtime.refreshShell()
            val thread =
                if (sessionId.isNotBlank()) {
                    snapshot.threads.firstOrNull { it.id == sessionId }
                } else {
                    snapshot.threads.firstOrNull { T3SessionMapper.questionId(it) == requestId }
                }
            return thread?.takeIf { T3SessionMapper.questionId(it) == requestId }
        }

        /** The thread projection's turn items; a failed fetch degrades to an empty list. */
        private suspend fun turnItems(threadId: String): List<T3TurnItem> {
            val profile = runtime.activeProfile() ?: return emptyList()
            return runCatching { api.fetchThread(profile, threadId).turnItems }.getOrDefault(emptyList())
        }

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
