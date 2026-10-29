package dev.opencode.mobile.highlight.bench

import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Where an isolated case's engine call runs. Common code cannot stop a thread, so the platform
 * owns them: a guard runs the task, waits at most [HighlightBenchTask.remainingWaitMs], and gives
 * up on a call that ignores its cancel signal so the run can go on.
 */
interface HighlightBenchCallGuard {
    /**
     * Calls [HighlightBenchTask.execute] exactly once, on any thread, and returns when it
     * finished or when the guard gave up on it.
     */
    fun execute(task: HighlightBenchTask): HighlightBenchGuardExit
}

enum class HighlightBenchGuardExit {
    Finished,

    /** The guard stopped waiting; the call may still be running in the background. */
    Abandoned,

    /** The guard stopped waiting and killed the thread (JVM host tests only). */
    ForceStopped,
}

/** Runs the call on the caller's thread. A call that ignores its cancel signal blocks the run. */
object HighlightBenchInlineGuard : HighlightBenchCallGuard {
    override fun execute(task: HighlightBenchTask): HighlightBenchGuardExit {
        task.execute()
        return HighlightBenchGuardExit.Finished
    }
}

/** One engine call prepared by the runner. Its timing is taken on the thread that executes it. */
class HighlightBenchTask internal constructor(
    private val highlighter: HighlightBenchHighlighter,
    private val language: String,
    private val source: String,
    private val collectSpans: Boolean,
    private val limit: Duration,
    grace: Duration,
    private val canceller: HighlightBenchCanceller,
) {
    private val graceMs: Long = grace.inWholeMilliseconds

    /** The call limit plus the grace a cancelled call gets to return. */
    val giveUpAfterMs: Long = (limit + grace).inWholeMilliseconds

    /**
     * How much longer a guard that has waited [waitedMs] for this call should wait before giving
     * up on it: the rest of [giveUpAfterMs], cut to the grace once the caller cancels. 0 means give
     * up now. Guards poll it, so a cancel ends the wait without waking them.
     */
    fun remainingWaitMs(waitedMs: Long): Long {
        val untilLimit = giveUpAfterMs - waitedMs
        val untilGraceEnds = canceller.cancelledForMs?.let { graceMs - it } ?: Long.MAX_VALUE
        return minOf(untilLimit, untilGraceEnds).coerceAtLeast(0)
    }

    @Volatile
    var isFinished: Boolean = false
        private set

    internal var elapsed: Duration = Duration.ZERO
        private set
    internal var output: HighlightBenchOutput? = null
        private set
    internal var error: Throwable? = null
        private set

    fun execute() {
        val startedAt = TimeSource.Monotonic.markNow()
        try {
            output = highlighter.highlight(language, source, HighlightBenchCancelSignal(startedAt + limit, canceller), collectSpans)
        } catch (failure: Throwable) {
            error = failure
        } finally {
            elapsed = startedAt.elapsedNow()
            isFinished = true
        }
    }
}
