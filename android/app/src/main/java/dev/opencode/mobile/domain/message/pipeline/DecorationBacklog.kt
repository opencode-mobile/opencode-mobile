package dev.opencode.mobile.domain.message

/**
 * Bounded pending decoration work for Phase 4 lanes.
 * Text-lane publishes are never gated by this queue.
 */
internal object DecorationBacklogPolicy {
    const val PENDING_COUNT_CAPACITY = 128
    const val PENDING_CHAR_COST_BUDGET = 512_000
}

internal data class DecorationJob(
    val generation: Long,
    val sessionKey: String,
    val blockID: String,
    val textHash: Int,
    val textLength: Int,
    val isStreaming: Boolean,
    val messages: List<Message>,
    val priority: Int,
) {
    val charCost: Int get() = textLength
}

/**
 * Lowest [priority] number is dropped first under pressure.
 * 0 = remaining history, 1 = viewport enrich, 2 = streaming decoration.
 */
internal class DecorationBacklog {
    private val jobs = ArrayDeque<DecorationJob>()
    private var queuedChars = 0
    var highWaterCount = 0
        private set
    var highWaterChars = 0
        private set

    val size: Int get() = jobs.size

    fun tryEnqueue(job: DecorationJob): Boolean {
        while (
            jobs.size >= DecorationBacklogPolicy.PENDING_COUNT_CAPACITY ||
            queuedChars + job.charCost > DecorationBacklogPolicy.PENDING_CHAR_COST_BUDGET
        ) {
            val dropped = dropLowestPriority() ?: return false
            queuedChars = (queuedChars - dropped.charCost).coerceAtLeast(0)
        }
        jobs.addLast(job)
        queuedChars += job.charCost
        highWaterCount = maxOf(highWaterCount, jobs.size)
        highWaterChars = maxOf(highWaterChars, queuedChars)
        return true
    }

    /** Keep only the latest streaming decoration candidate per block. */
    fun replaceStreamingJob(job: DecorationJob): Boolean {
        val existing =
            jobs.filter { candidate ->
                candidate.blockID == job.blockID && candidate.isStreaming
            }
        for (prior in existing) {
            jobs.remove(prior)
            queuedChars = (queuedChars - prior.charCost).coerceAtLeast(0)
        }
        return tryEnqueue(job)
    }

    fun poll(): DecorationJob? {
        val job = jobs.removeFirstOrNull() ?: return null
        queuedChars = (queuedChars - job.charCost).coerceAtLeast(0)
        return job
    }

    fun clear() {
        jobs.clear()
        queuedChars = 0
    }

    private fun dropLowestPriority(): DecorationJob? {
        if (jobs.isEmpty()) return null
        val index = jobs.withIndex().minBy { it.value.priority }.index
        return jobs.removeAt(index)
    }
}
