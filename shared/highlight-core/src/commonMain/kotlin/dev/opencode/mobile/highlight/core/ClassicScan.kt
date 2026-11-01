package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit

/**
 * Helpers shared by the lexers of the older, mostly case-insensitive languages (COBOL, Fortran,
 * Ada, Pascal, Visual Basic, ABAP, SAS): words of letters, digits, and `_`, strings whose quote
 * is escaped by doubling it, and keyword runs. Allocation-free, like [LexerScan].
 */
internal object ClassicScan {
    /** An ASCII letter, or any other letter (identifiers in these languages may be Unicode). */
    fun isLetter(char: Char): Boolean = isAsciiLetter(char) || (char.code >= 0x80 && char.isLetter())

    fun isWordPart(char: Char): Boolean = isLetter(char) || isDigit(char) || char == '_'

    /** The end of the word of letters, digits, and `_` from [from]. */
    fun wordEnd(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        var index = from
        while (index < end && isWordPart(text[index])) index += 1
        return index
    }

    /** True when the next non-blank after [from] is [char]. */
    fun nextIs(
        text: CharArray,
        from: Int,
        end: Int,
        char: Char,
    ): Boolean {
        val at = LexerScan.skipBlanks(text, from, end)
        return at < end && text[at] == char
    }

    /** True when `text[from, to)` equals the lowercase ASCII [word] in any case. */
    fun equalsIgnoringCase(
        text: CharArray,
        from: Int,
        to: Int,
        word: String,
    ): Boolean {
        if (to - from != word.length) return false
        for (offset in word.indices) {
            val char = text[from + offset]
            val folded = if (char in 'A'..'Z') char + 32 else char
            if (folded != word[offset]) return false
        }
        return true
    }

    /**
     * The body of a string whose [quote] is escaped by doubling it, from [from] (just after the
     * opening quote): String with each doubled quote as Constant, the closing quote included.
     * Returns the offset after the closing quote, or -1 when the line ends first (the rest of the
     * line is String). [segment] is where the String span starts (the opening quote or a prefix).
     */
    fun doubledQuoteString(
        text: CharArray,
        segment: Int,
        from: Int,
        end: Int,
        quote: Char,
        out: SpanSink,
    ): Int {
        var index = from
        var open = segment
        while (index < end) {
            if (text[index] == quote) {
                if (index + 1 < end && text[index + 1] == quote) {
                    out.span(open, index, RoleCode.STRING)
                    out.span(index, index + 2, RoleCode.CONSTANT)
                    index += 2
                    open = index
                    continue
                }
                out.span(open, index + 1, RoleCode.STRING)
                return index + 1
            }
            index += 1
        }
        out.span(open, end, RoleCode.STRING)
        return -1
    }

    /** Like [doubledQuoteString] without emitting: the offset after the closing quote, or -1. */
    fun doubledQuoteEnd(
        text: CharArray,
        from: Int,
        end: Int,
        quote: Char,
    ): Int {
        var index = from
        while (index < end) {
            if (text[index] == quote) {
                if (index + 1 < end && text[index + 1] == quote) {
                    index += 2
                    continue
                }
                return index + 1
            }
            index += 1
        }
        return -1
    }

    /** Digits and `_` separators from [from]. */
    fun digitsEnd(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        var index = from
        while (index < end && (isDigit(text[index]) || text[index] == '_')) index += 1
        return index
    }

    /**
     * An exponent at [at] (`e`/`E`, or any of [letters] in either case, then an optional sign and
     * digits); the offset after it, or [at] when there is none.
     */
    fun exponentEnd(
        text: CharArray,
        at: Int,
        end: Int,
        letters: String,
    ): Int {
        if (at >= end) return at
        val char = text[at]
        val folded = if (char in 'A'..'Z') char + 32 else char
        if (letters.indexOf(folded) < 0) return at
        var index = at + 1
        if (index < end && (text[index] == '+' || text[index] == '-')) index += 1
        if (index >= end || !isDigit(text[index])) return at
        return digitsEnd(text, index, end)
    }
}
