package dev.opencode.mobile.domain.featureflags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SessionComposerStyleTest {
    @Test
    fun `outline fade is the standard style`() {
        assertEquals(SessionComposerStyle.OutlineFade, SessionComposerStyle.resolve(docked = false))
    }

    @Test
    fun `docked flag selects docked`() {
        assertEquals(SessionComposerStyle.Docked, SessionComposerStyle.resolve(docked = true))
    }

    @Test
    fun `only docked stays behind a flag and defaults off`() {
        assertEquals(FeatureFlag.SessionComposerDocked, FeatureFlag.fromKey("session_composer_docked"))
        assertFalse(FeatureFlag.SessionComposerDocked.defaultValue)
        assertNull(FeatureFlag.fromKey("session_composer_outline_fade"))
    }
}
