package dev.opencode.mobile.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.featureflags.SessionComposerStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeSessionComposerChromeTest {
    @Test
    fun `edge color moves a fifth of the way toward the text color`() {
        val blended = blendComposerEdge(base = Color(0f, 0f, 0f), toward = Color(1f, 0.5f, 0f))
        assertEquals(0.2f, blended.red, 0.01f)
        assertEquals(0.1f, blended.green, 0.01f)
        assertEquals(0f, blended.blue, 0.01f)
        assertEquals(1f, blended.alpha, 0.01f)
    }

    @Test
    fun `only outline fade reserves room for the fade`() {
        assertEquals(30.dp, openCodeComposerFadeClearance(SessionComposerStyle.OutlineFade))
        assertEquals(0.dp, openCodeComposerFadeClearance(SessionComposerStyle.Floating))
        assertEquals(0.dp, openCodeComposerFadeClearance(SessionComposerStyle.Docked))
    }
}
