package dev.opencode.mobile.domain.message

import dev.opencode.mobile.domain.session.SessionActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
