package dev.opencode.hexcode.home

import kotlin.math.log10

/**
 * The talk button's meter level from the take's loudness (app-plan.md, Dictation › Meter): a visual cue that you're
 * being heard, not a measurement. RMS in dBFS maps to 0 at [EMPTY_DB] and 1 at [FULL_DB], on an ease-out curve, so
 * normal speech at arm's length fills about half to nine tenths of the height. Tuned on real phones: debug builds log
 * each take's level every quarter second (HexCode.Talk) to tune it again.
 */
object MeterLevel {
    /**
     * At or below this the bars rest as dots. Android's speech-recognition input is much quieter than iOS's (the
     * Pixel 9 gave −46 to −56 dB for normal speech), so its range sits lower than iOS's −50 to −18.
     */
    const val EMPTY_DB = -68f

    /** At or above this the bars are full. */
    const val FULL_DB = -38f

    /** [rms] (0 to 1, full scale) in dBFS; very quiet audio is a large negative number, never minus infinity. */
    fun dbfs(rms: Float): Float = 20f * log10(rms.coerceAtLeast(1e-6f))

    /** The bar height, 0 to 1, for a take whose RMS is [rms]. */
    fun fromRms(rms: Float): Float {
        val x = ((dbfs(rms) - EMPTY_DB) / (FULL_DB - EMPTY_DB)).coerceIn(0f, 1f)
        // Ease-out: quiet speech already lifts the bars well off the line.
        return 1f - (1f - x) * (1f - x)
    }
}
