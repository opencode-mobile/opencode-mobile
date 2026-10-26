package dev.opencode.mobile.ui.sessionchat.region

import dev.opencode.mobile.domain.session.SideQuestion
import dev.opencode.mobile.domain.session.SideQuestionEntry
import dev.opencode.mobile.domain.session.SideQuestionEvent
import dev.opencode.mobile.domain.session.SideQuestionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object SessionChatSideQuestionsCopy {
    const val UNSUPPORTED =
        "/btw needs an opencode v2 server that answers side questions. This server can't answer without adding to the conversation."
    const val UNAVAILABLE = "/btw needs a newer opencode server."
    const val COPIED = "Answer copied"

    fun failed(question: String): String = "/btw failed: “$question”"
}

/** One `/btw` answer shown in the answer sheet. */
data class SessionChatSideQuestionAnswer(
    val id: String,
    val question: String,
    val answer: String,
)

data class SessionChatSideQuestionsUiState(
    /** The server can answer `/btw`; gates the Btw tile and `/btw` parsing. */
    val supported: Boolean = false,
    /** The composer asks a side question on Send (the `/btw` pill). */
    val composerMode: Boolean = false,
    val pendingCount: Int = 0,
    /** Answers waiting behind the "answer ready" chip. */
    val readyCount: Int = 0,
    val failedCount: Int = 0,
    /** The answer sheet's content; null while it is closed. */
    val presentedAnswer: SessionChatSideQuestionAnswer? = null,
    /** Shown above the composer when the draft is a `/btw` this server cannot answer. */
    val unsupportedNotice: String? = null,
) {
    val hasChips: Boolean
        get() = pendingCount > 0 || readyCount > 0 || failedCount > 0
}

/** A one-shot snackbar from a `/btw` request. */
data class SessionChatSideQuestionNotice(
    val id: Long,
    val message: String,
    val isError: Boolean,
    /** The failed question Retry asks again; null when asking again cannot work. */
    val retryId: String?,
)

data class SessionChatSideQuestionsRegionState(
    val ui: SessionChatSideQuestionsUiState = SessionChatSideQuestionsUiState(),
    val notice: SessionChatSideQuestionNotice? = null,
)

/**
 * `/btw` side questions in Session Chat. Requests and unread answers live in the connection's
 * [SideQuestionStore], so they outlive this screen; this region only decides what the screen
 * shows. An answer that arrives while the screen is up opens the answer sheet (or waits behind
 * the one already open); answers that arrived while away wait as a chip.
 */
