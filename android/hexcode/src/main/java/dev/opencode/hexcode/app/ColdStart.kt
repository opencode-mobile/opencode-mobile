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
     * What the date card says while there's no card yet (app-plan.md, Home › Cold start): from the first frame, the
     * status area shows today's date with a dimmed line of what the app is doing, until a card (saved, made or the
     * screen's own) takes its place. Null once there's a card, or while setup failed or is signed out, which have their
     * own views.
     */
    fun catchingUp(today: TodayState): CatchingUp? =
        when {
            today.summary.summary != null -> null
            today.setup == SessionSetup.Opening -> CatchingUp.Connecting
            today.setup !is SessionSetup.Ready -> null
            today.summary.loading -> CatchingUp.Summarising
            today.summary.failed -> CatchingUp.Failed
            else -> CatchingUp.Opening
        }
}

/** The date card's Now, before the first card ([ColdStart.catchingUp]). */
enum class CatchingUp {
    /** Setup runs: "Reaching your computer…". */
    Connecting,

    /** Today's session is found, its state not read yet: "Opening today’s session…". */
    Opening,

    /** The first summary is on its way: "Summarising the last run…". */
    Summarising,

    /** The first summary request failed: "Couldn’t summarise the last run." (↻ says "Couldn't update" and retries). */
    Failed,
}
