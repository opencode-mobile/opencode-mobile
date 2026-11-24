package dev.opencode.mobile.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeSessionComposerChromeTest {
    @Test
    fun `edge color moves 12 percent of the way toward the text color`() {
        val blended = blendComposerEdge(base = Color(0f, 0f, 0f), toward = Color(1f, 0.5f, 0f))
        assertEquals(0.12f, blended.red, 0.01f)
        assertEquals(0.06f, blended.green, 0.01f)
        assertEquals(0f, blended.blue, 0.01f)
        assertEquals(1f, blended.alpha, 0.01f)
    }

    @Test
    fun `fade fits the resting gap and grows away from the end`() {
        assertEquals(OpenCodeBottomFadeOverhang, openCodeComposerFadeHeight(awayFromEnd = false))
        assertEquals(46.dp, openCodeComposerFadeHeight(awayFromEnd = true))
    }
}
