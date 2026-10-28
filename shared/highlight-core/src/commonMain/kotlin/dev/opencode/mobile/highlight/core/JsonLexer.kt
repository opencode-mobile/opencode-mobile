package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit
import dev.opencode.mobile.highlight.core.LexerScan.isWordPart
import dev.opencode.mobile.highlight.core.LexerScan.isWordStart

/**
 * JSON, leniently: also the JSONC and JSON5 additions people paste (comments, single quotes,
 * unquoted keys, hex, NaN and Infinity), so a tsconfig fence colors the same as strict JSON.
 *
 * Roles: an object key (a string or bare word followed by `:` on the same line), quotes
 * included, is Property; other strings are String with escapes as Constant; numbers, NaN, and
 * Infinity are Number; `true`, `false`, and `null` are Constant; `{ } [ ] : ,` are Punctuation;
 * comments are Comment. Anything else stays plain.
 *
 * States: 0, or [IN_BLOCK_COMMENT]. A string never continues past its line, so an unterminated
 * string (common while a fence streams in) colors only the rest of its own line.
 */
internal object JsonLexer : HighlightLexer() {
    private const val IN_BLOCK_COMMENT = 1

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        var index = start
        if (state == IN_BLOCK_COMMENT) {
            val close = LexerScan.blockCommentEnd(text, start, end)
            if (close < 0) {
                out.span(start, end, RoleCode.COMMENT)
                return IN_BLOCK_COMMENT
            }
            out.span(start, close, RoleCode.COMMENT)
            index = close
        }
        while (index < end && !out.stopped) {
            val char = text[index]
            when {
                char == ' ' || char == '\t' || char == '\r' -> index += 1
                char == '"' || char == '\'' -> index = string(text, index, end, char, out)
                char == '{' || char == '}' || char == '[' || char == ']' || char == ':' || char == ',' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                }
                isDigit(char) -> index = number(text, index, end, out)
                (char == '-' || char == '+' || char == '.') && startsNumber(text, index, end) -> index = number(text, index, end, out)
                char == '/' && index + 1 < end && text[index + 1] == '/' -> {
                    out.span(index, end, RoleCode.COMMENT)
                    return 0
                }
                char == '/' && index + 1 < end && text[index + 1] == '*' -> {
                    val close = LexerScan.blockCommentEnd(text, index + 2, end)
                    if (close < 0) {
                        out.span(index, end, RoleCode.COMMENT)
                        return IN_BLOCK_COMMENT
                    }
                    out.span(index, close, RoleCode.COMMENT)
                    index = close
                }
                isWordStart(char) -> index = word(text, index, end, out)
                else -> index += 1
            }
        }
        return 0
    }

    /** Lexes a string opening at [open]; returns the offset after it (the line end when unterminated). */
    private fun string(
        text: CharArray,
        open: Int,
        end: Int,
        quote: Char,
        out: SpanSink,
    ): Int {
        var index = open + 1
        var terminated = false
        while (index < end) {
            val char = text[index]
            if (char == '\\') {
                index += 2
            } else {
                index += 1
                if (char == quote) {
                    terminated = true
                    break
                }
            }
        }
        val close = minOf(index, end)
        if (terminated && isKey(text, close, end)) {
            out.span(open, close, RoleCode.PROPERTY)
            return close
        }
        if (!out.collecting) return close
        var segment = open
        index = open + 1
        while (index < close) {
            if (text[index] == '\\') {
                val escapeEnd = minOf(close, index + escapeLength(text, index, close))
                out.span(segment, index, RoleCode.STRING)
                out.span(index, escapeEnd, RoleCode.CONSTANT)
                segment = escapeEnd
                index = escapeEnd
            } else {
                index += 1
            }
        }
        out.span(segment, close, RoleCode.STRING)
        return close
    }

    private fun escapeLength(
        text: CharArray,
        backslash: Int,
        end: Int,
    ): Int {
        if (backslash + 1 >= end) return 1
        if (text[backslash + 1] != 'u') return 2
        var digits = 0
        while (digits < 4 && backslash + 2 + digits < end && isHexDigit(text[backslash + 2 + digits])) digits += 1
        return if (digits == 4) 6 else 2
    }

    private fun startsNumber(
        text: CharArray,
        at: Int,
        end: Int,
    ): Boolean {
        var index = at
        if (text[index] == '-' || text[index] == '+') index += 1
        if (index < end && text[index] == '.') index += 1
        return index < end && isDigit(text[index])
    }

    private fun number(
        text: CharArray,
        at: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        var index = at
        if (text[index] == '-' || text[index] == '+') index += 1
        if (index + 1 < end && text[index] == '0' && (text[index + 1] == 'x' || text[index + 1] == 'X')) {
            index += 2
            while (index < end && isHexDigit(text[index])) index += 1
        } else {
            while (index < end && isDigit(text[index])) index += 1
            if (index < end && text[index] == '.') {
                index += 1
                while (index < end && isDigit(text[index])) index += 1
            }
            if (index < end && (text[index] == 'e' || text[index] == 'E')) {
                var exponent = index + 1
                if (exponent < end && (text[exponent] == '+' || text[exponent] == '-')) exponent += 1
                if (exponent < end && isDigit(text[exponent])) {
                    index = exponent
                    while (index < end && isDigit(text[index])) index += 1
                }
            }
        }
        out.span(at, index, RoleCode.NUMBER)
        return index
    }

    private fun word(
        text: CharArray,
        at: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        var index = at + 1
        while (index < end && isWordPart(text[index])) index += 1
        val role =
            when {
                isKey(text, index, end) -> RoleCode.PROPERTY
                LexerScan.equals(text, at, index, "true") ||
                    LexerScan.equals(text, at, index, "false") ||
                    LexerScan.equals(text, at, index, "null") -> RoleCode.CONSTANT
                LexerScan.equals(text, at, index, "NaN") || LexerScan.equals(text, at, index, "Infinity") -> RoleCode.NUMBER
                else -> 0
            }
        if (role != 0) out.span(at, index, role)
        return index
    }

    private fun isKey(
        text: CharArray,
        after: Int,
        end: Int,
    ): Boolean {
        val next = LexerScan.skipBlanks(text, after, end)
        return next < end && text[next] == ':'
    }
}
