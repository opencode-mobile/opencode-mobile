package dev.opencode.mobile.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeBottomSheetLayoutTest {
    private val header = 64.dp

    private fun mode(
        height: OpenCodeBottomSheetHeight = OpenCodeBottomSheetHeight.FitContent,
        committed: Boolean = false,
        content: Dp,
        pinnedTop: Dp = 0.dp,
        pinnedBottom: Dp = 0.dp,
        headerHeight: Dp = header,
    ): BottomSheetLayoutMode =
        openCodeBottomSheetLayoutMode(
            height = height,
            committedToScrollable = committed,
            headerHeight = headerHeight,
            pinnedTopHeight = pinnedTop,
            contentHeight = content,
            pinnedBottomHeight = pinnedBottom,
        )

    @Test
    fun half_isScrollableFromTheFirstFrame() {
        assertEquals(BottomSheetLayoutMode.Scrollable, mode(height = OpenCodeBottomSheetHeight.Half, content = 0.dp))
        assertEquals(BottomSheetLayoutMode.Scrollable, mode(height = OpenCodeBottomSheetHeight.Half, content = 40.dp))
    }

    @Test
    fun fitContent_measuresBeforeTheBodyHasAHeight() {
        assertEquals(BottomSheetLayoutMode.Measuring, mode(content = 0.dp))
    }

    @Test
    fun fitContent_staysCompactWhileTheSheetFitsTheThreshold() {
        // 64 header + 276 body + 20 bottom padding = 360, the compact threshold.
        assertEquals(BottomSheetLayoutMode.Compact, mode(content = 276.dp))
        assertEquals(BottomSheetLayoutMode.Scrollable, mode(content = 277.dp))
    }

    @Test
    fun fitContent_countsBothPinnedSlotsTowardTheMeasuredHeight() {
        assertEquals(BottomSheetLayoutMode.Compact, mode(content = 176.dp, pinnedTop = 40.dp, pinnedBottom = 60.dp))
        assertEquals(BottomSheetLayoutMode.Scrollable, mode(content = 176.dp, pinnedTop = 40.dp, pinnedBottom = 61.dp))
        assertEquals(BottomSheetLayoutMode.Scrollable, mode(content = 200.dp, pinnedBottom = 80.dp))
    }

    @Test
    fun fitContent_staysScrollableOnceCommitted() {
        assertEquals(BottomSheetLayoutMode.Scrollable, mode(committed = true, content = 40.dp))
        assertEquals(BottomSheetLayoutMode.Scrollable, mode(committed = true, content = 0.dp))
    }

    @Test
    fun fitContent_estimatesTheHeaderBeforeItLaysOut() {
        // Estimated header: 12 top + 8 bottom + 40 row = 60.
        assertEquals(BottomSheetLayoutMode.Compact, mode(content = 280.dp, headerHeight = 0.dp))
        assertEquals(BottomSheetLayoutMode.Scrollable, mode(content = 281.dp, headerHeight = 0.dp))
    }

    @Test
    fun measuredHeight_sumsHeaderPinnedSlotsAndBody() {
        assertEquals(
            (header + 40.dp + 120.dp + 72.dp).value,
            openCodeBottomSheetMeasuredHeight(header, 40.dp, 120.dp, 72.dp).value,
            0.01f,
        )
    }

    @Test
    fun halfHeight_isHalfTheWindowLessTheTopGap() {
        assertEquals(392f, openCodeBottomSheetHalfHeight(800.dp).value, 0.01f)
        // Multi-window: a shorter window gives a shorter sheet.
        assertEquals(192f, openCodeBottomSheetHalfHeight(400.dp).value, 0.01f)
    }
}
