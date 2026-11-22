package dev.opencode.mobile.domain.featureflags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionComposerStyleTest {
    @Test
    fun `both flags off stays floating`() {
        assertEquals(SessionComposerStyle.Floating, SessionComposerStyle.resolve(outlineFade = false, docked = false))
    }

    @Test
    fun `outline flag alone selects outline fade`() {
        assertEquals(SessionComposerStyle.OutlineFade, SessionComposerStyle.resolve(outlineFade = true, docked = false))
    }

    @Test
    fun `docked flag alone selects docked`() {
        assertEquals(SessionComposerStyle.Docked, SessionComposerStyle.resolve(outlineFade = false, docked = true))
    }

    @Test
    fun `docked wins when both flags are on`() {
        assertEquals(SessionComposerStyle.Docked, SessionComposerStyle.resolve(outlineFade = true, docked = true))
    }

    @Test
    fun `composer style flags use shared keys and default off`() {
        assertEquals(FeatureFlag.SessionComposerOutlineFade, FeatureFlag.fromKey("session_composer_outline_fade"))
        assertEquals(FeatureFlag.SessionComposerDocked, FeatureFlag.fromKey("session_composer_docked"))
        assertFalse(FeatureFlag.SessionComposerOutlineFade.defaultValue)
        assertFalse(FeatureFlag.SessionComposerDocked.defaultValue)
    }
}
