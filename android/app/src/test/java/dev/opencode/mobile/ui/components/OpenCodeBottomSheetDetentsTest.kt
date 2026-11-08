package dev.opencode.mobile.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeBottomSheetDetentsTest {
    private val low = 400f
    private val halfStop = low
    private val high = 760f
    private val fullStop = high
    private val flick = 900f

    private fun settle(
        extent: Float,
        velocity: Float = 0f,
        lowStop: Float = low,
        highStop: Float = high,
    ): Float? = openCodeBottomSheetSettleTarget(extent, velocity, lowStop, highStop, flick)

    @Test
    fun settle_restsOnTheNearerStop() {
        assertEquals(low, settle(400f))
        assertEquals(low, settle(579f))
        assertEquals(high, settle(580f))
        assertEquals(high, settle(760f))
        // Pulled a little below half, it springs back up.
        assertEquals(low, settle(241f))
    }

    @Test
    fun settle_closesBelowSixtyPercentOfTheLowerStop() {
        assertEquals(low, settle(241f))
        assertNull(settle(239f))
        assertNull(settle(0f))
    }

    @Test
    fun settle_flickUpExpandsFromAnywhere() {
        assertEquals(high, settle(400f, velocity = -901f))
        assertEquals(high, settle(120f, velocity = -2_000f))
        // At the flick speed exactly it is not a flick: the nearer stop wins.
        assertEquals(low, settle(420f, velocity = -900f))
    }

    @Test
    fun settle_flickDownDropsFromFullToHalfAndFromHalfCloses() {
        assertEquals(low, settle(760f, velocity = 901f))
        assertEquals(low, settle(500f, velocity = 3_000f))
        assertNull(settle(401f, velocity = 901f))
        assertNull(settle(400f, velocity = 901f))
        assertNull(settle(380f, velocity = 1_500f))
    }

    @Test
    fun settle_withOneStopOnlyRestsThereOrCloses() {
        // A compact sheet: its fitted height is both stops.
        assertEquals(208f, settle(208f, lowStop = 208f, highStop = 208f))
        assertEquals(208f, settle(168f, lowStop = 208f, highStop = 208f))
        assertNull(settle(124f, lowStop = 208f, highStop = 208f))
        assertNull(settle(208f, velocity = 1_000f, lowStop = 208f, highStop = 208f))
        assertEquals(208f, settle(150f, velocity = -1_000f, lowStop = 208f, highStop = 208f))
    }

    @Test
    fun stops_halfAndScrollingFitContentRestAtHalfAndExpandToFull() {
        listOf(OpenCodeBottomSheetHeight.Half, OpenCodeBottomSheetHeight.FitContent).forEach { height ->
            assertEquals(
                BottomSheetStops(392.dp, 740.dp),
                openCodeBottomSheetStops(height, BottomSheetLayoutMode.Scrollable, 200.dp, 392.dp, 740.dp),
            )
        }
        // A cramped window never puts full below half.
        assertEquals(
            BottomSheetStops(192.dp, 192.dp),
            openCodeBottomSheetStops(OpenCodeBottomSheetHeight.Half, BottomSheetLayoutMode.Scrollable, 0.dp, 192.dp, 150.dp),
        )
    }

    @Test
    fun stops_compactAndFitAllSheetsHaveOneStop() {
        listOf(OpenCodeBottomSheetHeight.FitContent, OpenCodeBottomSheetHeight.FitAllContent).forEach { height ->
            assertEquals(
                BottomSheetStops(208.dp, 208.dp),
                openCodeBottomSheetStops(height, BottomSheetLayoutMode.Compact, 208.dp, 392.dp, 740.dp),
            )
            assertNull(openCodeBottomSheetStops(height, BottomSheetLayoutMode.Measuring, 0.dp, 392.dp, 740.dp))
        }
        assertEquals(
            BottomSheetStops(740.dp, 740.dp),
            openCodeBottomSheetStops(OpenCodeBottomSheetHeight.FitAllContent, BottomSheetLayoutMode.Scrollable, 900.dp, 392.dp, 740.dp),
        )
    }

    private fun detents(): BottomSheetDetents =
        BottomSheetDetents(CoroutineScope(Dispatchers.Unconfined)).apply {
            flickVelocity = flick
            setStops(halfStop, fullStop, presentation = 1)
        }

    private fun BottomSheetDetents.preScroll(dy: Float): Float = nestedScroll.onPreScroll(Offset(0f, dy), NestedScrollSource.UserInput).y

    private fun BottomSheetDetents.postScroll(dy: Float): Float =
        nestedScroll.onPostScroll(Offset.Zero, Offset(0f, dy), NestedScrollSource.UserInput).y

    @Test
    fun detents_openAtTheLowerStop_andSlideBelowIt() {
        val sheet = detents()
        assertEquals(low, sheet.layoutHeight)
        assertEquals(0f, sheet.offset)
        assertTrue(sheet.expandable)

        sheet.drag(100f)
        assertEquals(300f, sheet.extent)
        // Below half the column keeps its height and slides down instead.
        assertEquals(low, sheet.layoutHeight)
        assertEquals(100f, sheet.offset)

        sheet.drag(-260f)
        assertEquals(560f, sheet.layoutHeight)
        assertEquals(0f, sheet.offset)
        // It never grows past the higher stop.
        sheet.drag(-1_000f)
        assertEquals(high, sheet.extent)
    }

    @Test
    fun nestedScroll_growsTheSheetBeforeTheBodyScrolls() {
        val sheet = detents()
        // Finger up 100: the sheet takes all of it.
        assertEquals(-100f, sheet.preScroll(-100f))
        assertEquals(500f, sheet.extent)
        // Up 400 more: the sheet takes the 260 left to full, the body gets the rest.
        assertEquals(-260f, sheet.preScroll(-400f))
        assertEquals(high, sheet.extent)
        assertEquals(0f, sheet.preScroll(-50f))
        // Down is the body's first; the sheet takes only what the body leaves at its top.
        assertEquals(0f, sheet.preScroll(80f))
        assertEquals(80f, sheet.postScroll(80f))
        assertEquals(680f, sheet.extent)
    }

    @Test
    fun nestedScroll_ignoresFlingsAndLetsAFullSheetFlingItsBody() =
        runBlocking {
            val sheet = detents()
            assertEquals(0f, sheet.nestedScroll.onPreScroll(Offset(0f, -100f), NestedScrollSource.SideEffect).y)
            assertEquals(low, sheet.layoutHeight)

            sheet.preScroll(-1_000f)
            assertEquals(Velocity.Zero, sheet.nestedScroll.onPreFling(Velocity(0f, -3_000f)))
            assertTrue(sheet.restsHigh)
        }

    @Test
    fun nestedScroll_releasedMidwayTakesTheVelocityAndSettles() =
        runBlocking {
            var dismissed = false
            val sheet = detents().apply { onDismiss = { dismissed = true } }
            sheet.postScroll(250f)
            val velocity = Velocity(0f, 1_200f)
            assertEquals(velocity, sheet.nestedScroll.onPreFling(velocity))
            assertTrue("flicked down from half closes", dismissed)
        }

    @Test
    fun detents_aCompactSheetOnlySlidesDown() {
        val sheet =
            BottomSheetDetents(CoroutineScope(Dispatchers.Unconfined)).apply { setStops(208f, 208f, presentation = 1) }
        assertFalse(sheet.expandable)
        assertEquals(0f, sheet.preScroll(-100f))
        assertEquals(208f, sheet.layoutHeight)
        assertEquals(60f, sheet.postScroll(60f))
        assertEquals(60f, sheet.offset)
    }

    @Test
    fun detents_aNewPresentationTakesItsFirstStopsAtOnce() {
        val sheet = detents()
        sheet.drag(-1_000f)
        sheet.reset()
        sheet.setStops(208f, 208f, presentation = 2)
        assertEquals(208f, sheet.extent)
        assertFalse(sheet.restsHigh)
    }

    @Test
    fun detents_doNothingWhileExiting() {
        val sheet = detents().apply { enabled = false }
        assertEquals(0f, sheet.drag(100f))
        assertEquals(0f, sheet.offset)
    }
}
