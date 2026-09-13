package dev.opencode.mobile.domain.event

import dev.opencode.mobile.data.remote.SseAttemptOutcome
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SseLeaseAttemptTest {
    private val adapter = EventDomainAdapter(Json { ignoreUnknownKeys = true })

    @Test
    fun http2xxOpensWithoutConnecting() {
        val attempt = SseLeaseAttempt()
        assertTrue(attempt.onHttpStatus(200))
        assertTrue(attempt.httpOpened)
        assertFalse(attempt.serverConnected)
    }

    @Test
    fun http401DoesNotOpen() {
        val attempt = SseLeaseAttempt()
        assertFalse(attempt.onHttpStatus(401))
        assertFalse(attempt.httpOpened)
    }

    @Test
    fun firstFrameAcceptsV1WrappedServerConnected() {
        val attempt = SseLeaseAttempt()
        val events =
            adapter.parseEvents(
                """{"payload":{"type":"server.connected","properties":{}}}""",
            )
        assertEquals(SseFirstFrameResult.Connected, attempt.onFirstDataEvents(events))
        assertTrue(attempt.serverConnected)
    }

    @Test
    fun firstFrameAcceptsV2DirectServerConnected() {
        val attempt = SseLeaseAttempt()
        val events = adapter.parseEvents("""{"type":"server.connected","data":{}}""")
        assertEquals(SseFirstFrameResult.Connected, attempt.onFirstDataEvents(events))
        assertTrue(attempt.serverConnected)
    }

    @Test
    fun firstFrameRejectsHeartbeatAndLaterConnected() {
        val attempt = SseLeaseAttempt()
        val heartbeat =
            adapter.parseEvents(
                """{"payload":{"type":"server.heartbeat","properties":{}}}""",
            )
        assertEquals(SseFirstFrameResult.Rejected, attempt.onFirstDataEvents(heartbeat))
        val connected =
            adapter.parseEvents(
                """{"type":"server.connected","data":{}}""",
            )
        assertEquals(SseFirstFrameResult.Subsequent, attempt.onFirstDataEvents(connected))
        assertFalse(attempt.serverConnected)
    }

    @Test
    fun emptyParseIsRejected() {
        val attempt = SseLeaseAttempt()
        assertEquals(SseFirstFrameResult.Rejected, attempt.onFirstDataEvents(emptyList()))
        assertFalse(attempt.serverConnected)
    }

    @Test
    fun endedWithoutConnectedBecomesTransport() {
        val attempt = SseLeaseAttempt()
        attempt.onHttpStatus(200)
        assertEquals(
            SseAttemptOutcome.Transport(null),
            attempt.terminalOutcome(SseAttemptOutcome.Ended),
        )
    }

    @Test
    fun httpOutcomeStaysHttpWhenNotConnected() {
        val attempt = SseLeaseAttempt()
        assertEquals(
            SseAttemptOutcome.Http(404),
            attempt.terminalOutcome(SseAttemptOutcome.Http(404)),
        )
    }
}
