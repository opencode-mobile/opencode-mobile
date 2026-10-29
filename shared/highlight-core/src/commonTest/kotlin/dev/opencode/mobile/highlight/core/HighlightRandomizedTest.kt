package dev.opencode.mobile.highlight.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Random inputs against every property for a while. A second by default; lengthen it with
 * `HIGHLIGHT_FUZZ_SECONDS=3600` (the issue's hour-long run) and replay a failure with
 * `HIGHLIGHT_FUZZ_SEED`. From `android/`:
 * `HIGHLIGHT_FUZZ_SECONDS=600 ./gradlew :highlight-core:testDebugUnitTest --tests '*HighlightRandomizedTest*' --rerun`
 */
class HighlightRandomizedTest {
    @Test
    fun randomInputsKeepEveryProperty() {
        val seconds = testEnvironment("HIGHLIGHT_FUZZ_SECONDS")?.toDoubleOrNull() ?: 1.0
        val seed = testEnvironment("HIGHLIGHT_FUZZ_SEED")?.toLongOrNull() ?: Random.nextLong()
        val random = Random(seed)
        val until = TimeSource.Monotonic.markNow() + seconds.seconds
        var rounds = 0
        while (!until.hasPassedNow()) {
            val roundSeed = random.nextLong()
            val roundRandom = Random(roundSeed)
            val language = HighlightGenerators.languages[roundRandom.nextInt(HighlightGenerators.languages.size)]
            val size = if (roundRandom.nextInt(50) == 0) roundRandom.nextInt(5_000, 60_000) else roundRandom.nextInt(0, 800)
            val text = HighlightGenerators.text(language, roundRandom, size)
            val limits = if (roundRandom.nextInt(5) == 0) HighlightLimits(maxLineLength = roundRandom.nextInt(1, 64)) else HighlightLimits.standard
            try {
                HighlightPropertyChecks.checkAll(language, text, roundRandom, limits)
            } catch (failure: Throwable) {
                fail("Round $rounds (run seed $seed, round seed $roundSeed, $language, ${text.length} chars) failed: ${failure.message}", failure)
            }
            rounds += 1
        }
        println("HighlightRandomizedTest: $rounds rounds in $seconds s, seed $seed")
    }
}
