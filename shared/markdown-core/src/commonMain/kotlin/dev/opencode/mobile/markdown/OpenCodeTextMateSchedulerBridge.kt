package dev.opencode.mobile.markdown

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * JSON-facing scheduler driver for Swift. Keeps Kotlin request types inside KMP
 * while the platform actor owns continuations and serial execution turns.
 */
class OpenCodeTextMateSchedulerBridge(
    private val engine: OpenCodeTextMateHighlightingEngine =
        OpenCodeTextMateHighlightingEngine(createOpenCodePlatformMarkdownHighlighter()),
    private val scheduler: TextMateRequestScheduler = TextMateRequestScheduler(),
) {
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
        val admit = scheduler.admit(request)
        return OpenCodeMarkdownJson.json.encodeToString(
            SchedulerAdmitEnvelope(
                ticketId = admit.ticketId,
                completed = admit.completed.map { it.result },
            ),
        )
    }

    /**
     * Picks and executes at most one job. Returns null when the queue is idle or
     * an active job is already running (should not happen when the platform is
     * the sole driver).
     */
    fun pumpOnce(): String? {
        val job = scheduler.pickNext() ?: return null
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
        val completed = scheduler.finishActive(job.ticketId, raw)
        return OpenCodeMarkdownJson.json.encodeToString(completed.map { it.result })
    }

    fun trim(level: String): String {
        val pressure = MemoryPressure.valueOf(level)
        val completed = scheduler.trim(pressure)
        engine.trimNow(pressure)
        return OpenCodeMarkdownJson.json.encodeToString(completed.map { it.result })
    }

    fun setLowPowerOrThermal(disabled: Boolean) {
        scheduler.setLowPowerOrThermal(disabled)
    }

    fun snapshot(): String = OpenCodeMarkdownJson.json.encodeToString(scheduler.snapshot())

    companion object {
        fun createDefault(): OpenCodeTextMateSchedulerBridge = OpenCodeTextMateSchedulerBridge()
    }
}

@Serializable
data class SchedulerAdmitEnvelope(
    val ticketId: Long? = null,
    val completed: List<HighlightResult> = emptyList(),
)
