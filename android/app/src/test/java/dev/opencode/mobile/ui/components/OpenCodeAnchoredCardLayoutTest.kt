package dev.opencode.mobile.ui.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import dev.opencode.mobile.theme.OpenCodeLargeShape
import dev.opencode.mobile.theme.OpenCodeRadius
import dev.opencode.mobile.ui.sessionchat.view.SessionChatCornerSlotsLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OpenCodeAnchoredCardLayoutTest {
    private val pill = IntRect(left = 42, top = 300, right = 330, bottom = 394)

    @Test
    fun card_linesUpWithThePillsLeadingEdge_gapBelow() {
        assertEquals(IntOffset(42, 394 + 21), OpenCodeAnchoredCardLayout.cardOffset(pill, gap = 21))
    }

    @Test
    fun gap_matchesTheGapBetweenCornerPills() {
        assertEquals(SessionChatCornerSlotsLayout.Spacing, OpenCodeAnchoredCardLayout.Gap)
    }

    @Test
    fun width_isPreferredWhenItFits_elseKeepsTheEdgeInset() {
        assertEquals(840, OpenCodeAnchoredCardLayout.cardWidth(anchorLeft = 42, windowWidth = 1080, preferredWidth = 840, edgeInset = 42))
        assertEquals(1080 - 2 * 42, OpenCodeAnchoredCardLayout.cardWidth(anchorLeft = 42, windowWidth = 1080, preferredWidth = 2000, edgeInset = 42))
        // A pill further in (after todos) keeps the same screen-edge inset.
        assertEquals(1080 - 400 - 42, OpenCodeAnchoredCardLayout.cardWidth(anchorLeft = 400, windowWidth = 1080, preferredWidth = 2000, edgeInset = 42))
        assertEquals(0, OpenCodeAnchoredCardLayout.cardWidth(anchorLeft = 1060, windowWidth = 1080, preferredWidth = 840, edgeInset = 42))
    }

    @Test
    fun edgeInset_matchesTheCornerSlotInset() {
        assertEquals(SessionChatCornerSlotsLayout.HorizontalPadding, OpenCodeAnchoredCardLayout.EdgeInset)
    }

    @Test
    fun shape_isTheLargeTokenMatchingThePillCapsule() {
        assertSame(OpenCodeLargeShape, OpenCodeAnchoredCardLayout.Shape)
        assertEquals(FloatingPillMinHeight / 2, OpenCodeRadius.large)
    }
}
