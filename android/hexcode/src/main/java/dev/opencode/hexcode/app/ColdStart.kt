package dev.opencode.hexcode.app

import dev.opencode.hexcode.session.SessionSetup
import dev.opencode.hexcode.session.TodayState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds the system splash on a cold start (app-plan.md, Home › Cold start) only while the app reads what's saved on the
 * phone ([ready]), or for at most [timeoutMs] from when it was made; it never waits for the network. The activity asks
 * [holding] on every frame (`setKeepOnScreenCondition`); once it says no, it never says yes again.
 */
class ColdStartGate(
    private val clock: () -> Long,
    private val timeoutMs: Long = ColdStart.TIMEOUT_MS,
) {
    private val startedAt = clock()
    private val gone = CompletableDeferred<Unit>()

    @Volatile
    private var released = false

    /** What's saved is on screen: the splash can go. */
    fun ready() {
        released = true
        gone.complete(Unit)
    }

    /** The splash stays. */
    fun holding(): Boolean {
        if (!released && clock() - startedAt >= timeoutMs) ready()
        return !released
    }

    /** Returns once the splash has been told to go (or timed out). */
    suspend fun awaitGone() = gone.await()
}

/**
 * Runs [load] once: [delayMs] after [afterFirstFrame], or at once on [now], whichever comes first (app-plan.md,
 * Dictation › Load: the speech model loads about 1 s after the first frame, or when the talk button is pressed).
 */
class DeferredLoad(
    private val scope: CoroutineScope,
    private val delayMs: Long,
    private val load: () -> Unit,
) {
    private val started = AtomicBoolean(false)
    private var timer: Job? = null

    /** The first frame is drawn: [load] runs in [delayMs] unless [now] ran it sooner. */
    fun afterFirstFrame() {
        if (started.get() || timer != null) return
        timer =
            scope.launch {
                delay(delayMs)
                now()
            }
    }

    /** [load] runs now, if it hasn't yet. */
    fun now() {
        if (started.compareAndSet(false, true)) {
            timer?.cancel()
            load()
        }
    }
}

object ColdStart {
    /** The longest the splash waits for what's saved on the phone. */
    const val TIMEOUT_MS = 500L

    /** How long the home fades in over the splash's logo. */
    const val SPLASH_FADE_MS = 200L

    /** How long after the first frame the speech model starts loading, so it doesn't compete with start-up. */
    const val SPEECH_DELAY_MS = 1_000L

    /**
     * What the home shows first is ready: what's saved on the phone has been read (the saved card, if it's today's
     * saved session's), or setup has already got further. Never today's session from the server.
     */
    fun settled(today: TodayState): Boolean = today.restored || today.setup != SessionSetup.Opening

    /**
     * How much of the status area shows, for its fade: 0 nothing (setup runs with no saved card), 1 only the card's ↻
     * line (today's session is open, its first summary on its way), 2 the card, its fallback, or a failure. Each step
     * up fades in, so a card that arrives once today's session is known doesn't pop in.
     */
    fun cardStage(today: TodayState): Int =
        when {
            today.summary.summary != null -> 2
            today.setup is SessionSetup.Failed || today.setup == SessionSetup.SignedOut -> 2
            today.setup is SessionSetup.Ready -> 1
            else -> 0
        }
}
