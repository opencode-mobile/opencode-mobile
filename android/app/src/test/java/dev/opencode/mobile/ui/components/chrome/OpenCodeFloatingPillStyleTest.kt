package dev.opencode.mobile.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeFloatingPillStyleTest {
    @Test
    fun liftedFill_liftsToWhiteOverAnOffWhitePage() {
        val raised = Color(0xFFF5F5F5)
        val page = Color(0xFFEDEDED)
        assertEquals(Color.White, OpenCodeFloatingPillStyle.liftedFill(raised, page, 1f))
    }

    @Test
    fun liftedFill_keepsRaisedSurfaceOverAWhitePage() {
        val raised = Color(0xFFF2F2F2)
        val page = Color.White
        assertEquals(raised, OpenCodeFloatingPillStyle.liftedFill(raised, page, 1f))
    }

    @Test
    fun liftedFill_liftsPartiallyOverADarkPage() {
        val raised = Color(0xFF202020)
        val page = Color(0xFF141414)
        val lifted = OpenCodeFloatingPillStyle.liftedFill(raised, page, 0.1f)
        assert(lifted.red > raised.red)
    }
}
