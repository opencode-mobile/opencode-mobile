package dev.opencode.mobile.domain.event

import dev.opencode.mobile.data.remote.SseAttemptOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SseAttemptPolicyTest {
    @Test
    fun http401And403AreAuthFailedWithSlowRetry() {
        assertEquals(
            SseRetryDecision.AuthFailed(SseRetryBackoff.authDelayMs(1, Random(5))),
            SseAttemptPolicy.decide(SseAttemptOutcome.Http(401), protocolConfirmed = true, attempt = 1, random = Random(5)),
        )
        assertEquals(
            SseRetryDecision.AuthFailed(SseRetryBackoff.authDelayMs(4, Random(6))),
            SseAttemptPolicy.decide(SseAttemptOutcome.Http(403), protocolConfirmed = false, attempt = 4, random = Random(6)),
        )
    }

    @Test
    fun http404FastRetriesOnlyWhileProtocolUnconfirmed() {
        val unconfirmed =
            SseAttemptPolicy.decide(
                SseAttemptOutcome.Http(404),
                protocolConfirmed = false,
                attempt = 5,
                random = Random(0),
            )
        assertEquals(SseRetryDecision.Retry(SseAttemptPolicy.UNCONFIRMED_404_RETRY_MS), unconfirmed)

        val confirmed =
            SseAttemptPolicy.decide(
                SseAttemptOutcome.Http(404),
                protocolConfirmed = true,
                attempt = 3,
                random = Random(1),
            )
        val expected = SseRetryBackoff.delayMs(3, Random(1))
        assertEquals(SseRetryDecision.Retry(expected), confirmed)
        assertTrue((confirmed as SseRetryDecision.Retry).delayMs != SseAttemptPolicy.UNCONFIRMED_404_RETRY_MS)
    }

    @Test
    fun transportAndEndedUseBackoff() {
        val random = Random(2)
        val expected = SseRetryBackoff.delayMs(2, Random(2))
        assertEquals(
            SseRetryDecision.Retry(expected),
            SseAttemptPolicy.decide(
                SseAttemptOutcome.Transport(null),
                protocolConfirmed = true,
                attempt = 2,
                random = random,
            ),
        )
        assertEquals(
            SseRetryDecision.Retry(SseRetryBackoff.delayMs(1, Random(3))),
            SseAttemptPolicy.decide(
                SseAttemptOutcome.Ended,
                protocolConfirmed = false,
                attempt = 1,
                random = Random(3),
            ),
        )
    }

    @Test
    fun otherHttpUsesBackoff() {
        val decision =
            SseAttemptPolicy.decide(
                SseAttemptOutcome.Http(500),
                protocolConfirmed = false,
                attempt = 1,
                random = Random(4),
            )
        assertEquals(SseRetryDecision.Retry(SseRetryBackoff.delayMs(1, Random(4))), decision)
    }
}
