package dev.opencode.hexcode.session

import android.util.Log
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

    /** The session's `time.updated` now (`GET /api/session/{id}`), the basis of the card a request makes. */
    suspend fun updatedAt(): Long? = null
}

/**
 * The last card of today's session, kept on the phone so it survives the app being closed. [date] (`2026-11-06`) and
 * [directory] say which day's session in which project it belongs to, so it can show before setup finds the session.
 */
@Serializable
data class SavedSummary(
    val sessionId: String,
    val summary: StatusSummary,
    /** When the card was made, in [System.currentTimeMillis] time. */
    val updatedAt: Long,
    val date: String = "",
    val directory: String = "",
    /** The session's `time.updated` read just before the request that made the card (app-plan.md, Nothing new). */
    val basis: Long? = null,
    /** The card was made while the session was busy (a mid-run card), so it never counts as up to date. */
    val madeWhileBusy: Boolean = false,
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
 * [StatusSummary.Working]. Each new card (parsed or fallback) is saved in [store] for its session, with its date and
 * project; the next launch shows it at once, with its age, until a refresh succeeds: [preload] before setup when the
 * date and project match, [open] once setup has found the session (which drops a card saved for another).
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

    /** The session whose cards are saved, its date and project; set by [open]. */
    @Volatile
    private var sessionId: String? = null
    private var date: LocalDate? = null
    private var directory: String? = null

    /** The session the card on screen was saved for, or null when there is none. */
    @Volatile
    private var shownFor: String? = null

    /** The card on screen as saved, with its basis; null for a card that wasn't made by a request. */
    @Volatile
    private var saved: SavedSummary? = null

    /** Called with whether the session was busy each time a request makes a new card. */
    var onNewCard: (busy: Boolean) -> Unit = {}

    /**
     * Whether the card on screen is up to date (app-plan.md, Nothing new): it's today's session's, it was made while
     * the session was idle, and its basis is the session's [updatedAt] now.
     */
    fun isUpToDate(updatedAt: Long?): Boolean {
        val card = saved ?: return false
        return updatedAt != null &&
            shownFor == sessionId &&
            card.sessionId == sessionId &&
            !card.madeWhileBusy &&
            card.basis == updatedAt &&
            mutable.value.summary == card.summary
    }

    /** The app switched the model itself: that moves `time.updated`, not the card's content, so its basis moves too. */
    suspend fun moveBasis(updatedAt: Long?) {
        val card = saved ?: return
        if (updatedAt == null || card.basis == null || card.sessionId != sessionId) return
        val moved = card.copy(basis = updatedAt)
        saved = moved
        store(moved)
    }

    /**
     * No card yet while the session is busy: say it's working, without a request; the run's end refreshes. A new
     * session's empty card says it's working on the message instead ([markStarted]).
     */
    fun showWorking() {
        markStarted()
        if (mutable.value.summary != null || mutable.value.loading) return
        mutable.value = SummaryState(StatusSummary.working(today()), updatedAt = clock())
        shownFor = sessionId
    }

    /**
     * The app opens, before setup has found today's session: the saved card shows at once, with its age, if it was
     * saved for a session of [date] in [directory] and that session is [savedSession], today's saved session, when
     * the phone knows it (app-plan.md, Home › Cold start). Anything else on screen goes.
     */
    suspend fun preload(
        date: LocalDate,
        directory: String,
        savedSession: String? = null,
    ) {
        clear()
        val saved = load() ?: return
        if (savedSession != null && saved.sessionId != savedSession) return
        if (saved.date == date.toString() && saved.directory == directory) show(saved)
    }

    /**
     * Setup found today's session: a card already shown for it stays; one shown for a different session goes, and
     * this session's saved card, if any, shows instead. New cards are saved for it.
     */
    suspend fun open(
        sessionId: String,
        date: LocalDate,
        directory: String,
    ) {
        this.sessionId = sessionId
        this.date = date
        this.directory = directory
        if (shownFor == sessionId && mutable.value.summary != null) return
        clear()
        val saved = load() ?: return
        if (saved.sessionId == sessionId && this.sessionId == sessionId) show(saved)
    }

    private suspend fun load(): SavedSummary? =
        try {
            store?.loadSummary()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

    private fun show(saved: SavedSummary) {
        mutable.update { if (it.summary == null) it.copy(summary = saved.summary, updatedAt = saved.updatedAt) else it }
        shownFor = saved.sessionId
        this.saved = saved
    }

    /** No card, before a session opens or when another session takes its place. A request still running is dropped. */
    fun clear() {
        runEndWaiting = false
        job?.cancel()
        mutable.value = SummaryState()
        shownFor = null
        saved = null
    }

    /** A new session's card, without a request. A request still running is dropped. */
    fun showEmpty(date: LocalDate) {
        runEndWaiting = false
        job?.cancel()
        mutable.value = SummaryState(StatusSummary.empty(date), updatedAt = clock())
        shownFor = sessionId
        saved = null
    }

    /**
     * A message went to a new session, or it started running: its empty card reads "Working on your message." until
     * the first summary (app-plan.md, Empty session). Any other card stays as it is.
     */
    fun markStarted() {
        mutable.update { state ->
            val empty = state.summary as? StatusSummary.Empty
            if (empty == null || empty.started) state else state.copy(summary = empty.copy(started = true))
        }
    }

    /** A run ended while a request was on its way: one more request runs as soon as that one finishes. */
    @Volatile
    private var runEndWaiting = false

    /**
     * Asks for a new summary, unless one is on its way: then the call is dropped, except a [runEnd], which waits and
     * runs once when the current request finishes, so a mid-run card (from ↻ or a goal round) never outlives the run
     * (app-plan.md, The status summary › When). At most one waits. Returns whether this call started a request.
     */
    fun refresh(runEnd: Boolean = false): Boolean {
        while (true) {
            val current = mutable.value
            if (current.loading) {
                if (runEnd) runEndWaiting = true
                return false
            }
            if (mutable.compareAndSet(current, current.copy(loading = true))) break
        }
        val request =
            scope.launch {
                try {
                    // The basis first: generate itself doesn't move `time.updated`, so the card is up to date with it.
                    val basis =
                        try {
                            source.updatedAt()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        }
                    val busyAtStart = busy()
                    // A timeout counts as a failed request, not as a cancellation.
                    val reply = withTimeoutOrNull(timeoutMs) { source.summarise(SessionPrompts.SUMMARY) }
                    if (reply != null && reply.isBlank() && busy()) {
                        // Empty while the agent works: the last card stays, without "Couldn't update" (with none yet,
                        // a card says the agent is working); the run's end refreshes it.
                        mutable.update {
                            if (it.summary == null) {
                                shownFor = sessionId
                                SummaryState(StatusSummary.working(today()), updatedAt = clock())
                            } else {
                                it.copy(loading = false, failed = false)
                            }
                        }
                        return@launch
                    }
                    if (reply.isNullOrBlank()) throw IllegalStateException("The summary timed out or came back empty.")
                    // Busy as the request started: that's what the reply describes.
                    val midRun = busyAtStart
                    // Mid-run the model suggests its own next step, not the user's: the card drops it (Mid-run card).
                    val parsed = StatusSummary.parse(reply)
                    // No warning on the card for a reply with no shape (it still shows as one); the log keeps it.
                    if (parsed is StatusSummary.Fallback) Log.i("HexCode.Summary", "the reply had no shape (no title, no Now:)")
                    val summary =
                        when {
                            !midRun -> parsed
                            parsed is StatusSummary.Card -> parsed.copy(next = null)
                            parsed is StatusSummary.Fallback -> parsed.copy(next = null)
                            else -> parsed
                        }
                    val updatedAt = clock()
                    mutable.value = SummaryState(summary, updatedAt = updatedAt)
                    shownFor = sessionId
                    save(summary, updatedAt, basis, midRun)
                    onNewCard(midRun)
                } catch (error: CancellationException) {
                    mutable.update { it.copy(loading = false) }
                    throw error
                } catch (_: Exception) {
                    mutable.update { it.copy(loading = false, failed = true) }
                }
            }
        job = request
        request.invokeOnCompletion { cause ->
            // A dropped request (a new session, an empty card) takes the waiting run end with it.
            if (cause != null || !runEndWaiting) return@invokeOnCompletion
            runEndWaiting = false
            refresh()
        }
        return true
    }

    private suspend fun save(
        summary: StatusSummary,
        updatedAt: Long,
        basis: Long?,
        midRun: Boolean,
    ) {
        val id = sessionId ?: return
        val card = SavedSummary(id, summary, updatedAt, date?.toString().orEmpty(), directory.orEmpty(), basis, midRun)
        saved = card
        store(card)
    }

    private suspend fun store(card: SavedSummary) {
        try {
            store?.saveSummary(card)
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
