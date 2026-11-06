package dev.opencode.hexcode.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.roundToInt

/** Where the summary's reply comes from: `POST /api/session/{id}/generate` with [prompt], the model and its level. */
fun interface SummarySource {
    suspend fun summarise(prompt: String): String
}

/** The last card of today's session, kept on the phone so it survives the app being closed. */
@Serializable
data class SavedSummary(
    val sessionId: String,
    val summary: StatusSummary,
    /** When the card was made, in [System.currentTimeMillis] time. */
    val updatedAt: Long,
)

/** Where the last card is kept: one session's (today's) at a time, so saving forgets the one before. */
interface SummaryStore {
    suspend fun loadSummary(): SavedSummary?

    suspend fun saveSummary(saved: SavedSummary)
}

/** What ↻ says, beside its icon. */
sealed interface RefreshLabel {
    /** "updating…": a request is running. */
    data object Updating : RefreshLabel

    /** "Couldn't update": the last request failed, and the card above is the one before it. */
    data object Failed : RefreshLabel

    /** "now": updated less than 45 s ago. */
    data object Now : RefreshLabel

    /** "3m ago". */
    data class MinutesAgo(
        val minutes: Int,
    ) : RefreshLabel
}

/** The status card, and how fresh it is. */
data class SummaryState(
    val summary: StatusSummary? = null,
    /** When [summary] was last set, in [System.currentTimeMillis] time; null before the first. */
    val updatedAt: Long? = null,
    val loading: Boolean = false,
    /** The last request failed; [summary] is still the card before it. */
    val failed: Boolean = false,
) {
    /** ↻'s label at [now]: updating while a request runs, then a failure, then the card's age; null before any card. */
    fun label(now: Long): RefreshLabel? =
        when {
            loading -> RefreshLabel.Updating
            failed -> RefreshLabel.Failed
            updatedAt == null -> null
            now - updatedAt < FRESH_MS -> RefreshLabel.Now
            else -> RefreshLabel.MinutesAgo(((now - updatedAt) / 60_000.0).roundToInt())
        }

    companion object {
        /** Younger than this, a card was updated "now". */
        const val FRESH_MS = 45_000L
    }
}

/**
 * Keeps the status summary: asks [source] with [SessionPrompts.SUMMARY] and reads the reply with
 * [StatusSummary.parse]. Never two requests at once: a refresh while one runs is dropped, as the prototype's
 * `refreshCard`. A failed request, an empty reply or no reply within [TIMEOUT_MS] keeps the last card and marks it
 * [SummaryState.failed]; an empty reply while the agent is [busy] keeps it without the mark, or with no card yet shows
 * [StatusSummary.Working]. Each new card (parsed or fallback) is saved in [store] for its session, and [open] shows it at
 * once, with its age, the next time that session opens, until a refresh succeeds.
 *
 * The caller decides when (app-plan.md: on open and reconnect, when the turn ends, on ↻; never on a timer).
 */
class SummaryRefresher(
    private val scope: CoroutineScope,
    private val source: SummarySource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = TIMEOUT_MS,
    /** The agent is working: the server often answers with no text then, which isn't a failure. */
    private val busy: () -> Boolean = { false },
    private val store: SummaryStore? = null,
    private val today: () -> LocalDate = LocalDate::now,
) {
    private val mutable = MutableStateFlow(SummaryState())
    val state: StateFlow<SummaryState> = mutable.asStateFlow()

    private var job: Job? = null

    /** The session whose cards are saved; set by [open]. */
    @Volatile
    private var sessionId: String? = null

    /** Today's session opened: its saved card shows at once, with its age. A card saved for another session doesn't. */
    suspend fun open(sessionId: String) {
        clear()
        this.sessionId = sessionId
        val saved =
            try {
                store?.loadSummary()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        if (saved != null && saved.sessionId == sessionId && this.sessionId == sessionId) {
            mutable.update { if (it.summary == null) it.copy(summary = saved.summary, updatedAt = saved.updatedAt) else it }
        }
    }

    /** No card, before a session opens or when another session takes its place. A request still running is dropped. */
    fun clear() {
        job?.cancel()
        mutable.value = SummaryState()
    }

    /** A new session's card, without a request. A request still running is dropped. */
    fun showEmpty(date: LocalDate) {
        job?.cancel()
        mutable.value = SummaryState(StatusSummary.empty(date), updatedAt = clock())
    }

    /** Asks for a new summary, unless one is on its way. Returns whether this call started a request. */
    fun refresh(): Boolean {
        while (true) {
            val current = mutable.value
            if (current.loading) return false
            if (mutable.compareAndSet(current, current.copy(loading = true))) break
        }
        job =
            scope.launch {
                try {
                    // A timeout counts as a failed request, not as a cancellation.
                    val reply = withTimeoutOrNull(timeoutMs) { source.summarise(SessionPrompts.SUMMARY) }
                    if (reply != null && reply.isBlank() && busy()) {
                        // Empty while the agent works: the last card stays, without "Couldn't update" (with none yet,
                        // a card says the agent is working); the run's end refreshes it.
                        mutable.update {
                            if (it.summary == null) {
                                SummaryState(StatusSummary.working(today()), updatedAt = clock())
                            } else {
                                it.copy(loading = false, failed = false)
                            }
                        }
                        return@launch
                    }
                    if (reply.isNullOrBlank()) throw IllegalStateException("The summary timed out or came back empty.")
                    val summary = StatusSummary.parse(reply)
                    val updatedAt = clock()
                    mutable.value = SummaryState(summary, updatedAt = updatedAt)
                    save(summary, updatedAt)
                } catch (error: CancellationException) {
                    mutable.update { it.copy(loading = false) }
                    throw error
                } catch (_: Exception) {
                    mutable.update { it.copy(loading = false, failed = true) }
                }
            }
        return true
    }

    private suspend fun save(
        summary: StatusSummary,
        updatedAt: Long,
    ) {
        val id = sessionId ?: return
        try {
            store?.saveSummary(SavedSummary(id, summary, updatedAt))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The card shows; only the next launch misses it.
        }
    }

    companion object {
        /** A request that takes longer fails (app-plan.md, The status summary). */
        const val TIMEOUT_MS = 60_000L
    }
}
