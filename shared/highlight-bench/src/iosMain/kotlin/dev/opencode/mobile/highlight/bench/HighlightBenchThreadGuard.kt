package dev.opencode.mobile.highlight.bench

import platform.Foundation.NSThread
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time

/**
 * Runs each isolated call on a new thread and gives up on it after
 * [HighlightBenchTask.giveUpAfterMs]. iOS cannot stop a thread, so an abandoned call keeps
 * running until it returns. [stackSizeBytes] of 0 keeps the system default (512 KB).
 */
class HighlightBenchThreadGuard(
    private val stackSizeBytes: Long,
) : HighlightBenchCallGuard {
    constructor() : this(0)

    override fun execute(task: HighlightBenchTask): HighlightBenchGuardExit {
        val done = dispatch_semaphore_create(0)
        val thread =
            NSThread {
                try {
                    task.execute()
                } finally {
                    dispatch_semaphore_signal(done)
                }
            }
        thread.name = "highlight-bench"
        if (stackSizeBytes > 0) thread.stackSize = stackSizeBytes.toULong()
        thread.start()
        val timeout = dispatch_time(DISPATCH_TIME_NOW, task.giveUpAfterMs * 1_000_000)
        return if (dispatch_semaphore_wait(done, timeout) == 0L) {
            HighlightBenchGuardExit.Finished
        } else {
            HighlightBenchGuardExit.Abandoned
        }
    }
}
