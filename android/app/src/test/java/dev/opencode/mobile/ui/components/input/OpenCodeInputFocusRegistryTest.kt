package dev.opencode.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeInputFocusRegistryTest {
    private val registry = OpenCodeInputFocusRegistry()

    @Test
    fun reportsNothingInitially() {
        assertNull(registry.focusedTextInput)
        assertFalse(registry.hasModalSurface)
    }

    @Test
    fun fieldWinsOverComposerDuringHandOff() {
        val composer = Any()
        val field = Any()
        registry.updateTextInput(composer, OpenCodeFocusedTextInput.SessionComposer, focused = true)
        assertEquals(OpenCodeFocusedTextInput.SessionComposer, registry.focusedTextInput)
        registry.updateTextInput(field, OpenCodeFocusedTextInput.Field, focused = true)
        assertEquals(OpenCodeFocusedTextInput.Field, registry.focusedTextInput)
        registry.updateTextInput(composer, OpenCodeFocusedTextInput.SessionComposer, focused = false)
        assertEquals(OpenCodeFocusedTextInput.Field, registry.focusedTextInput)
        registry.removeTextInput(field)
        assertNull(registry.focusedTextInput)
    }

    @Test
    fun modalSurfacesCountPerOwner() {
        val sheet = Any()
        val other = Any()
        registry.updateModalSurface(sheet, shown = true)
        registry.updateModalSurface(other, shown = true)
        registry.updateModalSurface(sheet, shown = false)
        assertTrue(registry.hasModalSurface)
        registry.updateModalSurface(other, shown = false)
        assertFalse(registry.hasModalSurface)
    }
}
