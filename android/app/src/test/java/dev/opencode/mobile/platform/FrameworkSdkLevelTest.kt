package dev.opencode.mobile.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameworkSdkLevelTest {
    @Test
    fun `reported 33 with systemOverlays missing does not rewrite SDK_INT`() {
        assertNull(
            FrameworkSdkLevel.sdkIntToApply(
                reportedSdkInt = 33,
                probe = SystemOverlaysProbe.Missing,
            ),
        )
    }

    @Test
    fun `reported 34 with systemOverlays missing drops to 33`() {
        assertEquals(
            33,
            FrameworkSdkLevel.sdkIntToApply(
                reportedSdkInt = 34,
                probe = SystemOverlaysProbe.Missing,
            ),
        )
    }

    @Test
    fun `reported 35 with systemOverlays missing drops to 33`() {
        assertEquals(
            33,
            FrameworkSdkLevel.sdkIntToApply(
                reportedSdkInt = 35,
                probe = SystemOverlaysProbe.Missing,
            ),
        )
    }

    @Test
    fun `reported 34 with systemOverlays present does not rewrite SDK_INT`() {
        assertNull(
            FrameworkSdkLevel.sdkIntToApply(
                reportedSdkInt = 34,
                probe = SystemOverlaysProbe.Present,
            ),
        )
    }

    @Test
    fun `reported 36 with systemOverlays present does not rewrite SDK_INT`() {
        assertNull(
            FrameworkSdkLevel.sdkIntToApply(
                reportedSdkInt = 36,
                probe = SystemOverlaysProbe.Present,
            ),
        )
    }

    @Test
    fun `unknown probe on API 34 does not rewrite SDK_INT`() {
        assertNull(
            FrameworkSdkLevel.sdkIntToApply(
                reportedSdkInt = 34,
                probe = SystemOverlaysProbe.Unknown,
            ),
        )
    }

    @Test
    fun `unknown probe on API 36 does not rewrite SDK_INT`() {
        assertNull(
            FrameworkSdkLevel.sdkIntToApply(
                reportedSdkInt = 36,
                probe = SystemOverlaysProbe.Unknown,
            ),
        )
    }

    @Test
    fun `public systemOverlays method is present`() {
        assertEquals(
            SystemOverlaysProbe.Present,
            FrameworkSdkLevel.probeSystemOverlays(WithSystemOverlays::class.java),
        )
    }

    @Test
    fun `missing systemOverlays method is missing`() {
        assertEquals(
            SystemOverlaysProbe.Missing,
            FrameworkSdkLevel.probeSystemOverlays(WithoutSystemOverlays::class.java),
        )
    }

    @Test
    fun `NoSuchMethodException is the only missing result`() {
        assertEquals(
            SystemOverlaysProbe.Missing,
            FrameworkSdkLevel.classifyProbe { throw NoSuchMethodException("systemOverlays") },
        )
    }

    @Test
    fun `NoClassDefFoundError leaves the probe unknown`() {
        assertEquals(
            SystemOverlaysProbe.Unknown,
            FrameworkSdkLevel.classifyProbe { throw NoClassDefFoundError("WindowInsets") },
        )
    }

    @Test
    fun `unexpected probe failure leaves the probe unknown`() {
        assertEquals(
            SystemOverlaysProbe.Unknown,
            FrameworkSdkLevel.classifyProbe { throw IllegalStateException("probe failed") },
        )
    }

    @Suppress("unused")
    private class WithSystemOverlays {
        fun systemOverlays(): Int = 512
    }

    @Suppress("unused")
    private class WithoutSystemOverlays
}
