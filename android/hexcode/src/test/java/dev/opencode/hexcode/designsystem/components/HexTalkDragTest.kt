package dev.opencode.hexcode.designsystem.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** app-plan.md, Dictation: the 12 dp dead zone, and arming at 64 dp with hysteresis down to 52 dp. Distances in dp. */
class HexTalkDragTest {
    private fun look(offset: Float) = HexTalkDrag.look(offset, deadZone = 12f, armAt = 64f)

    private fun arm(
        previous: HexTalkRelease?,
        dx: Float,
    ) = HexTalkDrag.arm(previous, dx, armAt = 64f, disarmBelow = 52f)

    @Test
    fun nothingShowsInTheFirst12EitherWay() {
        assertEquals(0f, look(0f))
        assertEquals(0f, look(12f))
        assertEquals(0f, look(-12f))
        assertEquals(0.5f, look(38f), 0.0001f)
        assertEquals(0.5f, look(-38f), 0.0001f)
    }

    @Test
    fun theLookIsFullAtTheArmingPointAndNoFurther() {
        assertEquals(1f, look(64f))
        assertEquals(1f, look(-200f))
    }

    @Test
    fun theLookGrowsSteadilySoTintsFadeRatherThanSwitch() {
        val steps = (12..64).map { look(it.toFloat()) }
        steps.zipWithNext().forEach { (a, b) -> assert(b >= a && b - a < 0.03f) }
    }

    @Test
    fun armsAt64EitherWay() {
        assertNull(arm(null, 63.9f))
        assertEquals(HexTalkRelease.Lock, arm(null, 64f))
        assertEquals(HexTalkRelease.Drop, arm(null, -64f))
        assertNull(arm(null, -63f))
    }

    @Test
    fun staysArmedUntilBackUnder52() {
        assertEquals(HexTalkRelease.Lock, arm(HexTalkRelease.Lock, 55f))
        assertEquals(HexTalkRelease.Lock, arm(HexTalkRelease.Lock, 52f))
        assertNull(arm(HexTalkRelease.Lock, 51.9f))
        assertEquals(HexTalkRelease.Drop, arm(HexTalkRelease.Drop, -53f))
        assertNull(arm(HexTalkRelease.Drop, -40f))
    }

    @Test
    fun aShakyFingerAroundTheArmingPointDoesNotFlip() {
        var armed: HexTalkRelease? = null
        val changes = mutableListOf<HexTalkRelease?>()
        for (dx in listOf(60f, 65f, 61f, 58f, 63f, 54f, 66f, 53f, 60f)) {
            val next = arm(armed, dx)
            if (next != armed) changes += next
            armed = next
        }
        // One tick when it armed, none while it wobbled above 52.
        assertEquals(listOf<HexTalkRelease?>(HexTalkRelease.Lock), changes)
    }

    @Test
    fun crossingStraightToTheOtherSideArmsThatSide() {
        assertEquals(HexTalkRelease.Drop, arm(HexTalkRelease.Lock, -70f))
    }
}
