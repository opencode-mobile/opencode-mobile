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
import java.time.LocalDate
import kotlin.math.roundToInt

/** Where the summary's reply comes from: `POST /api/session/{id}/generate` with [prompt], the model and its level. */
fun interface SummarySource {
    suspend fun summarise(prompt: String): String
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
 * [SummaryState.failed].
 *
 * The caller decides when (app-plan.md: on open and reconnect, when the turn ends, on ↻; never on a timer).
 */
class SummaryRefresher(
    private val scope: CoroutineScope,
    private val source: SummarySource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    private val mutable = MutableStateFlow(SummaryState())
    val state: StateFlow<SummaryState> = mutable.asStateFlow()

    private var job: Job? = null

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
                    if (reply.isNullOrBlank()) throw IllegalStateException("The summary timed out or came back empty.")
                    val summary = StatusSummary.parse(reply)
                    mutable.value = SummaryState(summary, updatedAt = clock())
                } catch (error: CancellationException) {
                    mutable.update { it.copy(loading = false) }
                    throw error
                } catch (_: Exception) {
                    mutable.update { it.copy(loading = false, failed = true) }
                }
            }
        return true
    }

    companion object {
        /** A request that takes longer fails (app-plan.md, The status summary). */
        const val TIMEOUT_MS = 60_000L
    }
}
