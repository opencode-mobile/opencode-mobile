package dev.opencode.mobile.domain.releasenotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {
    @Test
    fun parse_readsPlainVersions() {
        assertEquals(SemanticVersion(1, 5, 0), SemanticVersion.parse("1.5.0"))
        assertEquals(SemanticVersion(10, 0, 12), SemanticVersion.parse(" 10.0.12 "))
    }

    @Test
    fun parse_dropsPrereleaseAndBuildSuffixes() {
        assertEquals(SemanticVersion(1, 5, 0), SemanticVersion.parse("1.5.0-internal.1"))
        assertEquals(SemanticVersion(1, 5, 0), SemanticVersion.parse("1.5.0+42"))
        assertEquals(SemanticVersion(1, 5, 0), SemanticVersion.parse("1.5.0-rc.1+42"))
    }

    @Test
    fun parse_rejectsAnythingButThreeNumbers() {
        listOf(null, "", "1.0", "1.0.0.0", "1.a.0", "v1.0.0", "1.-1.0", "1..0").forEach { text ->
            assertNull(text, SemanticVersion.parse(text))
        }
    }

    @Test
    fun compare_isNumericPerPart() {
        assertTrue(SemanticVersion(1, 10, 0) > SemanticVersion(1, 9, 9))
        assertTrue(SemanticVersion(2, 0, 0) > SemanticVersion(1, 99, 99))
        assertTrue(SemanticVersion(1, 2, 10) > SemanticVersion(1, 2, 9))
        assertEquals(0, SemanticVersion(1, 2, 3).compareTo(SemanticVersion.parse("1.2.3-beta")!!))
    }
}
