package dev.opencode.mobile.markdown

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * JSON-facing scheduler driver for Swift. Keeps Kotlin request types inside KMP
 * while the platform actor owns continuations.
 *
 * Two sides: [admit], [trimQueue], [setLowPowerOrThermal], [hasPendingWork], and
 * [snapshot] are queue operations and may run on any thread, including while a
 * job executes, which is how a newer revision cancels the running one.
 * [pumpOnce] and [trimEngine] touch engine state and must stay on one serial executor.
 */
class OpenCodeTextMateSchedulerBridge(
    private val engine: OpenCodeTextMateHighlightingEngine =
        OpenCodeTextMateHighlightingEngine(createOpenCodePlatformMarkdownHighlighter()),
    private val scheduler: TextMateRequestScheduler = TextMateRequestScheduler(),
) {
    private val lock = createTextMateSchedulerLock()

    fun admit(requestJson: String): String {
        val request =
            try {
                OpenCodeMarkdownJson.json.decodeFromString<HighlightRequest>(requestJson)
            } catch (error: Exception) {
                // Swift JSONEncoder omits nil optionals; never abort the host process
                // on a decode mismatch — return a cancelled empty result instead.
                return OpenCodeMarkdownJson.json.encodeToString(
                    SchedulerAdmitEnvelope(
                        ticketId = null,
                        completed = emptyList(),
                    ),
                )
            }
        val admit = lock.withLock { scheduler.admit(request) }
        return OpenCodeMarkdownJson.json.encodeToString(
            SchedulerAdmitEnvelope(
                ticketId = admit.ticketId,
                completed = admit.completed.map { it.result },
            ),
        )
    }

    /**
     * Picks and executes at most one job on the calling executor. Returns null when
     * the queue is idle or an active job is already running (should not happen when
     * the platform is the sole driver).
     */
    fun pumpOnce(): String? {
        val job = lock.withLock { scheduler.pickNext() } ?: return null
        val raw =
            if (job.cancelRequested()) {
                emptyResult(
                    request = job.request,
                    outcome = TextMateParseOutcome.Cancelled,
                    queueWaitNanoseconds = job.queueWaitNanoseconds,
                    schedulingReason = TextMateSchedulingReason.Supersession.name,
                )
            } else {
                engine.highlightNow(job.request, shouldCancel = job.cancelRequested)
            }
        val completed = lock.withLock { scheduler.finishActive(job.ticketId, raw) }
        return OpenCodeMarkdownJson.json.encodeToString(completed.map { it.result })
    }

    /** True while a job runs or waits; lets the driver close the gap after [pumpOnce] returns null. */
    fun hasPendingWork(): Boolean = lock.withLock { !scheduler.isIdle() }

    /** Drops queued work for [level] and, on Critical, cancels a running history job. */
    fun trimQueue(level: String): String {
        val completed = lock.withLock { scheduler.trim(MemoryPressure.valueOf(level)) }
        return OpenCodeMarkdownJson.json.encodeToString(completed.map { it.result })
    }

    /** Trims engine caches; call on the [pumpOnce] executor. */
    fun trimEngine(level: String) {
        engine.trimNow(MemoryPressure.valueOf(level))
    }

    fun setLowPowerOrThermal(disabled: Boolean) {
        lock.withLock { scheduler.setLowPowerOrThermal(disabled) }
    }

    fun snapshot(): String = OpenCodeMarkdownJson.json.encodeToString(lock.withLock { scheduler.snapshot() })

    companion object {
        fun createDefault(): OpenCodeTextMateSchedulerBridge = OpenCodeTextMateSchedulerBridge()
    }
}

@Serializable
data class SchedulerAdmitEnvelope(
    val ticketId: Long? = null,
    val completed: List<HighlightResult> = emptyList(),
)
