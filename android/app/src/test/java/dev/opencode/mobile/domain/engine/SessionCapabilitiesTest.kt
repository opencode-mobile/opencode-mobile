package dev.opencode.mobile.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCapabilitiesTest {
    @Test
    fun openCodeFlagsStayUnchanged() {
        val capabilities = SessionCapabilities.OpenCode
        assertTrue(capabilities.showsAgent)
        assertTrue(capabilities.showsVariant)
        assertTrue(capabilities.showsUndo)
        assertTrue(capabilities.showsCompact)
        assertTrue(capabilities.showsMcp)
        assertTrue(capabilities.showsContext)
        assertTrue(capabilities.showsChildSessions)
        assertFalse(capabilities.showsProvider)
        assertFalse(capabilities.showsRuntimeMode)
        assertFalse(capabilities.showsSettle)
        assertTrue(capabilities.showsTerminal)
        assertTrue(capabilities.showsReview)
        assertTrue(capabilities.showsFiles)
        assertTrue(capabilities.requiresComposerModel)
        assertEquals(
            SessionCapabilities.OpenCode,
            SessionCapabilities.forEngine(dev.opencode.mobile.domain.connection.ConnectionEngine.OPENCODE),
        )
    }

    @Test
    fun t3FlagsHideOpenCodeChrome() {
        val capabilities = SessionCapabilities.T3
        assertFalse(capabilities.showsAgent)
        assertFalse(capabilities.showsVariant)
        assertFalse(capabilities.showsUndo)
        assertFalse(capabilities.showsCompact)
        assertFalse(capabilities.showsMcp)
        assertFalse(capabilities.showsContext)
        assertFalse(capabilities.showsChildSessions)
        assertTrue(capabilities.showsProvider)
        assertTrue(capabilities.showsRuntimeMode)
        assertTrue(capabilities.showsSettle)
        assertFalse(capabilities.showsTerminal)
        assertFalse(capabilities.showsReview)
        assertFalse(capabilities.showsFiles)
        assertFalse(capabilities.requiresComposerModel)
    }
}
