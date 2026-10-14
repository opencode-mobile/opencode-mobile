package dev.opencode.mobile.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameworkSdkLevelTest {
    @Test
    fun `reported 33 with systemOverlays missing stays 33`() {
        assertEquals(
            33,
            FrameworkSdkLevel.correctedSdkInt(
                reportedSdkInt = 33,
                systemOverlaysAvailable = false,
            ),
        )
    }

    @Test
    fun `reported 34 with systemOverlays missing drops to 33`() {
        assertEquals(
            33,
            FrameworkSdkLevel.correctedSdkInt(
                reportedSdkInt = 34,
                systemOverlaysAvailable = false,
            ),
        )
    }

    @Test
    fun `reported 35 with systemOverlays missing drops to 33`() {
        assertEquals(
            33,
            FrameworkSdkLevel.correctedSdkInt(
                reportedSdkInt = 35,
                systemOverlaysAvailable = false,
            ),
        )
    }

    @Test
    fun `reported 34 with systemOverlays present stays 34`() {
        assertEquals(
            34,
            FrameworkSdkLevel.correctedSdkInt(
                reportedSdkInt = 34,
                systemOverlaysAvailable = true,
            ),
        )
    }

    @Test
    fun `reported 36 with systemOverlays present stays 36`() {
        assertEquals(
            36,
            FrameworkSdkLevel.correctedSdkInt(
                reportedSdkInt = 36,
                systemOverlaysAvailable = true,
            ),
        )
    }

    @Test
    fun `public systemOverlays method is available`() {
        assertTrue(FrameworkSdkLevel.systemOverlaysAvailable(WithSystemOverlays::class.java))
    }

    @Test
    fun `missing systemOverlays method is unavailable`() {
        assertFalse(FrameworkSdkLevel.systemOverlaysAvailable(WithoutSystemOverlays::class.java))
    }

    @Suppress("unused")
    private class WithSystemOverlays {
        fun systemOverlays(): Int = 512
    }

    @Suppress("unused")
    private class WithoutSystemOverlays
}
