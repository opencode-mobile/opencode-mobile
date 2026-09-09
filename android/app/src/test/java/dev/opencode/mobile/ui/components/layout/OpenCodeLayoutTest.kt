package dev.opencode.mobile.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeLayoutTest {
    @Test
    fun widthClassUsesCompactBelowBreakpoint() {
        assertEquals(OpenCodeLayoutWidth.Compact, OpenCodeLayoutWidth.fromWidth(599.dp))
        assertEquals(OpenCodeLayoutWidth.Compact, OpenCodeLayoutWidth.fromWidth(0.dp))
    }

    @Test
    fun widthClassUsesRegularAtBreakpoint() {
        assertEquals(OpenCodeLayoutWidth.Regular, OpenCodeLayoutWidth.fromWidth(600.dp))
        assertEquals(OpenCodeLayoutWidth.Regular, OpenCodeLayoutWidth.fromWidth(1024.dp))
    }

    @Test
    fun actionTileGridStaysTwoColumnsOnPhoneWidth() {
        assertEquals(2, OpenCodeLayout.actionTileColumnCount(390.dp))
        assertEquals(2, OpenCodeLayout.actionTileColumnCount(360.dp))
    }

    @Test
    fun actionTileGridGrowsOnWideSheet() {
        assertEquals(3, OpenCodeLayout.actionTileColumnCount(560.dp))
        assertEquals(4, OpenCodeLayout.actionTileColumnCount(720.dp))
    }
}
