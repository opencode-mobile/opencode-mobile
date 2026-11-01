package dev.opencode.mobile.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerAttachmentPasteTest {
    @Test
    fun imagesAndFilesAreAttachable() {
        assertTrue(composerClipTypesAreAttachable(listOf("image/png")))
        assertTrue(composerClipTypesAreAttachable(listOf("image/*", "text/plain")))
        assertTrue(composerClipTypesAreAttachable(listOf("application/pdf")))
    }

    @Test
    fun textClipsStayTextPastes() {
        assertFalse(composerClipTypesAreAttachable(emptyList()))
        assertFalse(composerClipTypesAreAttachable(listOf("text/plain", "text/html")))
        // An app-specific type next to text carries no file: the built-in Paste must stay.
        assertFalse(composerClipTypesAreAttachable(listOf("text/plain", "application/vnd.example.note")))
    }
}
