package dev.opencode.mobile.ui.sessionchat.region

import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.live.PermissionLiveStore
import dev.opencode.mobile.domain.live.QuestionLiveStore
import dev.opencode.mobile.domain.live.SessionTodoLiveStore
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.session.PermissionDecision
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.ui.sessionchat.InboxRequestPaging
import dev.opencode.mobile.ui.sessionchat.QuestionAnswerDraft
import dev.opencode.mobile.ui.sessionchat.SessionChatInboxRegionState
import dev.opencode.mobile.ui.sessionchat.buildQuestionAnswers
import dev.opencode.mobile.ui.sessionchat.dropOrphanQuestionRequests
import dev.opencode.mobile.ui.sessionchat.isQuestionAnswerValid
import dev.opencode.mobile.ui.sessionchat.orderedPermissionRequests
import dev.opencode.mobile.ui.sessionchat.orderedQuestionRequests
import dev.opencode.mobile.ui.sessionchat.syncedQuestionDrafts
import dev.opencode.mobile.ui.sessionchat.toggledQuestionSelection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SessionChatInbox(
    private val sessionRepository: SessionRepository,
    private val permissionLiveStore: PermissionLiveStore,
    private val questionLiveStore: QuestionLiveStore,
    private val sessionTodoLiveStore: SessionTodoLiveStore,
    private val scope: CoroutineScope,
    private val canMutate: () -> Boolean,
    private val loadedMessages: () -> List<Message>,
) {
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _state = MutableStateFlow(SessionChatInboxRegionState())
    val state: StateFlow<SessionChatInboxRegionState> = _state.asStateFlow()

    private var boundSessionId: String? = null
    private var boundWorktree: String? = null
    private var permissionObservationJob: Job? = null
    private var questionObservationJob: Job? = null
    private var todoObservationJob: Job? = null
    private var lastTodoLive: Boolean? = null

    fun bind(
        sessionId: String,
        worktree: String,
    ) {
        if (boundSessionId == sessionId && boundWorktree == worktree) {
            observe(sessionId, worktree)
            return
        }
        stop()
        boundSessionId = sessionId
        boundWorktree = worktree
        lastTodoLive = null
        _state.value = SessionChatInboxRegionState(rootSessionId = sessionId)
        observe(sessionId, worktree)
    }

    /**
     * The bound session moved to [worktree]: watches the new directory's permissions, questions
     * and todos. Requests on screen stay until the new directory's snapshot replaces them.
     */
    fun moveDirectory(worktree: String) {
        val sessionId = boundSessionId ?: return
        if (boundWorktree == worktree) return
        stop()
        boundWorktree = worktree
        // The next publish marks the new directory's todos live again.
        lastTodoLive = null
        observe(sessionId, worktree)
    }

    fun stop() {
        permissionObservationJob?.cancel()
        questionObservationJob?.cancel()
        todoObservationJob?.cancel()
        permissionObservationJob = null
        questionObservationJob = null
        todoObservationJob = null
        val sessionId = boundSessionId
        val worktree = boundWorktree
        if (sessionId != null && worktree != null) {
            sessionTodoLiveStore.setLive(false, sessionId, worktree)
        }
        lastTodoLive = false
    }

    fun setLive(
        isLive: Boolean,
        sessionId: String,
        worktree: String,
    ) {
        if (lastTodoLive == isLive) return
        lastTodoLive = isLive
        sessionTodoLiveStore.setLive(isLive, sessionId, worktree)
    }

    fun clearTopologyErrors() {
        _state.update {
            it.copy(permissionTopologyError = null, questionTopologyError = null)
        }
    }

    fun refilterOrphans() {
        val before = _state.value.questionRequests
        val filtered = dropOrphanQuestionRequests(before, loadedMessages())
        if (filtered == before) return
        _state.update { current ->
            val submittingCleared =
                current.questionSubmittingId != null &&
                    filtered.none { request -> request.id == current.questionSubmittingId }
            val next =
                current.copy(
                    questionRequests = filtered,
                    questionSubmittingId = if (submittingCleared) null else current.questionSubmittingId,
                    questionError = if (submittingCleared) null else current.questionError,
                )
            syncedQuestionDrafts(next)
        }
    }

    fun allowPermissionOnce(
        requestId: String,
        sessionId: String,
    ) = respondToPermission(requestId, sessionId, PermissionDecision.AllowOnce)

    fun denyPermission(
        requestId: String,
        sessionId: String,
    ) = respondToPermission(requestId, sessionId, PermissionDecision.Deny)

    fun alwaysAllowPermission(
        requestId: String,
        sessionId: String,
    ) = respondToPermission(requestId, sessionId, PermissionDecision.AlwaysAllow)

    fun hidePermissionDock(requestId: String) {
        if (_state.value.permissionRequests.none { it.id == requestId }) return
        _state.update { it.copy(permissionHiddenId = requestId) }
    }

    fun showPermissionDock() {
        _state.update { it.copy(permissionHiddenId = null) }
    }

    /** Pages the permission panel [delta] requests forward (negative goes back), clamped to the ends. */
    fun pagePermission(delta: Int) {
        val current = _state.value
        if (current.permissionSubmittingId != null) return
        val target =
            InboxRequestPaging.pagedId(current.permissionRequests.map { it.id }, current.permissionSelectedId, delta)
                ?: return
        if (target == current.visiblePermissionRequest?.id) return
        _state.update { it.copy(permissionSelectedId = target, permissionError = null) }
    }

    /** Pages the question panel [delta] requests forward; each request keeps its own answers. */
    fun pageQuestion(delta: Int) {
        val current = _state.value
        if (current.questionSubmittingId != null) return
        val target =
            InboxRequestPaging.pagedId(current.questionRequests.map { it.id }, current.questionSelectedId, delta)
                ?: return
        if (target == current.selectedQuestionRequest?.id) return
        _state.update { syncedQuestionDrafts(it.copy(questionSelectedId = target, questionError = null)) }
    }

    fun hideQuestionDock(requestId: String) {
        if (_state.value.visibleQuestionRequest?.id != requestId) return
        _state.update { it.copy(questionHiddenId = requestId) }
    }

    fun showQuestionDock() {
        _state.update { it.copy(questionHiddenId = null) }
    }

    fun toggleQuestionOption(
        requestId: String,
        sessionId: String,
        label: String,
    ) {
        val current = _state.value
        val request = current.visibleQuestionRequest ?: return
        if (request.id != requestId || request.sessionId != sessionId || current.questionSubmittingId != null) return
        val question = request.questions.getOrNull(current.questionWizardIndex) ?: return
        val draft = current.questionDraft
        val selected = toggledQuestionSelection(question, draft.selectedLabels, label)
        updateQuestionDraft(current.questionWizardIndex, draft.copy(selectedLabels = selected, customSelected = false))
    }

    fun toggleQuestionCustom(
        requestId: String,
        sessionId: String,
    ) {
        val current = _state.value
        val request = current.visibleQuestionRequest ?: return
        if (request.id != requestId || request.sessionId != sessionId || current.questionSubmittingId != null) return
        val question = request.questions.getOrNull(current.questionWizardIndex) ?: return
        if (!question.custom) return
        val draft = current.questionDraft
        val customSelected = !draft.customSelected
        updateQuestionDraft(
            current.questionWizardIndex,
            draft.copy(
                customSelected = customSelected,
                selectedLabels = if (customSelected) emptyList() else draft.selectedLabels,
            ),
        )
    }

    fun updateQuestionCustomText(
        requestId: String,
        sessionId: String,
        text: String,
    ) {
        val current = _state.value
        val request = current.visibleQuestionRequest ?: return
        if (request.id != requestId || request.sessionId != sessionId || current.questionSubmittingId != null) return
        updateQuestionDraft(
            current.questionWizardIndex,
            (current.questionDrafts[current.questionWizardIndex] ?: QuestionAnswerDraft()).copy(
                customText = text,
                customSelected = true,
                selectedLabels = emptyList(),
            ),
        )
    }

    fun questionBack(
        requestId: String,
        sessionId: String,
    ) {
        val current = _state.value
        val request = current.visibleQuestionRequest ?: return
        if (request.id != requestId || request.sessionId != sessionId || current.questionSubmittingId != null) return
        if (current.questionWizardIndex <= 0) return
        _state.update { it.copy(questionWizardIndex = it.questionWizardIndex - 1) }
    }

    fun questionNext(
        requestId: String,
        sessionId: String,
    ) {
        val current = _state.value
        val request = current.visibleQuestionRequest ?: return
        if (request.id != requestId || request.sessionId != sessionId || current.questionSubmittingId != null) return
        val question = request.questions.getOrNull(current.questionWizardIndex) ?: return
        if (!isQuestionAnswerValid(question, current.questionDraft)) return
        if (current.questionWizardIndex >= request.questions.lastIndex) return
        _state.update { it.copy(questionWizardIndex = it.questionWizardIndex + 1) }
    }

    fun submitQuestion(
        requestId: String,
        sessionId: String,
    ) {
        val current = _state.value
        val request = current.questionRequests.firstOrNull { it.id == requestId && it.sessionId == sessionId } ?: return
        if (current.questionSubmittingId != null) return
        if (!canMutate()) return
        val answers = buildQuestionAnswers(request.questions, current.questionDrafts) ?: return
        _state.update { it.copy(questionSubmittingId = request.id, questionError = null) }
        logI("question reply request=${request.id} session=${request.sessionId}")
        val worktree = boundWorktree
        scope.launch(ioDispatcher) {
            runCatching {
                sessionRepository.replyToQuestion(
                    request.sessionId,
                    request.id,
                    answers,
                    request.questions,
                    worktree,
                )
            }.onFailure { handleQuestionMutationFailure(request.id, it, dismiss = false) }
        }
    }

    fun dismissQuestion(
        requestId: String,
        sessionId: String,
    ) {
        val current = _state.value
        val request = current.questionRequests.firstOrNull { it.id == requestId && it.sessionId == sessionId } ?: return
        if (current.questionSubmittingId != null) return
        if (!canMutate()) return
        _state.update { it.copy(questionSubmittingId = request.id, questionError = null) }
        logI("question dismiss request=${request.id} session=${request.sessionId}")
        val worktree = boundWorktree
        scope.launch(ioDispatcher) {
            runCatching {
                sessionRepository.rejectQuestion(request.sessionId, request.id, worktree)
            }.onFailure { handleQuestionMutationFailure(request.id, it, dismiss = true) }
        }
    }

    private fun observe(
        sessionId: String,
        worktree: String,
    ) {
        permissionObservationJob?.cancel()
        permissionObservationJob =
            scope.launch(ioDispatcher) {
                permissionLiveStore.observePermissions(worktree).collect { requests ->
                    applyPermissionSnapshot(sessionId, worktree, requests)
                }
            }
        questionObservationJob?.cancel()
        questionObservationJob =
            scope.launch(ioDispatcher) {
                questionLiveStore.observeQuestions(worktree).collect { requests ->
                    applyQuestionSnapshot(sessionId, worktree, requests)
                }
            }
        todoObservationJob?.cancel()
        todoObservationJob =
            scope.launch(ioDispatcher) {
                sessionTodoLiveStore.observeTodos(sessionId, worktree).collect { todos ->
                    _state.update { it.copy(sessionTodos = todos) }
                }
            }
    }

    private fun respondToPermission(
        requestId: String,
        sessionId: String,
        decision: PermissionDecision,
    ) {
        if (!canMutate()) return
        val request =
            _state.value.permissionRequests.firstOrNull { it.id == requestId && it.sessionId == sessionId }
                ?: return
        if (_state.value.permissionSubmittingId != null) return
        _state.update { it.copy(permissionSubmittingId = request.id, permissionError = null) }
        val worktree = boundWorktree
        scope.launch(ioDispatcher) {
            runCatching {
                sessionRepository.respondToPermission(request.sessionId, request.id, decision, worktree)
            }.onFailure {
                if (it is SessionError.Http && it.statusCode == 404) {
                    val refreshed = refreshPermissionSnapshot()
                    if (refreshed && _state.value.permissionRequests.none { pending -> pending.id == request.id }) {
                        _state.update { state -> state.copy(permissionError = null) }
                        return@onFailure
                    }
                    _state.update { state ->
                        state.copy(
                            permissionSubmittingId = null,
                            permissionError = "Permission request is no longer available",
                        )
                    }
                } else {
                    logW("permission reply failed request=${request.id} decision=$decision", it)
                    _state.update { state ->
                        state.copy(
                            permissionSubmittingId = null,
                            permissionError = InteractionReplyError.PermissionReply.message(it),
                        )
                    }
                }
            }
        }
    }

    private suspend fun refreshPermissionSnapshot(): Boolean {
        val sessionId = boundSessionId ?: return false
        val worktree = boundWorktree ?: return false
        return runCatching { sessionRepository.listPendingPermissions(worktree) }
            .onSuccess { applyPermissionSnapshot(sessionId, worktree, it) }
            .isSuccess
    }

    private suspend fun applyPermissionSnapshot(
        sessionId: String,
        worktree: String,
        requests: List<PermissionRequest>,
    ) {
        val needsSessionTree =
            requests.any { request ->
                request.sessionId != sessionId &&
                    _state.value.permissionSessions.none { session -> session.id == request.sessionId }
            }
        var sessions = _state.value.permissionSessions
        val topologyError =
            if (needsSessionTree) {
                runCatching { sessionRepository.listSessions(worktree) }
                    .fold(
                        onSuccess = {
                            sessions = it
                            null
                        },
                        onFailure = {
                            sessions = emptyList()
                            "Unable to refresh permission session tree"
                        },
                    )
            } else {
                null
            }
        val ordered = orderedPermissionRequests(sessionId, sessions, requests)
        _state.update { current ->
            val submittingCleared =
                current.permissionSubmittingId != null &&
                    ordered.none { request -> request.id == current.permissionSubmittingId }
            current.copy(
                permissionRequests = ordered,
                permissionSessions = sessions,
                permissionTopologyError = topologyError,
                permissionSubmittingId = if (submittingCleared) null else current.permissionSubmittingId,
                permissionError = if (submittingCleared) null else current.permissionError,
            )
        }
    }

    private suspend fun refreshQuestionSnapshot(): Boolean {
        val sessionId = boundSessionId ?: return false
        val worktree = boundWorktree ?: return false
        return runCatching { sessionRepository.listPendingQuestions(worktree) }
            .onSuccess { applyQuestionSnapshot(sessionId, worktree, it) }
            .isSuccess
    }

    private suspend fun applyQuestionSnapshot(
        sessionId: String,
        worktree: String,
        requests: List<QuestionRequest>,
    ) {
        val needsSessionTree =
            requests.any { request ->
                request.sessionId != sessionId &&
                    _state.value.questionSessions.none { session -> session.id == request.sessionId }
            }
        var sessions = _state.value.questionSessions
        val topologyError =
            if (needsSessionTree) {
                runCatching { sessionRepository.listSessions(worktree) }
                    .fold(
                        onSuccess = {
                            sessions = it
                            null
                        },
                        onFailure = {
                            sessions = emptyList()
                            "Unable to refresh question session tree"
                        },
                    )
            } else {
                null
            }
        val ordered = orderedQuestionRequests(sessionId, sessions, requests)
        val filtered = dropOrphanQuestionRequests(ordered, loadedMessages())
        val previousVisibleId = _state.value.selectedQuestionRequest?.id
        _state.update { current ->
            val submittingCleared =
                current.questionSubmittingId != null &&
                    filtered.none { request -> request.id == current.questionSubmittingId }
            val next =
                current.copy(
                    questionRequests = filtered,
                    questionSessions = sessions,
                    questionTopologyError = topologyError,
                    questionSubmittingId = if (submittingCleared) null else current.questionSubmittingId,
                    questionError = if (submittingCleared) null else current.questionError,
                )
            syncedQuestionDrafts(next)
        }
        val visibleId = _state.value.selectedQuestionRequest?.id
        if (visibleId != previousVisibleId) {
            logI("question visible=${visibleId ?: "none"} queue=${filtered.size}")
        }
    }

    private fun handleQuestionMutationFailure(
        requestId: String,
        error: Throwable,
        dismiss: Boolean,
    ) {
        _state.update { current ->
            if (current.questionSubmittingId == requestId) {
                current.copy(questionSubmittingId = null)
            } else {
                current
            }
        }
        if (error is SessionError.Http && error.statusCode == 404) {
            scope.launch(ioDispatcher) {
                val refreshed = refreshQuestionSnapshot()
                if (refreshed && _state.value.questionRequests.none { pending -> pending.id == requestId }) {
                    _state.update { it.copy(questionError = null) }
                    return@launch
                }
                if (_state.value.visibleQuestionRequest?.id == requestId) {
                    _state.update { it.copy(questionError = "Question request is no longer available") }
                }
            }
            return
        }
        logW("question ${if (dismiss) "dismiss" else "reply"} failed request=$requestId", error)
        if (_state.value.visibleQuestionRequest?.id == requestId) {
            val failure = if (dismiss) InteractionReplyError.QuestionDismiss else InteractionReplyError.QuestionReply
            _state.update { current -> current.copy(questionError = failure.message(error)) }
        }
    }

    private fun updateQuestionDraft(
        index: Int,
        draft: QuestionAnswerDraft,
    ) {
        _state.update { current ->
            current.copy(questionDrafts = current.questionDrafts + (index to draft))
        }
    }


    private fun logI(message: String) {
        DiagnosticLog.info(DiagnosticLogArea.Chat, message)
    }

    private fun logW(
        message: String,
        error: Throwable,
    ) {
        val body = (error as? SessionError.Http)?.let { " status=${it.statusCode} body=${it.body.orEmpty().take(500)}" }
        DiagnosticLog.warning(DiagnosticLogArea.Chat, message + body.orEmpty(), error)
    }
}
