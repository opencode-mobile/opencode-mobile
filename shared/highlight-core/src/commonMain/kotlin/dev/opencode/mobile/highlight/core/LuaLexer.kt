package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * Lua 5.1 to 5.4 (and LuaJIT).
 *
 * Roles: `--` comments and `--[[ ]]` / `--[==[ ]==]` long comments are Comment, as is a `#!`
 * line at the top level. `"..."` and `'...'` strings and `[[ ]]` / `[==[ ]==]` long strings are
 * String, with escapes (`\n`, `\x41`, `\u{48}`, `\065`, `\z`) as Constant. Keywords are
 * Keyword; `true`, `false`, and `nil` are Constant; `self` is Variable. The name after
 * `function` (the last one of `a.b:c`) is Function, and its parameters are Variable; a name
 * called with `(`, a string, a table constructor, or a long string is Function, as is a method
 * after `:`; a name after `.` is Property. Numbers (hex floats too) are Number; operators
 * (`..`, `...`, `//`, `~=`, `#`) are Operator; brackets, `,`, `;`, `.`, `:`, and `::` are
 * Punctuation.
 *
 * States: 0 in code. Bits 30-31 hold the mode: in a long string or long comment, bits 0-15 hold
 * its level (the number of `=`, clamped at [MAX_LEVEL], where any longer closer matches); in a
 * short string continued by a trailing `\` or `\z`, bit 0 is set for `"`.
 *
 * Known gaps: a function's parameter list that runs past its line colors its names plain;
 * `goto` labels and `<const>` attributes are not set apart.
 */
internal object LuaLexer : HighlightLexer() {
    private const val MODE_SHIFT = 30
    private const val LONG_STRING = 1
    private const val LONG_COMMENT = 2
    private const val SHORT_STRING = 3
    private const val MAX_LEVEL = 0xFFFF
    private const val DOUBLE = 1

