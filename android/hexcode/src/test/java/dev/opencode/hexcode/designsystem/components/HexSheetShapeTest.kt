package dev.opencode.hexcode.designsystem.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** hex-design-system.md, `HexSheet` (shape): the foot corners follow the screen's, less the 8 dp inset. */
class HexSheetShapeTest {
    @Test
    fun footCornersAreTheScreenCornersLessTheInset() {
        assertEquals(40.dp, sheetBottomRadius(48.dp))
    }

    @Test
    fun aPhoneThatReportsNoCornerGets55LessTheInset() {
        assertEquals(47.dp, sheetBottomRadius(null))
    }

    @Test
    fun squareOrSlightlyRoundedScreensStillGet16() {
        assertEquals(16.dp, sheetBottomRadius(0.dp))
        assertEquals(16.dp, sheetBottomRadius(20.dp))
    }
}
