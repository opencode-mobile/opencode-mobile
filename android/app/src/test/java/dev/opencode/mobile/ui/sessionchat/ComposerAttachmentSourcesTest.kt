package dev.opencode.mobile.ui.sessionchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerAttachmentSourcesTest {
    @Test
    fun noLogFile_photosAndFiles_showsSheet() {
        val sources = ComposerAttachmentSources(photos = true, files = true, logFile = false)

        assertTrue(sources.isAttachEnabled)
        assertTrue(sources.showsSourceSheet)
        assertFalse(sources.logFile)
    }

    @Test
    fun noLogFile_photosOnly_skipsSheet() {
        val sources = ComposerAttachmentSources(photos = true, files = false, logFile = false)

        assertTrue(sources.isAttachEnabled)
        assertFalse(sources.showsSourceSheet)
    }

    @Test
    fun noLogFile_nothing_disablesAttach() {
        val sources = ComposerAttachmentSources(photos = false, files = false, logFile = false)

        assertFalse(sources.isAttachEnabled)
        assertFalse(sources.showsSourceSheet)
    }

    @Test
    fun logFile_textOnly_enablesAttachAndShowsLogFile() {
        val sources = ComposerAttachmentSources(photos = false, files = false, logFile = true)

        assertTrue(sources.isAttachEnabled)
        assertTrue(sources.showsSourceSheet)
        assertTrue(sources.logFile)
    }

    @Test
    fun logFile_photosOnly_showsSheetSoLogFileIsReachable() {
        val sources = ComposerAttachmentSources(photos = true, files = false, logFile = true)

        assertTrue(sources.isAttachEnabled)
        assertTrue(sources.showsSourceSheet)
        assertEquals(true, sources.photos)
        assertEquals(true, sources.logFile)
    }

    @Test
    fun composer_alwaysOffersLogFile() {
        val sources = SessionChatComposerState().attachmentSources

        assertTrue(sources.logFile)
        assertTrue(sources.isAttachEnabled)
    }

    @Test
    fun streamedUploads_acceptAnyType() {
        assertFalse(SessionChatComposerState().attachmentSources.acceptsAnyType)
        assertTrue(SessionChatComposerState(attachmentUploadAvailable = true).attachmentSources.acceptsAnyType)
    }
}
