package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * TOML 1.0, plus the TOML 1.1 newlines in inline tables.
 *
 * Roles: keys (bare, quoted, and dotted) are Property, with the `.` between parts as
 * Punctuation; table and array-table names in `[...]` / `[[...]]` headers are Type; strings
 * (basic, literal, and both multi-line forms) are String with escapes as Constant; numbers
 * (with `_`, hex, octal, binary, `inf`, `nan`) and dates and times are Number; `true` and
 * `false` are Constant; `=`, `,`, brackets, and braces are Punctuation; comments are Comment.
 *
 * States: bits 0-1 hold an open multi-line string ([BASIC] `"""` or [LITERAL] `'''`), bits
 * 2-5 the depth of open value brackets (arrays and inline tables, up to [MAX_DEPTH]), and bits
 * 6-13 whether each level is an inline table, so a line inside one starts with a key and a line
 * inside an array with a value. One-line strings end at their line, as TOML requires.
 *
 * Known gaps: brackets nested deeper than [MAX_DEPTH] are not tracked; keys are not checked
 * for an `=` after them.
 */
internal object TomlLexer : HighlightLexer() {
    private const val STRING_MASK = 3
    private const val BASIC = 1
    private const val LITERAL = 2
    private const val DEPTH_SHIFT = 2
    private const val DEPTH_MASK = 15 shl DEPTH_SHIFT
    private const val KIND_SHIFT = 6
    private const val MAX_DEPTH = 8

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        var st = state
        var index = start
        val open = st and STRING_MASK
        if (open != 0) {
            val close = multiLineEnd(text, start, end, if (open == BASIC) '"' else '\'')
            stringSpans(text, start, if (close < 0) end else close, open == BASIC, out)
            if (close < 0) return st
            st = st and STRING_MASK.inv()
            index = close
        }
        var depth = (st and DEPTH_MASK) ushr DEPTH_SHIFT
        var tables = (st ushr KIND_SHIFT) and 0xFF
        var expectKey = open == 0 && (depth == 0 || isTable(tables, depth))
        if (open == 0 && depth == 0) {
            val first = LexerScan.skipBlanks(text, start, end)
            if (first < end && text[first] == '[') {
                index = header(text, first, end, out)
                expectKey = false
            }
        }
        while (index < end && !out.stopped) {
            val char = text[index]
            when {
                char == ' ' || char == '\t' -> index += 1
                char == '#' -> {
                    out.span(index, end, RoleCode.COMMENT)
                    index = end
                }
                expectKey && (isBareKeyPart(char) || char == '"' || char == '\'') -> {
                    index = key(text, index, end, RoleCode.PROPERTY, out)
                    expectKey = false
                }
                char == '=' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    expectKey = false
                }
                char == '"' || char == '\'' -> {
                    val basic = char == '"'
                    if (index + 2 < end && text[index + 1] == char && text[index + 2] == char) {
                        val close = multiLineEnd(text, index + 3, end, char)
                        stringSpans(text, index, if (close < 0) end else close, basic, out)
                        if (close < 0) {
                            index = end
                            st = if (basic) BASIC else LITERAL
                        } else {
                            index = close
                        }
                    } else {
                        val close = lineStringEnd(text, index + 1, end, char)
                        stringSpans(text, index, close, basic, out)
                        index = close
                    }
                }
                char == '[' || char == '{' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    if (depth < MAX_DEPTH) {
                        if (char == '{') tables = tables or (1 shl depth)
                        depth += 1
                    }
                    expectKey = char == '{'
                    index += 1
                }
                char == ']' || char == '}' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    if (depth > 0) {
                        depth -= 1
                        tables = tables and (1 shl depth).inv()
                    }
                    expectKey = false
                    index += 1
                }
                char == ',' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    expectKey = depth > 0 && isTable(tables, depth)
                    index += 1
                }
                isDigit(char) || char == '+' || char == '-' -> {
                    val after = numberEnd(text, index, end)
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                }
                isAsciiLetter(char) -> {
                    var after = index + 1
                    while (after < end && isBareKeyPart(text[after])) after += 1
                    when {
                        LexerScan.equals(text, index, after, "true") || LexerScan.equals(text, index, after, "false") ->
                            out.span(index, after, RoleCode.CONSTANT)
                        LexerScan.equals(text, index, after, "inf") || LexerScan.equals(text, index, after, "nan") ->
                            out.span(index, after, RoleCode.NUMBER)
                    }
                    index = after
                }
                else -> index += 1
            }
        }
        if (out.stopped) return st
        return (st and STRING_MASK) or (depth shl DEPTH_SHIFT) or (tables shl KIND_SHIFT)
    }

    /** A `[table]` or `[[array]]` header from the `[` at [open]; returns the offset after it. */
    private fun header(
        text: CharArray,
        open: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        val double = open + 1 < end && text[open + 1] == '['
        var index = if (double) open + 2 else open + 1
        out.span(open, index, RoleCode.PUNCTUATION)
        index = LexerScan.skipBlanks(text, index, end)
        if (index < end && text[index] != ']') index = key(text, index, end, RoleCode.TYPE, out)
        index = LexerScan.skipBlanks(text, index, end)
        if (index < end && text[index] == ']') {
            val close = if (double && index + 1 < end && text[index + 1] == ']') index + 2 else index + 1
            out.span(index, close, RoleCode.PUNCTUATION)
            index = close
        }
        return index
    }

    /** A dotted key from [from]: parts as [role], dots as Punctuation; returns the offset after it. */
    private fun key(
        text: CharArray,
        from: Int,
        end: Int,
        role: Int,
        out: SpanSink,
    ): Int {
        var index = from
        while (index < end) {
            val char = text[index]
            val partEnd =
                when {
                    char == '"' || char == '\'' -> lineStringEnd(text, index + 1, end, char)
                    isBareKeyPart(char) -> {
                        var after = index + 1
                        while (after < end && isBareKeyPart(text[after])) after += 1
                        after
                    }
                    else -> return index
                }
            out.span(index, partEnd, role)
            index = LexerScan.skipBlanks(text, partEnd, end)
            if (index >= end || text[index] != '.') return partEnd
            out.span(index, index + 1, RoleCode.PUNCTUATION)
            index = LexerScan.skipBlanks(text, index + 1, end)
        }
        return index
    }

    /** The offset after a one-line string's closing quote, or [end]. */
    private fun lineStringEnd(
        text: CharArray,
        from: Int,
        end: Int,
        quote: Char,
    ): Int {
        var index = from
        while (index < end) {
            val char = text[index]
            if (char == quote) return index + 1
            index += if (char == '\\' && quote == '"') 2 else 1
        }
        return end
    }

    /**
     * The offset after a multi-line string's closing triple quote, which takes up to two more
     * quotes of the content with it; -1 when the string continues past the line.
     */
    private fun multiLineEnd(
        text: CharArray,
        from: Int,
        end: Int,
        quote: Char,
    ): Int {
        var index = from
        while (index < end) {
            val char = text[index]
            if (char == '\\' && quote == '"') {
                index += 2
                continue
            }
            if (char == quote && index + 2 < end && text[index + 1] == quote && text[index + 2] == quote) {
                var close = index + 3
                var extra = 0
                while (extra < 2 && close < end && text[close] == quote) {
                    close += 1
                    extra += 1
                }
                return close
            }
            index += 1
        }
        return -1
    }

    /** String text `[from, to)` as String, with escapes in basic strings as Constant. */
    private fun stringSpans(
        text: CharArray,
        from: Int,
        to: Int,
        basic: Boolean,
        out: SpanSink,
    ) {
        if (!basic || !out.collecting) {
            out.span(from, to, RoleCode.STRING)
            return
        }
        var segment = from
        var index = from
        while (index < to) {
            if (text[index] == '\\') {
                val length = escapeLength(text, index, to)
                out.span(segment, index, RoleCode.STRING)
                out.span(index, index + length, RoleCode.CONSTANT)
                index += length
                segment = index
            } else {
                index += 1
            }
        }
        out.span(segment, to, RoleCode.STRING)
    }

    private fun escapeLength(
        text: CharArray,
        backslash: Int,
        end: Int,
    ): Int {
        if (backslash + 1 >= end) return 1
        val digits =
            when (text[backslash + 1]) {
                'u' -> 4
                'U' -> 8
                'x' -> 2
                else -> return 2
            }
        var length = 2
        while (length < 2 + digits && backslash + length < end && isHexDigit(text[backslash + length])) length += 1
        return length
    }

    /** A number, date, time, or date-time from [from]; a date followed by ` HH:` takes the time too. */
    private fun numberEnd(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        var index = from + 1
        while (index < end && isNumberPart(text[index])) index += 1
        if (index - from == 10 && text[from + 4] == '-' && index + 3 < end && text[index] == ' ' &&
            isDigit(text[index + 1]) && isDigit(text[index + 2]) && text[index + 3] == ':'
        ) {
            index += 1
            while (index < end && isNumberPart(text[index])) index += 1
        }
        return index
    }

    private fun isNumberPart(char: Char): Boolean =
        isDigit(char) || isAsciiLetter(char) || char == '_' || char == '.' || char == ':' || char == '+' || char == '-'

    private fun isBareKeyPart(char: Char): Boolean = isAsciiLetter(char) || isDigit(char) || char == '_' || char == '-'

    private fun isTable(
        tables: Int,
        depth: Int,
    ): Boolean = tables and (1 shl (depth - 1)) != 0
}
