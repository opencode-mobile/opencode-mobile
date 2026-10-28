package dev.opencode.mobile.ui.sessionchat

/**
 * What a live activity card's footer band shows (#779): a tool, running (title,
 * `+N`, elapsed) or in its settled form (settled title only), or idle text.
 */
sealed interface SessionChatActivityFooter {
    data class Tool(
        val stepId: String,
        val family: SessionChatStepFamily?,
        val title: String,
        val running: Boolean,
        val extraRunning: Int,
        val startedAtMs: Long?,
    ) : SessionChatActivityFooter

    data class Idle(
        val text: String,
    ) : SessionChatActivityFooter

    companion object {
        /** The footer the steps ask for right now: the latest running tool, else idle text. */
        fun desired(
            steps: List<SessionChatStep>,
            thinkingText: String = SessionChatActivityCard.THINKING,
        ): SessionChatActivityFooter {
            val latest = SessionChatActivityCard.latestRunning(steps)
            if (latest != null) {
                val running = steps.count { it.kind == SessionChatStepKind.Tool && it.status.isInFlight }
                return Tool(
                    stepId = latest.id,
                    family = latest.family,
                    title = latest.runningTitle,
                    running = true,
                    extraRunning = running - 1,
                    startedAtMs = latest.startedAtMs,
                )
            }
            val waiting = steps.any { it.kind == SessionChatStepKind.ChildSession && it.status.isInFlight }
            return Idle(if (waiting) SessionChatActivityCard.WAITING_FOR_AGENTS else thinkingText)
        }

        /** [step] in its settled form: settled title, no `+N`, no elapsed. */
        fun settled(step: SessionChatStep): Tool =
            Tool(
                stepId = step.id,
                family = step.family,
                title = step.settledTitle,
                running = false,
                extraRunning = 0,
                startedAtMs = null,
            )
    }
}

/**
 * Identity the footer crossfades on: a tool's step and form, or the idle text.
 * A running tool's `+N` and elapsed change in place.
 */
val SessionChatActivityFooter.contentKey: String
    get() =
        when (this) {
            is SessionChatActivityFooter.Tool -> "tool:$stepId:${if (running) "running" else "settled"}"
            is SessionChatActivityFooter.Idle -> "idle:$text"
        }

/**
 * Footer hold for one live activity card (#779). What the band shows is
 * latched for [holdMs] so short tools stay readable:
 *
 * 1. The first value shows at once.
 * 2. A change inside the hold waits; only the latest one counts.
 * 3. A shown tool that settles inside the hold switches in place to its
 *    settled form, without restarting the hold.
 * 4. When the hold ends, the latest value shows and the hold restarts. When
 *    nothing runs and a tool that started after the shown one was never shown,
 *    the newest such tool shows settled for one hold first (one catch-up per
 *    hold).
 * 5. An unchanged value is a no-op.
 *
 * Pure and clock-injected: [submit] takes the card's visible steps (entry
 * debounced steps left out) on every update and on every timer tick;
 * [expiresAtMs] says when to tick next.
 */
