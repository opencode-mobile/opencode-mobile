package dev.opencode.mobile.domain.event

import dev.opencode.mobile.domain.session.SessionStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDomainAdapterTest {
    private val adapter = EventDomainAdapter(Json { ignoreUnknownKeys = true })

    @Test
    fun parseEvent_readsWrappedPayload() {
        val event =
            adapter.parseEvent(
                """{"payload":{"type":"message.updated","properties":{"sessionID":"ses-1"}}}""",
            )

        assertEquals("message.updated", event?.type)
        assertEquals("ses-1", event?.sessionId)
    }

    @Test
    fun parseEvent_readsSessionIdFromMessageInfo() {
        val event =
            adapter.parseEvent(
                """{"payload":{"type":"message.updated","properties":{"info":{"sessionID":"ses-1","id":"msg-1","role":"user"}}}}""",
            )

        assertEquals("message.updated", event?.type)
        assertEquals("ses-1", event?.sessionId)
    }

    @Test
    fun parseEvent_readsSessionIdFromMessagePart() {
        val event =
            adapter.parseEvent(
                """{"payload":{"type":"message.part.updated","properties":{"part":{"sessionID":"ses-1","id":"prt-1","type":"text"}}}}""",
            )

        assertEquals("message.part.updated", event?.type)
        assertEquals("ses-1", event?.sessionId)
    }

    @Test
    fun parseEvent_readsMessagePartDelta() {
        val event =
            adapter.parseEvent(
                """{"payload":{"type":"message.part.delta","properties":{"sessionID":"ses-1","messageID":"msg-1","partID":"prt-1","field":"text","delta":"Hi"}}}""",
            )

        assertEquals("message.part.delta", event?.type)
        assertEquals("ses-1", event?.sessionId)
        assertTrue(event!!.triggersMessageRefresh)
    }

    @Test
    fun parseEvent_readsUnwrappedPayload() {
        val event =
            adapter.parseEvent(
                """{"type":"session.created","properties":{"sessionID":"ses-2"}}""",
            )

        assertEquals("session.created", event?.type)
        assertEquals("ses-2", event?.sessionId)
    }

    @Test
    fun statusLabel_mapsBusyAndRetry() {
        val busy = adapter.statusLabel(SessionStatus(sessionId = "1", raw = mapOf("type" to "busy")))
        val retry = adapter.statusLabel(SessionStatus(sessionId = "1", raw = mapOf("type" to "retry")))
        val idle = adapter.statusLabel(SessionStatus(sessionId = "1", raw = mapOf("type" to "idle")))

        assertEquals("Working", busy)
        assertEquals("Retrying", retry)
        assertNull(idle)
    }

    @Test
    fun sessionStatusFromEvent_readsBusyPayload() {
        val event =
            adapter.parseEvent(
                """{"payload":{"type":"session.status","properties":{"sessionID":"ses-1","status":{"type":"busy"}}}}""",
            )

        val status = adapter.sessionStatusFromEvent(event!!)
        assertEquals("ses-1", status?.sessionId)
        assertEquals("busy", status?.raw?.get("type"))
        assertEquals("Working", status?.let(adapter::statusLabel))
    }

    @Test
    fun sessionTitleUpdate_readsInfoTitle() {
        val event =
            adapter.parseEvent(
                """{"payload":{"type":"session.updated","properties":{"info":{"id":"ses-1","title":"Live title"}}}}""",
            )

        val update = adapter.sessionTitleUpdate(event!!)
        assertEquals("ses-1", update?.sessionId)
        assertEquals("Live title", update?.title)
    }

    @Test
    fun sessionTitleUpdate_ignoresNonUpdatedEvents() {
        val event =
            adapter.parseEvent(
                """{"payload":{"type":"session.created","properties":{"info":{"id":"ses-1","title":"New"}}}}""",
            )

        assertNull(adapter.sessionTitleUpdate(event!!))
    }
}
