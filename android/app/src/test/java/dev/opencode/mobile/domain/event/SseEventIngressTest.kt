package dev.opencode.mobile.domain.event

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SseEventIngressTest {
    private val ingress = SseEventIngress(EventDomainAdapter(Json { ignoreUnknownKeys = true }))

    @Test
    fun heartbeat_isParsedAndDropped() {
        val result = ingress.accept("""{"payload":{"type":"server.heartbeat","properties":{}}}""")
        assertEquals(listOf("server.heartbeat"), result.parsed.map { it.type })
        assertTrue(result.toEmit.isEmpty())
        assertFalse(result.parseFailed)
    }

    @Test
    fun serverConnected_emitsOnceUntilReset() {
        val first = ingress.accept("""{"payload":{"type":"server.connected","properties":{}}}""")
        val second = ingress.accept("""{"payload":{"type":"server.connected","properties":{}}}""")
        assertEquals(listOf("server.connected"), first.toEmit.map { it.type })
        assertTrue(second.toEmit.isEmpty())
        ingress.reset()
        val third = ingress.accept("""{"payload":{"type":"server.connected","properties":{}}}""")
        assertEquals(listOf("server.connected"), third.toEmit.map { it.type })
    }

    @Test
    fun duplicateIds_areDroppedUntilReset() {
        val line = """{"payload":{"id":"evt_1","type":"message.updated","properties":{"sessionID":"ses-1"}}}"""
        val first = ingress.accept(line)
        val second = ingress.accept(line)
        assertEquals(listOf("message.updated"), first.toEmit.map { it.type })
        assertTrue(second.toEmit.isEmpty())
        ingress.reset()
        val third = ingress.accept(line)
        assertEquals(listOf("message.updated"), third.toEmit.map { it.type })
    }

    @Test
    fun blankLine_isNotAParseFailure() {
        val result = ingress.accept("   ")
        assertTrue(result.parsed.isEmpty())
        assertTrue(result.toEmit.isEmpty())
        assertFalse(result.parseFailed)
    }

    @Test
    fun unparseableLine_emitsParseFailed() {
        val result = ingress.accept("""{"nope":true}""")
        assertTrue(result.parseFailed)
        assertEquals(listOf("sse.parse.failed"), result.toEmit.map { it.type })
    }

    @Test
    fun droppedUsageUpdated_isNotAParseFailure() {
        val result =
            ingress.accept(
                """{"type":"session.usage.updated","data":{"sessionID":"ses-1","tokens":{"input":120,"output":4}}}""",
            )
        assertTrue(result.parsed.isEmpty())
        assertTrue(result.toEmit.isEmpty())
        assertFalse(result.parseFailed)
    }

    @Test
    fun droppedUsageUpdated_v2Envelope_isNotAParseFailure() {
        val result =
            ingress.accept(
                """{"payload":{"type":"session.usage.updated","properties":{"sessionID":"ses-1","tokens":{"input":120,"output":4},"cost":0.01}}}""",
            )
        assertTrue(result.parsed.isEmpty())
        assertTrue(result.toEmit.isEmpty())
        assertFalse(result.parseFailed)
    }

    @Test
    fun projectedUsageUpdated_emitsMessageUpdated() {
        val result =
            ingress.accept(
                """{"type":"session.usage.updated","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","tokens":{"input":120,"output":4,"reasoning":0}}}""",
            )
        assertFalse(result.parseFailed)
        assertEquals(listOf("message.updated"), result.toEmit.map { it.type })
    }
}
