package dev.opencode.mobile.domain.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppVersionTest {
    @Test
    fun from_usesVersionNameAndDecimalBuild() {
        val appVersion = AppVersion.from(versionName = "0.1.0", versionCode = 42L)
        assertEquals("0.1.0", appVersion.version)
        assertEquals("42", appVersion.build)
    }

    @Test
    fun from_fallsBackWhenVersionNameMissingOrBlank() {
        assertEquals(AppVersion.FALLBACK_VERSION, AppVersion.from(null, 1L).version)
        assertEquals(AppVersion.FALLBACK_VERSION, AppVersion.from("", 1L).version)
        assertEquals(AppVersion.FALLBACK_VERSION, AppVersion.from("   ", 1L).version)
    }

    @Test
    fun from_trimsVersionNameAndKeepsLargeBuildDecimal() {
        val appVersion = AppVersion.from(versionName = " 1.2.3 ", versionCode = 3_000_000_001L)
        assertEquals("1.2.3", appVersion.version)
        assertEquals("3000000001", appVersion.build)
    }
}
