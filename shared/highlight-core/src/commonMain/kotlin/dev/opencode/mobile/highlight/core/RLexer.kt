package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * R.
 *
 * Roles: `#` comments are Comment. `"..."` and `'...'` strings (which may span lines) and raw
 * strings (`r"(...)"`, `R'[...]'`, `r"---{...}---"`) are String, with escapes as Constant.
 * Keywords (`function`, `if`, `for`, `return`, ...) and the `\(x)` lambda's `\` are Keyword;
 * `TRUE`, `FALSE`, `NULL`, `NA` (and its typed forms), `Inf`, and `NaN` are Constant. A name
 * called with `(` is Function, a package before `::` or `:::` is Type, a name after `$` or `@`
 * is Property, and a function's parameters are Variable. Backtick names are one token, Function
 * when called. Numbers (hex, `1e3`, `5L`, `2i`) are Number; operators (`<-`, `<<-`, `->`, `|>`,
 * `%in%`, `%>%`, `$`, `@`, `::`) are Operator; brackets, `,`, and `;` are Punctuation.
 *
 * States: 0 in code. Bits 30-31 hold the mode: in a string, bit 0 is set for `"`; in a raw
 * string, bit 0 is set for `"`, bits 1-2 hold its bracket (`(`, `[`, `{`), and bits 3-18 its
 * dash count (clamped; any longer run closes a clamped one).
 *
 * Known gaps: a parameter list that runs past its line colors its names plain; backtick names
 * end at the line end; formula and `.`-prefixed names are not set apart.
 */
internal object RLexer : HighlightLexer() {
    private const val MODE_SHIFT = 30
    private const val STRING = 1
    private const val RAW = 2
    private const val DOUBLE = 1
    private const val BRACKET_SHIFT = 1
    private const val DASH_SHIFT = 3
    private const val MAX_DASHES = 0xFFFF

    // Word kinds.
    private const val KEYWORD = 1
    private const val FUNCTION = 2
    private const val CONSTANT = 3

