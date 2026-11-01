package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * Allocation-free scanning shared by the Haskell, OCaml, F#, Clojure, Common Lisp, Prolog, and
 * Smalltalk lexers: nested comments, escaped and doubled-quote strings, character literals, and
 * numbers.
 */
internal object SymbolicScan {
    /** Nesting depth past which a nested comment stops counting openers, so states stay bounded. */
    const val MAX_DEPTH = 0x3FFF

    /** A string that runs to the line end. */
    const val OPEN = -1

    /** A string that runs to the line end, where its last character is an escaping backslash. */
    const val CONTINUED = -2

    /**
     * Scans a nested comment from [from] at [depth] (at least 1): `open` + `openNext` nests and
     * `close` + `closeNext` closes. Returns the offset after the close that ends it, or `-depth`
     * when the line ends inside it.
     */
    fun nestedCommentEnd(
        text: CharArray,
        from: Int,
        end: Int,
        depth: Int,
        open: Char,
        openNext: Char,
        close: Char,
        closeNext: Char,
    ): Int {
        var level = depth
        var index = from
        while (index + 1 < end) {
            val char = text[index]
            if (char == open && text[index + 1] == openNext) {
                if (level < MAX_DEPTH) level += 1
                index += 2
            } else if (char == close && text[index + 1] == closeNext) {
                level -= 1
                index += 2
                if (level == 0) return index
            } else {
                index += 1
            }
        }
        return -level
    }

    /** The offset just past the first [close] (three characters at most) at or after [from], or -1. */
    fun find(
        text: CharArray,
        from: Int,
        end: Int,
        close: String,
    ): Int {
        var index = from
        val first = close[0]
        while (index + close.length <= end) {
            if (text[index] == first && LexerScan.startsWith(text, index, end, close)) return index + close.length
            index += 1
        }
        return -1
    }

    fun isNameStart(char: Char): Boolean = isAsciiLetter(char) || char == '_' || (char.code >= 0x80 && char.isLetter())

    /** A name character in the ML family, where primes belong to names (`x'`, `foldl'`). */
    fun isPrimedNamePart(char: Char): Boolean = isNameStart(char) || isDigit(char) || char == '\''

    fun isUpper(char: Char): Boolean = char in 'A'..'Z' || (char.code >= 0x80 && char.isUpperCase())

    /** The offset after an ML name starting at [from]: letters, digits, `_`, and primes. */
    fun primedNameEnd(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        var index = from
        while (index < end && isPrimedNamePart(text[index])) index += 1
        return index
    }

    /** How many UTF-16 units the character at [at] takes: 2 for a whole surrogate pair. */
    fun charLength(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int = if (text[at].isHighSurrogate() && at + 1 < end && text[at + 1].isLowSurrogate()) 2 else 1

    /**
     * An ML character literal at the quote [at]: `'x'`, `'\n'`, `'\''`, `'\x41'`, `'\u{1F600}'`,
     * `'\^A'`. Returns the offset after its closing quote, or -1 when it is not one (a type
     * variable `'a`, a promoted constructor).
     */
    fun quotedCharEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        val first = at + 1
        if (first >= end) return -1
        val char = text[first]
        if (char == '\\') {
            if (first + 1 >= end) return -1
            var index = first + 1 + charLength(text, first + 1, end)
            val limit = minOf(end, first + 14)
            while (index < limit) {
                val part = text[index]
                if (part == '\'') return index + 1
                if (!(isAsciiLetter(part) || isDigit(part) || part == '{' || part == '}' || part == '_')) return -1
                index += 1
            }
            return -1
        }
        if (char == '\'') return -1
        val after = first + charLength(text, first, end)
        return if (after < end && text[after] == '\'') after + 1 else -1
    }

    /**
     * String text from [from] up to and including [quote], with backslash escapes as Constant.
     * [simpleEscapes] makes every escape a backslash and one character (Lisp); otherwise numeric
     * escapes take their digits. Returns the offset after the quote, [OPEN], or [CONTINUED].
     */
    fun escapedEnd(
        text: CharArray,
        from: Int,
        end: Int,
        quote: Char,
        out: SpanSink,
        simpleEscapes: Boolean = false,
    ): Int {
        var index = from
        var segment = from
        while (index < end) {
            val char = text[index]
            if (char == '\\') {
                out.span(segment, index, RoleCode.STRING)
                if (index + 1 >= end) {
                    out.span(index, end, RoleCode.CONSTANT)
                    return CONTINUED
                }
                val after = if (simpleEscapes) index + 1 + charLength(text, index + 1, end) else escapeEnd(text, index, end)
                out.span(index, after, RoleCode.CONSTANT)
                index = after
                segment = after
                continue
            }
            if (char == quote) {
                out.span(segment, index + 1, RoleCode.STRING)
                return index + 1
            }
            index += 1
        }
        out.span(segment, end, RoleCode.STRING)
        return OPEN
    }

