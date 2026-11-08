package dev.opencode.hexcode.app

import dev.opencode.hexcode.session.SessionSetup
import dev.opencode.hexcode.session.TodayState

/**
 * Holds the system splash on a cold start (app-plan.md, Home › Cold start) until [ready], or for at most [timeoutMs]
 * from when it was made, so the home appears in one piece instead of part by part. The activity asks [holding] on
 * every frame (`setKeepOnScreenCondition`); once it says no, it never says yes again.
 */
class ColdStartGate(
    private val clock: () -> Long,
    private val timeoutMs: Long = ColdStart.TIMEOUT_MS,
) {
    private val startedAt = clock()

    @Volatile
    private var released = false

    /** The first state is on screen: the splash can go. */
    fun ready() {
        released = true
    }

    /** The splash stays. */
    fun holding(): Boolean {
        if (released) return false
        if (clock() - startedAt >= timeoutMs) released = true
        return !released
    }
}

object ColdStart {
    /** The longest the splash waits for the first state. */
    const val TIMEOUT_MS = 1_500L

    /** How long the splash, with its logo, fades out as the home appears. */
    const val SPLASH_FADE_MS = 200L

    /**
     * The first state is ready: today's session found and its summary read (a card, or a failure with none), or
     * "Couldn't open today's session", or Signed out. A card saved for today shown while setup runs isn't: the top bar
     * state and the goal line would still be missing.
     */
    fun settled(today: TodayState): Boolean =
        when (today.setup) {
            SessionSetup.Opening -> false
            is SessionSetup.Ready -> today.summary.summary != null || today.summary.failed
            is SessionSetup.Failed, SessionSetup.SignedOut -> true
        }

    /**
     * How much of the status area shows, for its fade: 0 nothing (setup runs with no saved card), 1 only the card's ↻
     * line (today's session is open, its first summary on its way), 2 the card, its fallback, or a failure. Each step
     * up fades in, so a card arriving after the splash has gone doesn't pop in.
     */
    fun cardStage(today: TodayState): Int =
        when {
            today.summary.summary != null -> 2
            today.setup is SessionSetup.Failed || today.setup == SessionSetup.SignedOut -> 2
            today.setup is SessionSetup.Ready -> 1
            else -> 0
        }
}
