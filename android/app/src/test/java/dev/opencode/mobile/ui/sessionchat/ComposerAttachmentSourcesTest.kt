package dev.opencode.mobile.ui.sessionchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerAttachmentSourcesTest {
    @Test
    fun locked_photosAndFiles_showsSheetWithoutLogFile() {
        val sources = ComposerAttachmentSources(photos = true, files = true, logFile = false)

        assertTrue(sources.isAttachEnabled)
        assertTrue(sources.showsSourceSheet)
        assertFalse(sources.logFile)
    }

    @Test
    fun locked_photosOnly_skipsSheet() {
        val sources = ComposerAttachmentSources(photos = true, files = false, logFile = false)

        assertTrue(sources.isAttachEnabled)
        assertFalse(sources.showsSourceSheet)
    }

    @Test
    fun locked_nothing_disablesAttach() {
        val sources = ComposerAttachmentSources(photos = false, files = false, logFile = false)

        assertFalse(sources.isAttachEnabled)
        assertFalse(sources.showsSourceSheet)
    }

    @Test
    fun unlocked_textOnly_enablesAttachAndShowsLogFile() {
        val sources = ComposerAttachmentSources(photos = false, files = false, logFile = true)

        assertTrue(sources.isAttachEnabled)
        assertTrue(sources.showsSourceSheet)
        assertTrue(sources.logFile)
    }

    @Test
    fun unlocked_photosOnly_showsSheetSoLogFileIsReachable() {
        val sources = ComposerAttachmentSources(photos = true, files = false, logFile = true)

        assertTrue(sources.isAttachEnabled)
        assertTrue(sources.showsSourceSheet)
        assertEquals(true, sources.photos)
        assertEquals(true, sources.logFile)
    }

    @Test
    fun engineOwnedModel_enablesPhotosWithoutComposerModel() {
        // T3 keeps the model on the session, so the composer may have none selected.
        val composer = SessionChatComposerState()

        assertEquals(
            ComposerAttachmentSources(photos = true, files = false, logFile = false),
            composer.attachmentSources(developerToolsUnlocked = false, allowsDocuments = false, modelOwnedByEngine = true),
        )
        assertEquals(
            ComposerAttachmentSources(photos = true, files = true, logFile = false),
            composer.attachmentSources(developerToolsUnlocked = false, allowsDocuments = true, modelOwnedByEngine = true),
        )
    }

    @Test
    fun composerOwnedModel_withoutSelection_disablesAttach() {
        assertFalse(SessionChatComposerState().attachmentSources(developerToolsUnlocked = false).isAttachEnabled)
    }
}
