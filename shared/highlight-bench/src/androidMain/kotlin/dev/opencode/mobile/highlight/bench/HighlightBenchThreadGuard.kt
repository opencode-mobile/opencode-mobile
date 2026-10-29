package dev.opencode.mobile.highlight.bench

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs each isolated call on a new daemon thread and gives up on it once
 * [HighlightBenchTask.remainingWaitMs] runs out (the limit and grace, or the grace after a cancel).
 * Android cannot stop a thread, so an abandoned call keeps running; JVM host tests pass
 * [stopAbandoned] to force-stop it and report whether it stopped.
 */
class HighlightBenchThreadGuard(
    private val stackSizeBytes: Long = 0,
    private val stopAbandoned: ((Thread) -> Boolean)? = null,
) : HighlightBenchCallGuard {
    override fun execute(task: HighlightBenchTask): HighlightBenchGuardExit {
        val done = CountDownLatch(1)
        val thread =
            Thread(null, {
                try {
                    task.execute()
                } finally {
                    done.countDown()
                }
            }, "highlight-bench", stackSizeBytes).apply {
                isDaemon = true
                start()
            }
        val startedAt = System.nanoTime()
        while (true) {
            val remaining = task.remainingWaitMs((System.nanoTime() - startedAt) / 1_000_000)
            if (remaining <= 0) break
            if (done.await(minOf(remaining, POLL_MS), TimeUnit.MILLISECONDS)) return HighlightBenchGuardExit.Finished
        }
        if (task.isFinished) return HighlightBenchGuardExit.Finished
        val stop = stopAbandoned ?: return HighlightBenchGuardExit.Abandoned
        return if (stop(thread)) HighlightBenchGuardExit.ForceStopped else HighlightBenchGuardExit.Abandoned
    }

    private companion object {
        /** How often the wait checks for a cancel; a finished call ends it at once. */
        const val POLL_MS = 20L
    }
}
