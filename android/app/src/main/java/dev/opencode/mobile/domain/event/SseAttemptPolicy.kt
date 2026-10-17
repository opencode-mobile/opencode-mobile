package dev.opencode.mobile.domain.event

import dev.opencode.mobile.data.remote.SseAttemptOutcome
import kotlin.random.Random

internal sealed interface SseRetryDecision {
    data class Retry(
        val delayMs: Long,
    ) : SseRetryDecision

    /** The server rejected the credential; show it, but keep retrying slowly in case auth recovers. */
    data class AuthFailed(
        val delayMs: Long,
    ) : SseRetryDecision
}

internal object SseAttemptPolicy {
    const val UNCONFIRMED_404_RETRY_MS = 1_000L

    fun decide(
        outcome: SseAttemptOutcome,
        protocolConfirmed: Boolean,
        attempt: Int,
        random: Random = Random.Default,
    ): SseRetryDecision =
        when (outcome) {
            SseAttemptOutcome.Cancelled,
            SseAttemptOutcome.Ended,
            is SseAttemptOutcome.Transport,
            -> SseRetryDecision.Retry(SseRetryBackoff.delayMs(attempt, random))
            is SseAttemptOutcome.Http ->
                when {
                    outcome.statusCode == 401 || outcome.statusCode == 403 ->
                        SseRetryDecision.AuthFailed(SseRetryBackoff.authDelayMs(attempt, random))
                    outcome.statusCode == 404 && !protocolConfirmed ->
                        SseRetryDecision.Retry(UNCONFIRMED_404_RETRY_MS)
                    else -> SseRetryDecision.Retry(SseRetryBackoff.delayMs(attempt, random))
                }
        }
}
