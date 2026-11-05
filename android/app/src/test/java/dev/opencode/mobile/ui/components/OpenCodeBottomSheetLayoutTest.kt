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
        compactMaxHeight: Dp = BottomSheetCompactMaxHeight,
    ): BottomSheetLayoutMode =
        openCodeBottomSheetLayoutMode(
            height = height,
            committedToScrollable = committed,
            headerHeight = headerHeight,
            pinnedTopHeight = pinnedTop,
            contentHeight = content,
            pinnedBottomHeight = pinnedBottom,
            compactMaxHeight = compactMaxHeight,
        )

    @Test
    fun fitAllMaxHeight_isTheWindowLessTopInsetTopGapAndBottomInset() {
        // 800 window - 24 status bar - 8 top gap - 8 bottom inset = 760.
        assertEquals(760f, openCodeBottomSheetFitAllMaxHeight(800.dp, 24.dp, 8.dp).value, 0.01f)
        // Three-button navigation pads the bottom inset by the bar: 800 - 24 - 8 - (48 + 8) = 712.
        assertEquals(712f, openCodeBottomSheetFitAllMaxHeight(800.dp, 24.dp, 56.dp).value, 0.01f)
    }

    @Test
    fun fitAllMaxHeight_neverDropsBelowAHalfHeightSheet() {
        // A cramped window still rests at half height (192 column + 20 bottom padding).
        assertEquals(212f, openCodeBottomSheetFitAllMaxHeight(400.dp, 200.dp, 100.dp).value, 0.01f)
    }

    @Test
    fun fitAllContent_staysCompactPastTheFitContentThresholdUpToTheWindowLimit() {
        val limit = openCodeBottomSheetFitAllMaxHeight(800.dp, 24.dp, 8.dp)
        val fitAll = OpenCodeBottomSheetHeight.FitAllContent
        // 64 header + 500 body + 20 bottom padding = 584: past 420, still under the 760 limit.
        assertEquals(BottomSheetLayoutMode.Scrollable, mode(content = 500.dp))
        assertEquals(BottomSheetLayoutMode.Compact, mode(height = fitAll, content = 500.dp, compactMaxHeight = limit))
        // 64 + 676 + 20 = 760 fits exactly; one more dp scrolls.
        assertEquals(BottomSheetLayoutMode.Compact, mode(height = fitAll, content = 676.dp, compactMaxHeight = limit))
        assertEquals(BottomSheetLayoutMode.Scrollable, mode(height = fitAll, content = 677.dp, compactMaxHeight = limit))
    }

    @Test
    fun fitAllContent_measuresFirstAndStaysScrollableOnceCommitted() {
        val fitAll = OpenCodeBottomSheetHeight.FitAllContent
        assertEquals(BottomSheetLayoutMode.Measuring, mode(height = fitAll, content = 0.dp, compactMaxHeight = 760.dp))
        assertEquals(
            BottomSheetLayoutMode.Scrollable,
            mode(height = fitAll, committed = true, content = 40.dp, compactMaxHeight = 760.dp),
        )
    }

    @Test
    fun fitAllContent_countsPinnedSlotsTowardTheLimit() {
        val fitAll = OpenCodeBottomSheetHeight.FitAllContent
        // 64 + 40 + 500 + 136 + 20 = 760.
        assertEquals(
            BottomSheetLayoutMode.Compact,
            mode(height = fitAll, content = 500.dp, pinnedTop = 40.dp, pinnedBottom = 136.dp, compactMaxHeight = 760.dp),
        )
        assertEquals(
            BottomSheetLayoutMode.Scrollable,
            mode(height = fitAll, content = 500.dp, pinnedTop = 40.dp, pinnedBottom = 137.dp, compactMaxHeight = 760.dp),
        )
    }

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
        // 64 header + 336 body + 20 bottom padding = 420, the compact threshold shared with iOS.
        assertEquals(BottomSheetLayoutMode.Compact, mode(content = 336.dp))
        assertEquals(BottomSheetLayoutMode.Scrollable, mode(content = 337.dp))
    }

    @Test
    fun fitContent_countsBothPinnedSlotsTowardTheMeasuredHeight() {
        assertEquals(BottomSheetLayoutMode.Compact, mode(content = 236.dp, pinnedTop = 40.dp, pinnedBottom = 60.dp))
        assertEquals(BottomSheetLayoutMode.Scrollable, mode(content = 236.dp, pinnedTop = 40.dp, pinnedBottom = 61.dp))
        assertEquals(BottomSheetLayoutMode.Scrollable, mode(content = 260.dp, pinnedBottom = 80.dp))
    }

    @Test
    fun fitContent_staysScrollableOnceCommitted() {
        assertEquals(BottomSheetLayoutMode.Scrollable, mode(committed = true, content = 40.dp))
        assertEquals(BottomSheetLayoutMode.Scrollable, mode(committed = true, content = 0.dp))
    }

    @Test
    fun fitContent_estimatesTheHeaderBeforeItLaysOut() {
        // Estimated header: 12 top + 8 bottom + 40 row = 60.
        assertEquals(BottomSheetLayoutMode.Compact, mode(content = 340.dp, headerHeight = 0.dp))
        assertEquals(BottomSheetLayoutMode.Scrollable, mode(content = 341.dp, headerHeight = 0.dp))
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

    @Test
    fun fitContent_followsAShrinkingBodyWhileCompact() {
        // A compact sheet is not latched: a shorter body (Commands to MCP) stays compact.
        assertEquals(BottomSheetLayoutMode.Compact, mode(content = 300.dp))
        assertEquals(BottomSheetLayoutMode.Compact, mode(content = 120.dp))
    }

    @Test
    fun compactThreshold_matchesIos() {
        assertEquals(420f, BottomSheetCompactMaxHeight.value, 0.01f)
    }

    @Test
    fun dragDismisses_pastTheDistanceOrOnAFastFling() {
        val distance = 96f
        val velocity = 1_200f
        assertEquals(false, openCodeBottomSheetDragDismisses(95f, 0f, distance, velocity))
        assertEquals(true, openCodeBottomSheetDragDismisses(96f, 0f, distance, velocity))
        assertEquals(true, openCodeBottomSheetDragDismisses(10f, 1_200f, distance, velocity))
        assertEquals(false, openCodeBottomSheetDragDismisses(10f, 1_199f, distance, velocity))
        // An upward fling never dismisses.
        assertEquals(false, openCodeBottomSheetDragDismisses(10f, -3_000f, distance, velocity))
    }

    @Test
    fun centeredScroll_centersTheRowAndNeverScrollsAboveTheTop() {
        // Row 48 tall at 600 in a 300 viewport: center 624 sits at 150.
        assertEquals(474f, openCodeBottomSheetCenteredScroll(600f, 48f, 300f), 0.01f)
        assertEquals(0f, openCodeBottomSheetCenteredScroll(40f, 48f, 300f), 0.01f)
    }
}
