package dev.opencode.mobile.domain.message

import dev.opencode.mobile.domain.event.OpencodeEvent
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicLong

/**
 * Filters which foreground events enter MessageSync per-session staging.
 * Global consumers keep their own filters on the shared stream.
 */
object MessageEventRouter {
    val messageIntakeTypes: Set<String> =
        setOf(
            "message.updated",
            "message.part.updated",
            "message.part.delta",
            "message.removed",
            "message.part.removed",
            "session.idle",
        )

    fun shouldStageForMessageSync(event: OpencodeEvent): Boolean = event.type in messageIntakeTypes

    fun isIncrementalTranscriptEvent(event: OpencodeEvent): Boolean = event.type in incrementalTranscriptTypes

    private val incrementalTranscriptTypes: Set<String> =
        setOf(
            "message.updated",
            "message.part.updated",
            "message.part.delta",
            "message.removed",
            "message.part.removed",
        )

    /**
     * Cheap UTF-8 size proxy for staging byte budgets. Never inspects full
     * message bodies beyond the delta/text fields already on the event.
     */
    fun estimatedByteCost(event: OpencodeEvent): Int {
        var cost = event.type.length + (event.sessionId?.length ?: 0)
        val props = event.rawProperties
        props?.get("delta")?.jsonPrimitive?.contentOrNull?.let { cost += it.length }
        props
            ?.get("part")
            ?.jsonObject
            ?.get("text")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.let { cost += it.length }
        return maxOf(64, cost)
    }
}

/** Monotonic foreground-connection generation. */
class ConnectionEpoch {
    private val value = AtomicLong(0)

    fun current(): Long = value.get()

    fun bump(): Long = value.incrementAndGet()

    companion object {
        val shared = ConnectionEpoch()
    }
}

internal const val MESSAGE_EVENT_STAGING_BYTE_CAPACITY = 2_000_000
