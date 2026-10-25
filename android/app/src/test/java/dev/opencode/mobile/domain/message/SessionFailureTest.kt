package dev.opencode.mobile.domain.message

import dev.opencode.mobile.domain.session.SessionActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFailureTest {
    @Test
    fun actionsFollowTheErrorType() {
        assertNull(failure("provider.auth").action())
        assertNull(failure("provider.auth").actionLabel())
        assertEquals(FailureAction.PickModel, failure("provider.no-route").action())
        assertEquals(FailureAction.Copy, failure("unknown").action())
        assertEquals(FailureAction.Copy, failure("provider.unknown").action())
        assertNull(failure("provider.content-filter").action())
        assertNull(failure("provider.rate-limit").action())
        assertNull(failure("permission.rejected").action())
        assertNull(failure("compaction.failed").action())
    }

    @Test
    fun usageLimitTypesMapToTheirActions() {
        assertEquals(FailureAction.PickModel, failure("provider.free-limit").action())
        assertEquals("Pick a model", failure("provider.free-limit").actionLabel())
        assertEquals(FailureAction.PickModel, failure("provider.model-budget-limit").action())
        assertNull(failure("provider.budget-limit").action())
        assertFalse(failure("provider.free-limit").coversLiveRetry())
        assertFalse(failure("provider.budget-limit").coversLiveRetry())
    }

    @Test
    fun retryAtCountsFromTheRecordedTime() {
        val limited =
            SessionFailure.fromV2(
                type = "provider.free-limit",
                message = "Free usage limit reached.",
                status = 429,
                providerBody = null,
                retryAfterMs = 30_000L,
                recordedAtEpochMs = 1_000L,
            )
        assertEquals(30_000L, limited?.retryAfterMs)
        assertEquals(31_000L, limited?.retryAtEpochMs())
        assertEquals("HTTP 429 · Free usage limit reached.", limited?.line())
    }

    @Test
    fun noRetryAfterMeansNoRetryTime() {
        val plain =
            SessionFailure.fromV2(
                type = "provider.rate-limit",
                message = "Slow down",
                status = 429,
                providerBody = null,
                recordedAtEpochMs = 1_000L,
            )
        assertNull(plain?.retryAfterMs)
        assertNull(plain?.recordedAtEpochMs)
        assertNull(plain?.retryAtEpochMs())
        assertEquals(SessionFailure(type = "provider.rate-limit", message = "Slow down", status = 429), plain)

        val v1 = SessionFailure.fromV1(name = "APIError", message = "Slow down", statusCode = 429, responseBody = null)
        assertNull(v1?.retryAtEpochMs())
        assertNull(failure("provider.quota").retryAtEpochMs())
    }

    @Test
    fun negativeRetryAfterIsIgnored() {
        val failure =
            SessionFailure.fromV2(
                type = "provider.quota",
                message = "Quota",
                status = null,
                providerBody = null,
                retryAfterMs = -5L,
                recordedAtEpochMs = 1_000L,
            )
        assertNull(failure?.retryAtEpochMs())
    }

    @Test
    fun retryAfterLabelUsesSecondsThenMinutes() {
        assertEquals("Try again in 1 s", SessionFailure.retryAfterLabel(1L))
        assertEquals("Try again in 12 s", SessionFailure.retryAfterLabel(11_200L))
        assertEquals("Try again in 90 s", SessionFailure.retryAfterLabel(90_000L))
        assertEquals("Try again in 2 min", SessionFailure.retryAfterLabel(90_001L))
        assertEquals("Try again in 4 min", SessionFailure.retryAfterLabel(240_000L))
        assertEquals("Try again in 60 min", SessionFailure.retryAfterLabel(3_600_000L))
        assertNull(SessionFailure.retryAfterLabel(0L))
        assertNull(SessionFailure.retryAfterLabel(-1_000L))
    }

    @Test
    fun rateLimitAndQuotaAreCoveredByTheLiveRetry() {
        assertTrue(failure("provider.rate-limit").coversLiveRetry())
        assertTrue(failure(" Provider.Quota ").coversLiveRetry())
        assertFalse(failure("provider.auth").coversLiveRetry())
        assertFalse(failure("provider.content-filter").coversLiveRetry())
        assertFalse(failure("provider.no-route").coversLiveRetry())
    }

    @Test
    fun contextOverflowOffersCompact() {
        assertEquals(
            FailureAction.Compact,
            failure("ContextOverflowError", message = "Context overflow").action(),
        )
        assertEquals(
            FailureAction.Compact,
            failure(
                "provider.invalid-request",
                message = "the context is too long",
                body = "max length",
            ).action(),
        )
        assertNull(failure("provider.invalid-request", message = "bad field").action())
    }

    @Test
    fun contentFilterHidesTheRetryCard() {
        val hidden =
            SessionActivity.Retrying(
                attempt = 1,
                nextAtMs = 5_000L,
                message = "blocked",
                errorType = "provider.content-filter",
            ).retryCard()
        assertNull(hidden)

        val shown =
            SessionActivity.Retrying(
                attempt = 2,
                nextAtMs = 5_000L,
                message = "  Slow down please, this reason is longer than eighty characters and must be cut off here now ",
                errorType = "provider.rate-limit",
                actionLabel = " Upgrade ",
                actionLink = " https://example.com ",
            ).retryCard()
        assertEquals(2, shown?.attempt)
        assertEquals(5_000L, shown?.nextAtMs)
        assertEquals(80, shown?.message?.length)
        assertEquals("Upgrade", shown?.actionLabel)
        assertEquals("https://example.com", shown?.actionLink)
    }

    private fun failure(
        type: String,
        message: String = "failed",
        body: String? = null,
    ) = SessionFailure(type = type, message = message, providerBody = body)
}
