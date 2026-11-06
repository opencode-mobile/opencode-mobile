package dev.opencode.hexcode.home

/** The After turn / Now switch on the draft while the agent works (app-plan.md, Sending). */
enum class SendMode {
    /** `queue`: delivered after this turn. The default, and what the switch goes back to after every send. */
    AfterTurn,

    /** Held on the phone for 3 s with Undo, then `steer` with the `[Correction] ` label. */
    Now,
}

/** What Send does with the draft. */
enum class SendPlan {
    /** The agent is idle: `steer`, which starts a run. */
    Idle,

    /** The agent works, After turn: `queue`. */
    Queue,

    /** The agent works, Now: held 3 s on the phone, then `steer` as a correction. */
    Now,
}

/** The rules of the app-plan.md "Sending" table, as pure functions. */
object Sending {
    /** How long a Now is held on the phone, with Undo, before it's sent. */
    const val HOLD_MS = 3_000L

    /** The label a Now carries, so the agent adjusts rather than restarts. */
    const val CORRECTION = "[Correction] "

    /** [busy]: the session runs or waits for an answer. */
    fun plan(
        busy: Boolean,
        mode: SendMode,
    ): SendPlan =
        when {
            !busy -> SendPlan.Idle
            mode == SendMode.Now -> SendPlan.Now
            else -> SendPlan.Queue
        }

    /** The request's `delivery`. */
    fun delivery(plan: SendPlan): String = if (plan == SendPlan.Queue) "queue" else "steer"

    /** The request's `text`: a Now is labelled as a correction. */
    fun text(
        plan: SendPlan,
        draft: String,
    ): String = if (plan == SendPlan.Now) CORRECTION + draft else draft
}