class SessionChatActivityFooterHold(
    private val holdMs: Long = HOLD_MS,
    private val thinkingText: String = SessionChatActivityCard.THINKING,
) {
    var displayed: SessionChatActivityFooter? = null
        private set

    private var shownAtMs = 0L
    private var holding = false
    private var catchUp = false
    private var steps: List<SessionChatStep> = emptyList()

    // Tool steps on the card when [displayed] was latched; a catch-up only picks tools that came later.
    private var knownToolIds: Set<String> = emptySet()
    private val shownToolIds = mutableSetOf<String>()

    /** When the hold ends, or null with no hold running. */
    val expiresAtMs: Long?
        get() = if (holding) shownAtMs + holdMs else null

    /** The latest value waiting for the hold to end, or null. */
    val pending: SessionChatActivityFooter?
        get() {
            val shown = displayed ?: return null
            val desired = SessionChatActivityFooter.desired(steps, thinkingText)
            return desired.takeUnless { it.contentKey == shown.contentKey }
        }

    fun submit(
        steps: List<SessionChatStep>,
        nowMs: Long,
    ): SessionChatActivityFooter {
        this.steps = steps
        val desired = SessionChatActivityFooter.desired(steps, thinkingText)
        val shown = displayed ?: return show(desired, nowMs, catchUp = false)
        displayed = refreshed(shown, desired)
        if (holding && nowMs < shownAtMs + holdMs) return displayed!!
        holding = false
        return advance(nowMs)
    }

    /** Ends the hold when it is due; same as a [submit] of the last steps. */
    fun expire(nowMs: Long): SessionChatActivityFooter? {
        if (displayed == null) return null
        return submit(steps, nowMs)
    }

    private fun advance(nowMs: Long): SessionChatActivityFooter {
        val shown = displayed!!
        val desired = SessionChatActivityFooter.desired(steps, thinkingText)
        if (desired is SessionChatActivityFooter.Idle && !catchUp) {
            catchUpStep()?.let { return show(SessionChatActivityFooter.settled(it), nowMs, catchUp = true) }
        }
        catchUp = false
        return if (desired.contentKey == shown.contentKey) shown else show(desired, nowMs, catchUp = false)
    }

    /** The same running tool picks up its new `+N`; a shown tool that settled turns settled (rule 3). */
    private fun refreshed(
        shown: SessionChatActivityFooter,
        desired: SessionChatActivityFooter,
    ): SessionChatActivityFooter {
        if (shown !is SessionChatActivityFooter.Tool || !shown.running) return shown
        if (desired is SessionChatActivityFooter.Tool && desired.stepId == shown.stepId) return desired
        val step = steps.firstOrNull { it.id == shown.stepId } ?: return shown
        return if (step.status.isInFlight) shown else SessionChatActivityFooter.settled(step)
    }

    private fun catchUpStep(): SessionChatStep? =
        steps
            .withIndex()
            .filter { (_, step) ->
                step.kind == SessionChatStepKind.Tool &&
                    !step.status.isInFlight &&
                    step.id !in knownToolIds &&
                    step.id !in shownToolIds
            }.maxWithOrNull(compareBy<IndexedValue<SessionChatStep>> { it.value.startedAtMs ?: Long.MIN_VALUE }.thenBy { it.index })
            ?.value

    private fun show(
        footer: SessionChatActivityFooter,
        nowMs: Long,
        catchUp: Boolean,
    ): SessionChatActivityFooter {
        displayed = footer
        shownAtMs = nowMs
        holding = true
        this.catchUp = catchUp
        knownToolIds = steps.filter { it.kind == SessionChatStepKind.Tool }.mapTo(HashSet()) { it.id }
        if (footer is SessionChatActivityFooter.Tool) shownToolIds += footer.stepId
        return footer
    }

    companion object {
        const val HOLD_MS = 1_200L
    }
}

/**
 * Step entry debounce (#779): a tool or thought renders running only once the
 * client has seen it in flight for [delayMs]; before that it is hidden, and
 * one that settles sooner first appears settled. Child sessions are never
 * hidden. On the first update (history, reconnect) a step the server says
 * started at least [delayMs] ago shows at once.
 *
 * Pure and clock-injected: [submit] takes every step on screen on every update
 * and timer tick and returns the ids to hide; [nextDueMs] says when to tick next.
 */
class SessionChatStepEntryDebounce(
    private val delayMs: Long = DELAY_MS,
) {
    private val firstSeenMs = HashMap<String, Long>()
    private var primed = false

    /** Hidden step ids from the last [submit]. */
    var hidden: Set<String> = emptySet()
        private set

    /** When the next hidden step comes due, or null. */
    var nextDueMs: Long? = null
        private set

    fun submit(
        steps: List<SessionChatStep>,
        nowMs: Long,
    ): Set<String> {
        val inFlight = steps.filter { it.kind != SessionChatStepKind.ChildSession && it.status.isInFlight }
        firstSeenMs.keys.retainAll(inFlight.mapTo(HashSet()) { it.id })
        val firstUpdate = !primed
        primed = true
        val nextHidden = HashSet<String>()
        var due: Long? = null
        for (step in inFlight) {
            val seen =
                firstSeenMs.getOrPut(step.id) {
                    val started = step.startedAtMs
                    if (firstUpdate && started != null && nowMs - started >= delayMs) nowMs - delayMs else nowMs
                }
            val shownAt = seen + delayMs
            if (nowMs < shownAt) {
                nextHidden += step.id
                due = minOf(due ?: shownAt, shownAt)
            }
        }
        hidden = nextHidden
        nextDueMs = due
        return nextHidden
    }

    companion object {
        const val DELAY_MS = 300L
    }
}
