package dev.opencode.mobile.domain.session

import dev.opencode.mobile.domain.live.LiveStoreScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** One `/btw` question and where it stands. Answers live in memory only. */
data class SideQuestionEntry(
    val id: String,
    val sessionId: String,
    val question: String,
    val status: Status,
) {
    sealed interface Status {
        data object Pending : Status

        data class Answered(
            val text: String,
        ) : Status

        /** The request failed and asking again may work. */
        data object Failed : Status
    }

    val answer: String?
        get() = (status as? Status.Answered)?.text
}

sealed interface SideQuestionEvent {
    val sessionId: String

    data class Answered(
        override val sessionId: String,
        val id: String,
    ) : SideQuestionEvent

    /**
     * The request failed. A [retryable] entry stays as [SideQuestionEntry.Status.Failed]; any other
     * is already removed, since asking again cannot succeed (no `generate` route for it).
     */
    data class Failed(
        override val sessionId: String,
        val id: String,
        val question: String,
        val retryable: Boolean,
    ) : SideQuestionEvent
}

/**
 * In-memory `/btw` requests and unread answers for the active connection, keyed by session id.
 * It outlives Session Chat, so a request keeps running and its answer waits after the user leaves
 * the session. [clear] runs on every profile change; killing the app drops everything.
 */
@Singleton
class SideQuestionStore(
    private val request: suspend (sessionId: String, question: String) -> String,
    private val scope: CoroutineScope,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    @Inject
    constructor(
        sessionRepository: SessionRepository,
        liveStoreScope: LiveStoreScope,
    ) : this(
        request = { sessionId, question -> sessionRepository.askSideQuestion(sessionId, question) },
        scope = CoroutineScope(liveStoreScope.coroutineScope.coroutineContext + Dispatchers.IO),
    )

    private val entries = MutableStateFlow<List<SideQuestionEntry>>(emptyList())
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _events = MutableSharedFlow<SideQuestionEvent>(extraBufferCapacity = 64)

    /** Answers and failures as they happen, for the screen that is showing the session. */
    val events: SharedFlow<SideQuestionEvent> = _events.asSharedFlow()

    /** The session's entries, oldest first. */
    fun observe(sessionId: String): Flow<List<SideQuestionEntry>> =
        entries.map { all -> all.filter { it.sessionId == sessionId } }.distinctUntilChanged()

    fun entries(sessionId: String): List<SideQuestionEntry> = entries.value.filter { it.sessionId == sessionId }

    fun entry(id: String): SideQuestionEntry? = entries.value.firstOrNull { it.id == id }

    /** Starts a request. Returns null and does nothing for a blank question. */
    fun ask(
        sessionId: String,
        question: String,
    ): String? {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return null
        val entry = SideQuestionEntry(newId(), sessionId, trimmed, SideQuestionEntry.Status.Pending)
        entries.update { it + entry }
        start(entry)
        return entry.id
    }

    fun retry(id: String) {
        val entry = entry(id)?.takeIf { it.status == SideQuestionEntry.Status.Failed } ?: return
        replace(id) { it.copy(status = SideQuestionEntry.Status.Pending) }
        start(entry)
    }

    /** Forgets an entry, cancelling its request if it is still running. */
    fun remove(id: String) {
        jobs.remove(id)?.cancel()
        entries.update { all -> all.filterNot { it.id == id } }
    }

    /** Drops every request and answer: the active Connection Profile changed. */
    fun clear() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        entries.value = emptyList()
    }

    private fun start(entry: SideQuestionEntry) {
        jobs.remove(entry.id)?.cancel()
        jobs[entry.id] =
            scope.launch {
                val result = runCatching { request(entry.sessionId, entry.question) }
                // A removed or cleared entry was cancelled; its outcome no longer matters.
                if (!isActive) return@launch
                finish(entry, result)
            }
    }

    private fun finish(
        entry: SideQuestionEntry,
        result: Result<String>,
    ) {
        jobs.remove(entry.id)
        if (entry(entry.id) == null) return
        result.fold(
            onSuccess = { text ->
                replace(entry.id) { it.copy(status = SideQuestionEntry.Status.Answered(text)) }
                _events.tryEmit(SideQuestionEvent.Answered(entry.sessionId, entry.id))
            },
            onFailure = { error ->
                val retryable = isRetryable(error)
                if (retryable) {
                    replace(entry.id) { it.copy(status = SideQuestionEntry.Status.Failed) }
                } else {
                    entries.update { all -> all.filterNot { it.id == entry.id } }
                }
                _events.tryEmit(SideQuestionEvent.Failed(entry.sessionId, entry.id, entry.question, retryable))
            },
        )
    }

    private fun replace(
        id: String,
        transform: (SideQuestionEntry) -> SideQuestionEntry,
    ) {
        entries.update { all -> all.map { if (it.id == id) transform(it) else it } }
    }

    companion object {
        /** A 404 means the server has no `generate` route for this session, so Retry cannot help. */
        fun isRetryable(error: Throwable): Boolean = (error as? SessionError.Http)?.statusCode != 404
    }
}
