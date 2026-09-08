package dev.opencode.mobile.ui.sessionchat

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCompactChromeTest {
    @Test
    fun successMessage_formatsDeltaWhenBothFractionsPresent() {
        assertEquals(
            "Context compacted · 72% → 34%",
            SessionCompactChrome.successMessage(beforeFraction = 0.72f, afterFraction = 0.34f),
        )
    }

    @Test
    fun successMessage_fallsBackWhenEitherFractionMissing() {
        assertEquals(
            SessionCompactChrome.FALLBACK_SUCCESS,
            SessionCompactChrome.successMessage(beforeFraction = 0.72f, afterFraction = null),
        )
        assertEquals(
            SessionCompactChrome.FALLBACK_SUCCESS,
            SessionCompactChrome.successMessage(beforeFraction = null, afterFraction = 0.34f),
        )
    }
}
