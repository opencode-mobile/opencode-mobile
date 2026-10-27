package dev.opencode.mobile.highlight.bench

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs each isolated call on a new daemon thread and gives up on it after
 * [HighlightBenchTask.giveUpAfterMs]. Android cannot stop a thread, so an abandoned call keeps
 * running; JVM host tests pass [stopAbandoned] to force-stop it and report whether it stopped.
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
        if (done.await(task.giveUpAfterMs, TimeUnit.MILLISECONDS)) return HighlightBenchGuardExit.Finished
        val stop = stopAbandoned ?: return HighlightBenchGuardExit.Abandoned
        return if (stop(thread)) HighlightBenchGuardExit.ForceStopped else HighlightBenchGuardExit.Abandoned
    }
}
