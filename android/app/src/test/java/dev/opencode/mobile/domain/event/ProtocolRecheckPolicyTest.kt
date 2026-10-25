package dev.opencode.mobile.domain.event

import dev.opencode.mobile.data.remote.SseAttemptOutcome
import dev.opencode.mobile.domain.connection.ProtocolRedetection
import dev.opencode.mobile.domain.connection.ServerProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ProtocolRecheckPolicyTest {
    @Test
    fun httpErrorOnEventPath_suggestsProtocolChange() {
        // opencode v2 answers the v1 event paths with 500.
        assertTrue(suggests(SseAttemptOutcome.Http(500)))
        assertTrue(suggests(SseAttemptOutcome.Http(404)))
    }

    @Test
    fun rejectedCredential_isNotAProtocolChange() {
        assertFalse(suggests(SseAttemptOutcome.Http(401)))
        assertFalse(suggests(SseAttemptOutcome.Http(403)))
    }

    @Test
    fun transportFailureBeforeAnyResponse_isAnOutage() {
        assertFalse(suggests(SseAttemptOutcome.Transport(IOException("refused"))))
        assertFalse(suggests(SseAttemptOutcome.Cancelled))
    }

    @Test
    fun openedWithoutServerConnected_suggestsProtocolChange() {
        assertTrue(suggests(SseAttemptOutcome.Transport(null), httpOpened = true))
        assertTrue(suggests(SseAttemptOutcome.Ended, httpOpened = true))
    }

    @Test
    fun streamThatWentLiveAndDropped_isNotAProtocolSignal() {
        assertFalse(suggests(SseAttemptOutcome.Ended, httpOpened = true, serverConnected = true))
        assertFalse(suggests(SseAttemptOutcome.Transport(null), httpOpened = true, serverConnected = true))
    }

    @Test
    fun isDue_throttlesToOneRecheckPerWindow() {
        assertTrue(ProtocolRecheckPolicy.isDue(nowMs = 1_000, lastRecheckMs = null))
        assertFalse(ProtocolRecheckPolicy.isDue(nowMs = 10_000, lastRecheckMs = 1_000))
        assertTrue(ProtocolRecheckPolicy.isDue(nowMs = 16_000, lastRecheckMs = 1_000))
    }

    @Test
    fun action_retargetsOnlyWhenTheDetectedProtocolDiffers() {
        assertEquals(
            ProtocolRecheckAction.Retarget(ServerProtocol.V2),
            ProtocolRecheckPolicy.action(ProtocolRedetection.Detected(ServerProtocol.V2), ServerProtocol.V1),
        )
        assertEquals(
            ProtocolRecheckAction.Retarget(ServerProtocol.V1),
            ProtocolRecheckPolicy.action(ProtocolRedetection.Detected(ServerProtocol.V1), ServerProtocol.V2),
        )
        assertEquals(
            ProtocolRecheckAction.KeepProtocol,
            ProtocolRecheckPolicy.action(ProtocolRedetection.Detected(ServerProtocol.V1), ServerProtocol.V1),
        )
    }

    @Test
    fun action_marksUnreachableOnlyWhenNoProtocolAnswered() {
        assertEquals(
            ProtocolRecheckAction.MarkUnreachable,
            ProtocolRecheckPolicy.action(ProtocolRedetection.Unreachable, ServerProtocol.V1),
        )
        assertEquals(
            ProtocolRecheckAction.None,
            ProtocolRecheckPolicy.action(ProtocolRedetection.Skipped, ServerProtocol.V1),
        )
    }

    private fun suggests(
        outcome: SseAttemptOutcome,
        httpOpened: Boolean = false,
        serverConnected: Boolean = false,
    ): Boolean = ProtocolRecheckPolicy.suggestsProtocolChange(outcome, httpOpened, serverConnected)
}