class SessionChatSideQuestions(
    private val store: SideQuestionStore,
    private val supported: () -> Boolean,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SessionChatSideQuestionsRegionState())
    val state: StateFlow<SessionChatSideQuestionsRegionState> = _state.asStateFlow()

    /** False while another sheet or dialog is up, so a new answer waits instead of stacking. */
    var canPresentAnswer: () -> Boolean = { true }

    private var sessionId: String? = null
    private var entriesJob: Job? = null
    private var eventsJob: Job? = null
    private var presentedId: String? = null

    /** Answers that arrived while a sheet was open, oldest first. */
    private val queuedIds = mutableListOf<String>()
    private var noticeSequence = 0L

    /** Shows [sessionId]; a different session drops what this screen showed for the last one. */
    fun bind(sessionId: String) {
        if (this.sessionId != sessionId) {
            stop()
            this.sessionId = sessionId
        }
        start()
    }

    fun start() {
        val sessionId = sessionId ?: return
        if (entriesJob?.isActive != true) {
            entriesJob = scope.launch { store.observe(sessionId).collect { refresh() } }
        }
        if (eventsJob?.isActive != true) {
            eventsJob = scope.launch { store.events.collect { handle(it) } }
        }
        refresh()
    }

    /**
     * Leaving Session Chat: an answer on screen was read, so it is forgotten; requests and unread
     * answers stay in the store.
     */
    fun stop() {
        entriesJob?.cancel()
        eventsJob?.cancel()
        entriesJob = null
        eventsJob = null
        queuedIds.clear()
        presentedId?.let {
            presentedId = null
            store.remove(it)
        }
        _state.update { it.copy(ui = it.ui.copy(composerMode = false), notice = null) }
        refresh()
    }

    fun enterMode() {
        if (!supported() || _state.value.ui.composerMode) return
        _state.update { it.copy(ui = it.ui.copy(composerMode = true)) }
    }

    fun exitMode() {
        if (!_state.value.ui.composerMode) return
        _state.update { it.copy(ui = it.ui.copy(composerMode = false)) }
    }

    /** Sends one `generate` request and leaves side-question mode. False when nothing was asked. */
    fun ask(question: String): Boolean {
        val sessionId = sessionId ?: return false
        if (!supported() || store.ask(sessionId, question) == null) return false
        _state.update { it.copy(ui = it.ui.copy(composerMode = false)) }
        refresh()
        return true
    }

    /** The "answer ready" chip: opens the oldest unread answer. */
    fun openReadyAnswer() {
        val sessionId = sessionId ?: return
        if (presentedId != null) return
        val entry = store.entries(sessionId).firstOrNull { it.answer != null } ?: return
        queuedIds.remove(entry.id)
        present(entry.id)
    }

    /** Closing the sheet forgets its answer, then opens the next one that arrived meanwhile. */
    fun closeAnswer() {
        val id = presentedId ?: return
        presentedId = null
        store.remove(id)
        while (queuedIds.isNotEmpty()) {
            val next = queuedIds.removeAt(0)
            if (store.entry(next)?.answer != null) {
                present(next)
                return
            }
        }
        refresh()
    }

    fun answerCopied() {
        post(SessionChatSideQuestionNotice(nextNoticeId(), SessionChatSideQuestionsCopy.COPIED, isError = false, retryId = null))
    }

    fun retry(id: String) {
        _state.update { if (it.notice?.retryId == id) it.copy(notice = null) else it }
        store.retry(id)
    }

    /** The failed chip: asks every failed question again. */
    fun retryFailed() {
        _state.update { it.copy(notice = null) }
        failedIds().forEach(store::retry)
    }

    fun dismissFailed() {
        _state.update { it.copy(notice = null) }
        failedIds().forEach(store::remove)
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    /** The composer draft changed: say when a typed `/btw` cannot be asked here. */
    fun updateDraft(text: String) {
        val notice =
            SessionChatSideQuestionsCopy.UNSUPPORTED.takeIf { !supported() && SideQuestion.questionInDraft(text) != null }
        if (notice == _state.value.ui.unsupportedNotice) return
        _state.update { it.copy(ui = it.ui.copy(unsupportedNotice = notice)) }
    }

    private fun handle(event: SideQuestionEvent) {
        if (event.sessionId != sessionId) return
        when (event) {
            is SideQuestionEvent.Answered ->
                if (presentedId == null && canPresentAnswer()) present(event.id) else queuedIds += event.id
            is SideQuestionEvent.Failed ->
                post(
                    SessionChatSideQuestionNotice(
                        id = nextNoticeId(),
                        message =
                            if (event.retryable) {
                                SessionChatSideQuestionsCopy.failed(event.question)
                            } else {
                                SessionChatSideQuestionsCopy.UNAVAILABLE
                            },
                        isError = true,
                        retryId = event.id.takeIf { event.retryable },
                    ),
                )
        }
    }

    private fun failedIds(): List<String> {
        val sessionId = sessionId ?: return emptyList()
        return store.entries(sessionId).filter { it.status == SideQuestionEntry.Status.Failed }.map { it.id }
    }

    private fun present(id: String) {
        presentedId = id
        refresh()
    }

    private fun post(notice: SessionChatSideQuestionNotice) {
        _state.update { it.copy(notice = notice) }
    }

    private fun nextNoticeId(): Long {
        noticeSequence += 1
        return noticeSequence
    }

    private fun refresh() {
        val entries = sessionId?.let(store::entries).orEmpty()
        if (presentedId != null && entries.firstOrNull { it.id == presentedId }?.answer == null) {
            presentedId = null
        }
        val presented = presentedId
        val isSupported = supported()
        _state.update { current ->
            current.copy(
                ui =
                    current.ui.copy(
                        supported = isSupported,
                        composerMode = current.ui.composerMode && isSupported,
                        pendingCount = entries.count { it.status == SideQuestionEntry.Status.Pending },
                        readyCount = entries.count { it.answer != null && it.id != presented },
                        failedCount = entries.count { it.status == SideQuestionEntry.Status.Failed },
                        presentedAnswer =
                            entries.firstOrNull { it.id == presented }?.let { entry ->
                                entry.answer?.let { SessionChatSideQuestionAnswer(entry.id, entry.question, it) }
                            },
                    ),
            )
        }
    }
}
