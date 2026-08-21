package dev.opencode.mobile.ui.sessionchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkingTurnIndicatorTest {
    @Test
    fun labelForActiveTurn_nullWhenIdle() {
        assertNull(
            WorkingTurnIndicator.labelForActiveTurn(statusLabel = null),
        )
    }

    @Test
    fun labelForActiveTurn_showsWorkingWhenBusy() {
        assertEquals(
            "Working",
            WorkingTurnIndicator.labelForActiveTurn(statusLabel = "Working"),
        )
    }

    @Test
    fun labelForActiveTurn_showsRetrying() {
        assertEquals(
            "Retrying",
            WorkingTurnIndicator.labelForActiveTurn(statusLabel = "Retrying"),
        )
    }

    @Test
    fun labelForActiveTurn_hidesWhenAwaitingUser() {
        assertNull(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                isAwaitingUser = true,
            ),
        )
    }

    @Test
    fun labelForActiveTurn_hidesCompactingWhenAwaitingUser() {
        assertNull(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                isCompacting = true,
                isAwaitingUser = true,
            ),
        )
    }

    @Test
    fun labelForActiveTurn_showsCompactingEvenWhenStatusIsWorking() {
        assertEquals(
            SessionCompactChrome.STATUS_LABEL,
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                isCompacting = true,
            ),
        )
    }

    @Test
    fun labelForActiveTurn_showsCompactingWhenIdleStatus() {
        assertEquals(
            SessionCompactChrome.STATUS_LABEL,
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = null,
                isCompacting = true,
            ),
        )
    }
}
