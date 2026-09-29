package dev.opencode.mobile.domain.featureflags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagTest {
    private val sharedKeyPattern = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")

    @Test
    fun `keys are unique snake_case shared with iOS`() {
        val keys = FeatureFlag.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        keys.forEach { key -> assertTrue("$key must be snake_case", sharedKeyPattern.matches(key)) }
    }

    @Test
    fun `fromKey resolves known keys only`() {
        assertEquals(FeatureFlag.SessionChatChipTimeline, FeatureFlag.fromKey("session_chat_chip_timeline"))
        assertNull(FeatureFlag.fromKey("unknown_flag"))
    }

    @Test
    fun `chip timeline defaults on`() {
        assertTrue(FeatureFlag.SessionChatChipTimeline.defaultValue)
    }
}