    private val words = LexerWords()

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        var index = start
        when (state ushr MODE_SHIFT) {
            STRING -> {
                val close = quoted(text, start, end, if (state and DOUBLE != 0) '"' else '\'', out)
                if (close < 0) return state
                index = close
            }
            RAW -> {
                val close = rawClose(text, start, end, state)
                if (close < 0) {
                    out.span(start, end, RoleCode.STRING)
                    return state
                }
                out.span(start, close, RoleCode.STRING)
                index = close
            }
        }
        var afterFunction = false
        var afterMember = false
        var paramDepth = 0
        var expectParam = false
        while (index < end && !out.stopped) {
            val char = text[index]
            if (char == ' ' || char == '\t') {
                index += 1
                continue
            }
            val wasAfterFunction = afterFunction
            val wasAfterMember = afterMember
            val wasExpectParam = expectParam
            afterFunction = false
            afterMember = false
            expectParam = false
            val next = if (index + 1 < end) text[index + 1] else '\u0000'
            when {
                char == '#' -> {
                    out.span(index, end, RoleCode.COMMENT)
                    index = end
                }
                (char == 'r' || char == 'R') && (next == '"' || next == '\'') && rawOpen(text, index, end) > 0 -> {
                    val body = rawOpen(text, index, end)
                    val raw = rawState(text, index, body)
                    val close = rawClose(text, body, end, raw)
                    if (close < 0) {
                        out.span(index, end, RoleCode.STRING)
                        return raw
                    }
                    out.span(index, close, RoleCode.STRING)
                    index = close
                }
                char == '"' || char == '\'' -> {
                    out.span(index, index + 1, RoleCode.STRING)
                    val close = quoted(text, index + 1, end, char, out)
                    if (close < 0) return (STRING shl MODE_SHIFT) or (if (char == '"') DOUBLE else 0)
                    index = close
                }
                char == '`' -> {
                    var after = index + 1
                    while (after < end && text[after] != '`') after += if (text[after] == '\\' && after + 1 < end) 2 else 1
                    after = minOf(after + 1, end)
                    if (paramDepth == 1 && wasExpectParam) {
                        out.span(index, after, RoleCode.VARIABLE)
                    } else if (isCall(text, after, end)) {
                        out.span(index, after, RoleCode.FUNCTION)
                    }
                    index = after
                }
                isDigit(char) || (char == '.' && isDigit(next)) -> {
                    val after = numberEnd(text, index, end)
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                }
                isNameStart(char) -> {
                    var after = index + 1
                    while (after < end && isNamePart(text[after])) after += 1
                    val kind = words.lookup(text, index, after)
                    when {
                        kind == KEYWORD -> out.span(index, after, RoleCode.KEYWORD)
                        kind == FUNCTION -> {
                            out.span(index, after, RoleCode.KEYWORD)
                            afterFunction = true
                        }
                        kind == CONSTANT -> out.span(index, after, RoleCode.CONSTANT)
                        paramDepth == 1 && wasExpectParam -> out.span(index, after, RoleCode.VARIABLE)
                        after + 1 < end && text[after] == ':' && text[after + 1] == ':' -> out.span(index, after, RoleCode.TYPE)
                        isCall(text, after, end) -> out.span(index, after, RoleCode.FUNCTION)
                        wasAfterMember -> out.span(index, after, RoleCode.PROPERTY)
                    }
                    index = after
                }
                char == '\\' && next == '(' -> {
                    // `\(x) x + 1`, the short lambda.
                    out.span(index, index + 1, RoleCode.KEYWORD)
                    afterFunction = true
                    index += 1
                }
                char == '(' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    if (wasAfterFunction && paramDepth == 0) {
                        paramDepth = 1
                        expectParam = true
                    } else if (paramDepth > 0) {
                        paramDepth += 1
                    }
                    index += 1
                }
                char == ')' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    if (paramDepth > 0) paramDepth -= 1
                    index += 1
                }
                char == ',' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    expectParam = paramDepth == 1
                    index += 1
                }
                char == '[' || char == ']' || char == '{' || char == '}' || char == ';' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                }
                char == '%' -> {
                    var after = index + 1
                    while (after < end && text[after] != '%') after += 1
                    after = if (after < end) after + 1 else index + 1
                    out.span(index, after, RoleCode.OPERATOR)
                    index = after
                }
                char == '$' || char == '@' -> {
                    out.span(index, index + 1, RoleCode.OPERATOR)
                    afterMember = true
                    index += 1
                }
                char == ':' -> {
                    var after = index + 1
                    while (after < end && after < index + 3 && text[after] == ':') after += 1
                    out.span(index, after, RoleCode.OPERATOR)
                    index = after
                }
                isOperator(char) -> {
                    out.span(index, index + 1, RoleCode.OPERATOR)
                    index += 1
                }
                else -> index += 1
            }
        }
        return 0
    }

    /**
     * A string's text from [from] up to its closing [quote]: the offset after it, or -1 when the
     * string runs past the line.
     */
    private fun quoted(
        text: CharArray,
        from: Int,
        end: Int,
        quote: Char,
        out: SpanSink,
    ): Int {
        var segment = from
        var index = from
        while (index < end) {
            val char = text[index]
            if (char == quote) {
                out.span(segment, index + 1, RoleCode.STRING)
                return index + 1
            }
            if (char == '\\') {
                val after = escapeEnd(text, index, end)
                out.span(segment, index, RoleCode.STRING)
                out.span(index, after, RoleCode.CONSTANT)
                index = after
                segment = after
                continue
            }
            index += 1
        }
        out.span(segment, end, RoleCode.STRING)
        return -1
    }

    private fun escapeEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        if (at + 1 >= end) return end
        val kind = text[at + 1]
        var index = at + 2
        val digits =
            when (kind) {
                'x' -> 2
                'u' -> 4
                'U' -> 8
                else -> 0
            }
        if (digits > 0) {
            if (index < end && text[index] == '{') {
                index += 1
                while (index < end && index < at + 4 + digits && isHexDigit(text[index])) index += 1
                if (index < end && text[index] == '}') index += 1
            } else {
                while (index < end && index < at + 2 + digits && isHexDigit(text[index])) index += 1
            }
        } else if (kind in '0'..'7') {
            while (index < end && index < at + 4 && text[index] in '0'..'7') index += 1
        }
        return index
    }

    /** The offset after the opening bracket of a raw string at [at] (`r"---(`), or 0 when it is not one. */
    private fun rawOpen(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at + 2
        while (index < end && text[index] == '-') index += 1
        if (index >= end) return 0
        val bracket = text[index]
        return if (bracket == '(' || bracket == '[' || bracket == '{') index + 1 else 0
    }

    /** The raw-string state for the opener `text[at, body)`. */
    private fun rawState(
        text: CharArray,
        at: Int,
        body: Int,
    ): Int {
        val bracket =
            when (text[body - 1]) {
                '(' -> 0
                '[' -> 1
                else -> 2
            }
        val dashes = minOf(body - at - 3, MAX_DASHES)
        val quote = if (text[at + 1] == '"') DOUBLE else 0
        return (RAW shl MODE_SHIFT) or quote or (bracket shl BRACKET_SHIFT) or (dashes shl DASH_SHIFT)
    }

    /** The offset after the closer of the raw string [state] at or after [from], or -1. */
    private fun rawClose(
        text: CharArray,
        from: Int,
        end: Int,
        state: Int,
    ): Int {
        val closer =
            when ((state ushr BRACKET_SHIFT) and 3) {
                0 -> ')'
                1 -> ']'
                else -> '}'
            }
        val quote = if (state and DOUBLE != 0) '"' else '\''
        val dashes = (state ushr DASH_SHIFT) and MAX_DASHES
        var index = from
        while (index < end) {
            if (text[index] != closer) {
                index += 1
                continue
            }
            var after = index + 1
            while (after < end && text[after] == '-') after += 1
            val count = after - index - 1
            if (after < end && text[after] == quote && (count == dashes || (dashes == MAX_DASHES && count > dashes))) return after + 1
            index = after
        }
        return -1
    }

    /** True when `(` follows the name ending at [at], after blanks. */
    private fun isCall(
        text: CharArray,
        at: Int,
        end: Int,
    ): Boolean {
        val index = LexerScan.skipBlanks(text, at, end)
        return index < end && text[index] == '('
    }

    private fun numberEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at
        if (text[at] == '0' && at + 1 < end && (text[at + 1] == 'x' || text[at + 1] == 'X')) {
            index += 2
            while (index < end && (isHexDigit(text[index]) || text[index] == '.')) index += 1
            if (index < end && (text[index] == 'p' || text[index] == 'P')) index = exponentEnd(text, index, end)
        } else {
            while (index < end && (isDigit(text[index]) || text[index] == '.')) index += 1
            if (index < end && (text[index] == 'e' || text[index] == 'E')) index = exponentEnd(text, index, end)
        }
        if (index < end && (text[index] == 'L' || text[index] == 'i')) index += 1
        return index
    }

    private fun exponentEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at + 1
        if (index < end && (text[index] == '+' || text[index] == '-')) index += 1
        while (index < end && isDigit(text[index])) index += 1
        return index
    }

    private fun isOperator(char: Char): Boolean =
        when (char) {
            '+', '-', '*', '/', '^', '<', '>', '=', '!', '&', '|', '~', '?' -> true
            else -> false
        }

    private fun isNameStart(char: Char): Boolean = isAsciiLetter(char) || char == '.'

    private fun isNamePart(char: Char): Boolean = isAsciiLetter(char) || isDigit(char) || char == '.' || char == '_'

    init {
        words.register(KEYWORD, "if", "else", "repeat", "while", "for", "in", "next", "break", "return")
        words.register(FUNCTION, "function")
        words.register(
            CONSTANT,
            "TRUE", "FALSE", "NULL", "NA", "Inf", "NaN", "NA_integer_", "NA_real_", "NA_character_", "NA_complex_",
        )
    }
}