    /** The end of the escape at the backslash [at] (with a character after it). */
    fun escapeEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        val char = text[at + 1]
        var index = at + 2
        when {
            isDigit(char) -> while (index < end && index < at + 5 && isDigit(text[index])) index += 1
            char == 'x' || char == 'o' -> {
                // `\o` takes octal digits only; `\x` hex, ending at an optional `\` (Prolog `\x41\`).
                while (index < end && index < at + 8 && (if (char == 'o') text[index] in '0'..'7' else isHexDigit(text[index]))) index += 1
            }
            char == 'u' && index < end && text[index] == '{' -> {
                var close = index + 1
                while (close < end && close < at + 14 && isHexDigit(text[close])) close += 1
                if (close < end && text[close] == '}') index = close + 1
            }
            char == 'u' || char == 'U' -> {
                val digits = if (char == 'u') 4 else 8
                var close = index
                while (close < end && close < index + digits && isHexDigit(text[close])) close += 1
                if (close == index + digits) index = close
            }
            char.isHighSurrogate() && index < end && text[index].isLowSurrogate() -> index += 1
        }
        return index
    }

    /**
     * Quoted text from [from] up to and including [quote], where a doubled quote stays inside as
     * Constant (Smalltalk, Prolog, SQL style), and backslash escapes are Constant when
     * [backslashes]. Returns the offset after the quote, [OPEN], or [CONTINUED].
     */
    fun doubledEnd(
        text: CharArray,
        from: Int,
        end: Int,
        quote: Char,
        backslashes: Boolean,
        role: Int,
        out: SpanSink,
    ): Int {
        var index = from
        var segment = from
        while (index < end) {
            val char = text[index]
            if (backslashes && char == '\\') {
                out.span(segment, index, role)
                if (index + 1 >= end) {
                    out.span(index, end, RoleCode.CONSTANT)
                    return CONTINUED
                }
                val after = escapeEnd(text, index, end).let { if (it < end && text[it] == '\\' && text[index + 1] == 'x') it + 1 else it }
                out.span(index, after, RoleCode.CONSTANT)
                index = after
                segment = after
                continue
            }
            if (char == quote) {
                if (index + 1 < end && text[index + 1] == quote) {
                    out.span(segment, index, role)
                    out.span(index, index + 2, RoleCode.CONSTANT)
                    index += 2
                    segment = index
                    continue
                }
                out.span(segment, index + 1, role)
                return index + 1
            }
            index += 1
        }
        out.span(segment, end, role)
        return OPEN
    }

    /**
     * A number at the digit [at]: `0x`, `0o`, and `0b` prefixes, `_` separators, a fraction (a
     * digit after the dot), and an exponent.
     */
    fun numberEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at
        if (text[index] == '0' && index + 2 < end) {
            val prefix = text[index + 1]
            val radix =
                when (prefix) {
                    'x', 'X' -> 16
                    'o', 'O' -> 8
                    'b', 'B' -> 2
                    else -> 0
                }
            if (radix != 0 && isRadixDigit(text[index + 2], radix)) {
                index += 2
                while (index < end && (isRadixDigit(text[index], radix) || text[index] == '_')) index += 1
                return index
            }
        }
        while (index < end && (isDigit(text[index]) || text[index] == '_')) index += 1
        if (index + 1 < end && text[index] == '.' && isDigit(text[index + 1])) {
            index += 1
            while (index < end && (isDigit(text[index]) || text[index] == '_')) index += 1
        }
        return exponentEnd(text, index, end)
    }

    /** Past an `e`/`E` exponent at [at] when one is there, otherwise [at]. */
    fun exponentEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        if (at >= end || (text[at] != 'e' && text[at] != 'E')) return at
        var index = at + 1
        if (index < end && (text[index] == '+' || text[index] == '-')) index += 1
        if (index >= end || !isDigit(text[index])) return at
        while (index < end && (isDigit(text[index]) || text[index] == '_')) index += 1
        return index
    }

    fun isRadixDigit(
        char: Char,
        radix: Int,
    ): Boolean =
        when (radix) {
            16 -> isHexDigit(char)
            8 -> char in '0'..'7'
            else -> char == '0' || char == '1'
        }

    /** True when [char] is the first character at or after [from] that is not a blank. */
    fun nextIs(
        text: CharArray,
        from: Int,
        end: Int,
        char: Char,
    ): Boolean {
        val at = LexerScan.skipBlanks(text, from, end)
        return at < end && text[at] == char
    }
}
