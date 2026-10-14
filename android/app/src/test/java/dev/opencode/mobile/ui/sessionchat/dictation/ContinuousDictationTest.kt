package dev.opencode.mobile.ui.sessionchat.dictation

import android.speech.SpeechRecognizer
import dev.opencode.mobile.ui.sessionchat.ComposerDictationPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousDictationTest {
    @Test
    fun appendUtterance_joinsWithOneSpace() {
        assertEquals("hello", appendDictationUtterance("", " hello "))
        assertEquals("hello there", appendDictationUtterance("hello", "there"))
        assertEquals("hello there", appendDictationUtterance("hello ", "there"))
        assertEquals("hello", appendDictationUtterance("hello", "  "))
    }

    @Test
    fun volatileTail_startsWithSeparatorAfterCommittedText() {
        assertEquals("there", dictationVolatileTail("", "there"))
        assertEquals(" there", dictationVolatileTail("hello", "there"))
        assertEquals("", dictationVolatileTail("hello", ""))
    }

    @Test
    fun silence_keepsListeningWithOrWithoutTranscript() {
        for (error in listOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
            for (failures in 0..5) {
                assertEquals(DictationErrorAction.Restart, dictationErrorAction(error, failures, hasTranscript = false))
                assertEquals(DictationErrorAction.Restart, dictationErrorAction(error, failures, hasTranscript = true))
            }
        }
    }

    @Test
    fun busyRecognizer_retriesThenKeepsTranscript() {
        val busy = SpeechRecognizer.ERROR_RECOGNIZER_BUSY
        assertEquals(DictationErrorAction.RetryLater, dictationErrorAction(busy, 0, hasTranscript = false))
        assertEquals(DictationErrorAction.RetryLater, dictationErrorAction(busy, 2, hasTranscript = false))
        assertEquals(DictationErrorAction.KeepTranscript, dictationErrorAction(busy, 3, hasTranscript = true))
        assertEquals(DictationErrorAction.Fail, dictationErrorAction(busy, 3, hasTranscript = false))
    }

    @Test
    fun fatalError_keepsTranscriptWhenThereIsOne() {
        val audio = SpeechRecognizer.ERROR_AUDIO
        assertEquals(DictationErrorAction.KeepTranscript, dictationErrorAction(audio, 0, hasTranscript = true))
        assertEquals(DictationErrorAction.Fail, dictationErrorAction(audio, 0, hasTranscript = false))
        assertEquals(
            DictationErrorAction.PermissionDenied,
            dictationErrorAction(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, 0, hasTranscript = true),
        )
    }

    @Test
    fun stoppedNotification_showsFinalTranscriptSettlesOrbThenStops() {
        val events = mutableListOf<String>()
        val listener =
            object : ComposerDictationSession.Listener {
                override fun onPartial(
                    committed: String,
                    volatile: String,
                ) {
                    events += "partial:$committed|$volatile"
                }

                override fun onLevel(level: Float) {
                    events += "level:$level"
                }

                override fun onStopped() {
                    events += "stopped"
                }

                override fun onUnavailable(
                    phase: ComposerDictationPhase,
                    message: String?,
                ) {
                    events += "unavailable:$phase"
                }
            }

        notifyDictationStopped(listener, "hello there")

        assertEquals(listOf("partial:hello there|", "level:0.0", "stopped"), events)
    }

    @Test
    fun rmsLevel_isSilentAtRestAndRisesWithSpeech() {
        assertEquals(0f, dictationLevelFromRmsDb(-2f))
        assertEquals(1f, dictationLevelFromRmsDb(12f))
        val quiet = dictationLevelFromRmsDb(2f)
        val normal = dictationLevelFromRmsDb(6f)
        assertTrue(quiet > 0.3f)
        assertTrue(normal > quiet)
    }
}
