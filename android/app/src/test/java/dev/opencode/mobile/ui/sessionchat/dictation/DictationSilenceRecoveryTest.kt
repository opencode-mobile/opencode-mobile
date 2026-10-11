package dev.opencode.mobile.ui.sessionchat.dictation

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationSilenceRecoveryTest {
    @Test
    fun firstPause_promotesVolatileAndRestarts() {
        val recovery =
            recoverDictationSilence(
                committed = "hello",
                volatile = " there",
                silenceRestarts = 0,
            )

        val restart = recovery as DictationSilenceRecovery.Restart
        assertEquals("hello there", restart.committed)
        assertEquals(1, restart.silenceRestarts)
    }

    @Test
    fun secondPause_keepsPromotedTranscript() {
        val recovery =
            recoverDictationSilence(
                committed = "hello",
                volatile = " there",
                silenceRestarts = 1,
            )

        assertEquals(DictationSilenceRecovery.Keep("hello there"), recovery)
    }

    @Test
    fun secondPause_withNothingSpoken_fails() {
        val recovery =
            recoverDictationSilence(
                committed = "",
                volatile = "",
                silenceRestarts = 1,
            )

        assertEquals(DictationSilenceRecovery.Fail, recovery)
    }
}
