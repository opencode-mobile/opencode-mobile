package dev.opencode.mobile.ui.components

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class OpenCodeSpinnerMorphTest {
    private val tolerance = 0.001f

    private fun near(
        a: Offset,
        b: Offset,
    ) = abs(a.x - b.x) < tolerance && abs(a.y - b.y) < tolerance

    @Test
    fun spinnerSweepBreathesBetweenItsLimits() {
        val tau = 2 * PI
        assertEquals(0.28 * tau, OpenCodeSpinnerGeometry.pose(0).sweep, 0.001)
        assertEquals(0.72 * tau, OpenCodeSpinnerGeometry.pose(800).sweep, 0.001)
        for (ms in 0L until 1_600L step 10) {
            val sweep = OpenCodeSpinnerGeometry.pose(ms).sweep / tau
            assertTrue(sweep >= 0.28 - 0.001 && sweep <= 0.72 + 0.001)
        }
    }

    @Test
    fun spinnerPoseIsAFunctionOfTheClock() {
        assertEquals(OpenCodeSpinnerGeometry.pose(12_340), OpenCodeSpinnerGeometry.pose(12_340))
    }

    @Test
    fun checkIsSampledWithItsCornerExact() {
        val points = OpenCodeCheckMorph.sampledCheck()
        assertEquals(OpenCodeCheckMorph.POINT_COUNT + 1, points.size)
        assertTrue(near(points.first(), OpenCodeCheckMorph.CheckLine[0]))
        assertTrue(points.any { near(it, OpenCodeCheckMorph.CheckLine[1]) })
        assertTrue(near(points.last(), OpenCodeCheckMorph.CheckLine[2]))
    }

    @Test
    fun morphStartsOnTheLiveArcAndEndsOnTheCheck() {
        val morph = OpenCodeCheckMorph(OpenCodeSpinnerPose(start = 1.1, sweep = 2.4))
        morph.points(0.0).forEachIndexed { index, point ->
            val angle = morph.angles[index]
            assertTrue(near(point, Offset((8 + 6 * cos(angle)).toFloat(), (8 + 6 * sin(angle)).toFloat())))
        }
        morph.points(OpenCodeCheckMorph.DURATION_MS).zip(OpenCodeCheckMorph.sampledCheck()).forEach { (point, target) ->
            assertTrue(near(point, target))
        }
    }

    @Test
    fun strokeColorAndPopRunToTheirEnds() {
        assertEquals(1.3f, OpenCodeCheckMorph.strokeWidth(0.0), tolerance)
        assertEquals(1f, OpenCodeCheckMorph.strokeWidth(540.0), tolerance)
        assertEquals(0f, OpenCodeCheckMorph.colorProgress(0.0), tolerance)
        assertEquals(1f, OpenCodeCheckMorph.colorProgress(540.0), tolerance)
        assertEquals(1.0, OpenCodeCheckMorph.scale(429.0), 0.0001)
        assertTrue(OpenCodeCheckMorph.scale(500.0) > 1.0)
        assertEquals(1.0, OpenCodeCheckMorph.scale(OpenCodeCheckMorph.TOTAL_MS), 0.0001)
    }
}
