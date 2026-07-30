package dev.opencode.mobile.markdown

import kotlin.math.roundToInt

enum class TextMateHighlightSpeedBand {
    Fast,
    Ok,
    Slow,
}

object TextMateHighlightSpeed {
    fun budgetMilliseconds(fenceCharacters: Int): Double {
        val chars = fenceCharacters.coerceAtLeast(0)
        return when {
            chars <= 2_000 -> 50.0
            chars <= 8_000 -> 150.0
            else -> 150.0 * (chars.toDouble() / 8_000.0)
        }
    }

    fun band(
        totalNanoseconds: Long,
        fenceCharacters: Int,
    ): TextMateHighlightSpeedBand {
        val totalMs = totalNanoseconds.toDouble() / 1_000_000.0
        val budget = budgetMilliseconds(fenceCharacters)
        return when {
            totalMs <= budget * 0.5 -> TextMateHighlightSpeedBand.Fast
            totalMs <= budget -> TextMateHighlightSpeedBand.Ok
            else -> TextMateHighlightSpeedBand.Slow
        }
    }

    fun bandForOutcome(
        outcome: TextMateParseOutcome,
        totalNanoseconds: Long,
        fenceCharacters: Int,
    ): TextMateHighlightSpeedBand? =
        when (outcome) {
            TextMateParseOutcome.Highlighted,
            TextMateParseOutcome.NoSpans,
            -> band(totalNanoseconds, fenceCharacters)
            else -> null
        }

    /** Fence characters processed per millisecond of wall time, or null when unmeasured. */
    fun charactersPerMillisecond(
        totalNanoseconds: Long,
        fenceCharacters: Int,
    ): Double? {
        val totalMs = totalNanoseconds.toDouble() / 1_000_000.0
        if (totalMs <= 0.0) return null
        return fenceCharacters.coerceAtLeast(0).toDouble() / totalMs
    }

    /** Emitted spans per millisecond of wall time, or null when unmeasured. */
    fun spansPerMillisecond(
        totalNanoseconds: Long,
        spanCount: Int,
    ): Double? {
        val totalMs = totalNanoseconds.toDouble() / 1_000_000.0
        if (totalMs <= 0.0) return null
        return spanCount.coerceAtLeast(0).toDouble() / totalMs
    }

    /**
     * Compact throughput fragment for logbook subtitles/details, e.g.
     * `12.3 chars/ms · 4.1 spans/ms`. Null when wall time is zero.
     */
    fun throughputLabel(
        totalNanoseconds: Long,
        fenceCharacters: Int,
        spanCount: Int,
    ): String? {
        val charsPerMs = charactersPerMillisecond(totalNanoseconds, fenceCharacters) ?: return null
        val spansPerMs = spansPerMillisecond(totalNanoseconds, spanCount)
        return buildString {
            append(formatOneDecimal(charsPerMs))
            append(" chars/ms")
            if (spansPerMs != null) {
                append(" · ")
                append(formatOneDecimal(spansPerMs))
                append(" spans/ms")
            }
        }
    }

    private fun formatOneDecimal(value: Double): String {
        val tenths = (value * 10.0).roundToInt()
        val whole = tenths / 10
        val frac = kotlin.math.abs(tenths % 10)
        return "$whole.$frac"
    }
}
