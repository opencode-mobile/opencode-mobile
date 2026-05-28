package dev.opencode.mobile.domain.event

import dev.opencode.mobile.domain.session.SessionStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