    // Word kinds.
    private const val KEYWORD = 1
    private const val FUNCTION = 2
    private const val CONSTANT = 3
    private const val SELF = 4

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
            LONG_STRING, LONG_COMMENT -> {
                val role = if (state ushr MODE_SHIFT == LONG_STRING) RoleCode.STRING else RoleCode.COMMENT
                val close = longClose(text, start, end, state and MAX_LEVEL)
                if (close < 0) {
                    out.span(start, end, role)
                    return state
                }
                out.span(start, close, role)
                index = close
            }
            SHORT_STRING -> {
                val quote = if (state and DOUBLE != 0) '"' else '\''
                val close = shortString(text, start, end, quote, out)
                if (close < 0) return if (close == CONTINUES) state else 0
                index = close
            }
            else -> {
                if (end - start > 1 && text[start] == '#' && text[start + 1] == '!') {
                    out.span(start, end, RoleCode.COMMENT)
                    return 0
                }
            }
        }
        var afterFunction = false
        var afterDot = false
        var afterColon = false
        var params = false
        while (index < end && !out.stopped) {
            val char = text[index]
            if (char == ' ' || char == '\t') {
                index += 1
                continue
            }
            val wasAfterFunction = afterFunction
            val wasAfterDot = afterDot
            val wasAfterColon = afterColon
            afterFunction = false
            afterDot = false
            afterColon = false
            val next = if (index + 1 < end) text[index + 1] else '\u0000'
            when {
                char == '-' && next == '-' -> {
                    if (index + 2 < end && text[index + 2] == '[') {
                        val level = longOpenLevel(text, index + 2, end)
                        if (level >= 0) {
                            val body = index + 4 + level
                            val close = longClose(text, body, end, minOf(level, MAX_LEVEL))
                            if (close < 0) {
                                out.span(index, end, RoleCode.COMMENT)
                                return (LONG_COMMENT shl MODE_SHIFT) or minOf(level, MAX_LEVEL)
                            }
                            out.span(index, close, RoleCode.COMMENT)
                            index = close
                            continue
                        }
                    }
                    out.span(index, end, RoleCode.COMMENT)
                    index = end
                }
                char == '"' || char == '\'' -> {
                    out.span(index, index + 1, RoleCode.STRING)
                    val close = shortString(text, index + 1, end, char, out)
                    if (close < 0) {
                        if (close == CONTINUES) return (SHORT_STRING shl MODE_SHIFT) or (if (char == '"') DOUBLE else 0)
                        index = end
                    } else {
                        index = close
                    }
                }
                char == '[' && (next == '[' || next == '=') && longOpenLevel(text, index, end) >= 0 -> {
                    val level = longOpenLevel(text, index, end)
                    val body = index + 2 + level
                    val close = longClose(text, body, end, minOf(level, MAX_LEVEL))
                    if (close < 0) {
                        out.span(index, end, RoleCode.STRING)
                        return (LONG_STRING shl MODE_SHIFT) or minOf(level, MAX_LEVEL)
                    }
                    out.span(index, close, RoleCode.STRING)
                    index = close
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
                    when (kind) {
                        KEYWORD -> out.span(index, after, RoleCode.KEYWORD)
                        FUNCTION -> {
                            out.span(index, after, RoleCode.KEYWORD)
                            afterFunction = true
                        }
                        CONSTANT -> out.span(index, after, RoleCode.CONSTANT)
                        SELF -> out.span(index, after, RoleCode.VARIABLE)
                        else -> {
                            val following = if (after < end) text[after] else '\u0000'
                            when {
                                params -> out.span(index, after, RoleCode.VARIABLE)
                                wasAfterFunction && (following == '.' || following == ':') -> afterFunction = true
                                wasAfterFunction -> {
                                    out.span(index, after, RoleCode.FUNCTION)
                                    afterFunction = true
                                }
                                wasAfterColon || isCall(text, after, end) -> out.span(index, after, RoleCode.FUNCTION)
                                wasAfterDot -> out.span(index, after, RoleCode.PROPERTY)
                            }
                        }
                    }
                    index = after
                }
                char == '.' -> {
                    if (next == '.') {
                        val after = if (index + 2 < end && text[index + 2] == '.') index + 3 else index + 2
                        out.span(index, after, RoleCode.OPERATOR)
                        index = after
                    } else {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        afterDot = true
                        afterFunction = wasAfterFunction
                        index += 1
                    }
                }
                char == ':' -> {
                    if (next == ':') {
                        out.span(index, index + 2, RoleCode.PUNCTUATION)
                        index += 2
                    } else {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        afterColon = true
                        afterFunction = wasAfterFunction
                        index += 1
                    }
                }
                char == '(' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    if (wasAfterFunction) params = true
                    index += 1
                }
                char == ')' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    params = false
                    index += 1
                }
                char == '{' || char == '}' || char == '[' || char == ']' || char == ',' || char == ';' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
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

    private const val CONTINUES = -1
    private const val UNTERMINATED = -2

    /**
     * A short string's text from [from] after its opening [quote]: the offset after the closing
     * quote, [CONTINUES] when a trailing `\` or `\z` carries it to the next line, or
     * [UNTERMINATED] when the line ends first.
     */
    private fun shortString(
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
                out.span(segment, index, RoleCode.STRING)
                if (index + 1 >= end) {
                    out.span(index, end, RoleCode.CONSTANT)
                    return CONTINUES
                }
                val after = escapeEnd(text, index, end)
                out.span(index, after, RoleCode.CONSTANT)
                if (text[index + 1] == 'z' && LexerScan.skipBlanks(text, after, end) == end) return CONTINUES
                index = after
                segment = after
                continue
            }
            index += 1
        }
        out.span(segment, end, RoleCode.STRING)
        return UNTERMINATED
    }

    /** The offset after the escape at [at] (a `\` with at least one character after it). */
    private fun escapeEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        val kind = text[at + 1]
        var index = at + 2
        when {
            kind == 'x' -> while (index < end && index < at + 4 && isHexDigit(text[index])) index += 1
            kind == 'u' && index < end && text[index] == '{' -> {
                index += 1
                while (index < end && index < at + 12 && isHexDigit(text[index])) index += 1
                if (index < end && text[index] == '}') index += 1
            }
            isDigit(kind) -> while (index < end && index < at + 4 && isDigit(text[index])) index += 1
        }
        return index
    }

    /** The level of a long bracket `[`, `=`*, `[` at [at], or -1 when it is not one. */
    private fun longOpenLevel(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at + 1
        while (index < end && text[index] == '=') index += 1
        return if (index < end && text[index] == '[') index - at - 1 else -1
    }

    /** The offset after the `]`, `=`*, `]` closing a long bracket of [level] at or after [from], or -1. */
    private fun longClose(
        text: CharArray,
        from: Int,
        end: Int,
        level: Int,
    ): Int {
        var index = from
        while (index < end) {
            if (text[index] != ']') {
                index += 1
                continue
            }
            var after = index + 1
            while (after < end && text[after] == '=') after += 1
            val count = after - index - 1
            if (after < end && text[after] == ']' && (count == level || (level == MAX_LEVEL && count > level))) return after + 1
            // The `=` run cannot start a closer; the character after it may.
            index = if (after > index + 1) after else index + 1
        }
        return -1
    }

    /** True when the name ending at [at] is called: `(`, a string, `{`, or a long string follows. */
    private fun isCall(
        text: CharArray,
        at: Int,
        end: Int,
    ): Boolean {
        val index = LexerScan.skipBlanks(text, at, end)
        if (index >= end) return false
        val char = text[index]
        if (char == '(' || char == '"' || char == '\'' || char == '{') return true
        return char == '[' && index + 1 < end && (text[index + 1] == '[' || text[index + 1] == '=') && longOpenLevel(text, index, end) >= 0
    }

    private fun numberEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at
        val hex = text[at] == '0' && at + 1 < end && (text[at + 1] == 'x' || text[at + 1] == 'X')
        if (hex) index += 2
        val exponent = if (hex) 'p' else 'e'
        while (index < end) {
            val char = text[index]
            when {
                if (hex) isHexDigit(char) else isDigit(char) -> index += 1
                char == '.' && !(index + 1 < end && text[index + 1] == '.') -> index += 1
                (char == exponent || char == exponent.uppercaseChar()) -> {
                    index += 1
                    if (index < end && (text[index] == '+' || text[index] == '-')) index += 1
                    while (index < end && isDigit(text[index])) index += 1
                    return index
                }
                else -> return index
            }
        }
        return index
    }

    private fun isOperator(char: Char): Boolean =
        when (char) {
            '+', '-', '*', '/', '%', '^', '#', '&', '~', '|', '<', '>', '=' -> true
            else -> false
        }

    private fun isNameStart(char: Char): Boolean = isAsciiLetter(char) || char == '_'

    private fun isNamePart(char: Char): Boolean = isAsciiLetter(char) || isDigit(char) || char == '_'

    init {
        words.register(
            KEYWORD,
            "and", "break", "do", "else", "elseif", "end", "for", "goto", "if", "in", "local", "not", "or", "repeat", "return",
            "then", "until", "while",
        )
        words.register(FUNCTION, "function")
        words.register(CONSTANT, "true", "false", "nil")
        words.register(SELF, "self")
    }
}
