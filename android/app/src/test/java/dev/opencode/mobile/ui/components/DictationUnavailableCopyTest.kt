package dev.opencode.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationUnavailableCopyTest {
    @Test
    fun modelStates_nameAndroidAndTheLanguage() {
        val model =
            listOf(
                OpenCodeDictationUnavailableReason.ModelNotInstalled,
                OpenCodeDictationUnavailableReason.ModelDownloading,
                OpenCodeDictationUnavailableReason.ModelDownloadQueued,
                OpenCodeDictationUnavailableReason.ModelDownloadFailed,
            )
        for (reason in model) {
            val copy = unavailableCopy(reason, errorMessage = null, languageLabel = "English")
            assertTrue(copy.title, copy.title.startsWith("Android"))
            assertTrue(copy.title, copy.title.contains("English speech model"))
            assertNotNull(copy.detail)
        }
    }

    @Test
    fun blankLanguage_fallsBackToSpeechModel() {
        val copy = unavailableCopy(OpenCodeDictationUnavailableReason.ModelNotInstalled, null, languageLabel = "")
        assertEquals("Android needs its speech model", copy.title)
    }

    @Test
    fun recognizerError_usesTheMessageOrDefault() {
        assertEquals(
            "Busy",
            unavailableCopy(OpenCodeDictationUnavailableReason.RecognizerError, "Busy", "English").title,
        )
        val fallback = unavailableCopy(OpenCodeDictationUnavailableReason.RecognizerError, " ", "English")
        assertEquals("Dictation stopped", fallback.title)
        assertNull(fallback.detail)
    }
}
