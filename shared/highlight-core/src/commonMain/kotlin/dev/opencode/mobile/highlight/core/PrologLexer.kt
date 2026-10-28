package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.SymbolicScan.isNameStart

/**
 * Prolog (ISO and SWI-Prolog).
 *
 * Roles: `%` comments and `/* */` comments are Comment. Quoted atoms (`'...'`), strings
 * (`"..."`), and back-quoted code lists are String, with escapes (`\n`, `\x41\`) and doubled
 * quotes as Constant; one continues onto the next line only after a trailing `\`. Variables
 * (capitalized or starting with `_`) are Variable. An atom before `(` is Function, as is a
 * clause head atom at the start of a line; the predicate a `:-` directive at the start of a line
 * calls (`module`, `use_module`, ...) is Keyword, as are `is`, `dynamic`, `!`, `:-`, `-->`, and
 * `?-`; `true`, `false`, and `fail` are Constant, and `mod`, `rem`, `xor`, and `div` Operator. Numbers (integers, floats, `0x1F`, `0o17`,
 * `0b101`, character codes `0'c`, `_` digit groups) are Number. Other operators are Operator;
 * the clause-ending `.`, brackets, `,`, and `|` are Punctuation.
 *
 * States: 0 in code, [COMMENT] inside a block comment, and [QUOTED], [DOUBLE], or [BACK] inside
 * a quoted item whose line ended with a continuation backslash.
 *
 * Known gaps: user-defined operators are plain atoms; a quoted item ends at its line end unless
 * it continues with `\`, as in strict ISO mode.
 */
internal object PrologLexer : HighlightLexer() {
    private const val COMMENT = 1
    private const val QUOTED = 2
    private const val DOUBLE = 3
    private const val BACK = 4

    // Word kinds.
    private const val KEYWORD = 1
    private const val CONSTANT = 2
    private const val OPERATOR = 3

    private val words = LexerWords()

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        var index = start
        when (state) {
            COMMENT -> {
                val close = LexerScan.blockCommentEnd(text, start, end)
                out.span(start, if (close < 0) end else close, RoleCode.COMMENT)
                if (close < 0) return COMMENT
                index = close
            }
            QUOTED, DOUBLE, BACK -> {
                val close = SymbolicScan.doubledEnd(text, start, end, quoteOf(state), true, RoleCode.STRING, out)
                if (close < 0) return if (close == SymbolicScan.CONTINUED) state else 0
                index = close
            }
        }
        var first = true
        var directive = false
        while (index < end && !out.stopped) {
            val char = text[index]
            val next = if (index + 1 < end) text[index + 1] else ' '
            if (char == ' ' || char == '\t') {
                index += 1
                continue
            }
            val wasFirst = first
            first = false
            val wasDirective = directive
            directive = false
            when {
                char == '%' -> {
                    out.span(index, end, RoleCode.COMMENT)
                    return 0
                }
                char == '/' && next == '*' -> {
                    val close = LexerScan.blockCommentEnd(text, index + 2, end)
                    out.span(index, if (close < 0) end else close, RoleCode.COMMENT)
                    if (close < 0) return COMMENT
                    index = close
                    directive = wasDirective
                    first = wasFirst
                }
                char == '\'' || char == '"' || char == '`' -> {
                    out.span(index, index + 1, RoleCode.STRING)
                    val close = SymbolicScan.doubledEnd(text, index + 1, end, char, true, RoleCode.STRING, out)
                    if (close < 0) {
                        return if (close == SymbolicScan.CONTINUED) {
                            when (char) {
                                '\'' -> QUOTED
                                '"' -> DOUBLE
                                else -> BACK
                            }
                        } else {
                            0
                        }
                    }
                    index = close
                }
                char == '0' && next == '\'' -> {
                    val after = charCodeEnd(text, index + 2, end)
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                }
                isDigit(char) -> {
                    val after = SymbolicScan.numberEnd(text, index, end)
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                }
                isNameStart(char) -> {
                    val after = nameEnd(text, index, end)
                    if (out.collecting) {
                        val role =
                            if (char == '_' || SymbolicScan.isUpper(char)) {
                                RoleCode.VARIABLE
                            } else {
                                val kind = words.lookup(text, index, after)
                                when {
                                    wasDirective -> RoleCode.KEYWORD
                                    kind == KEYWORD -> RoleCode.KEYWORD
                                    kind == OPERATOR -> RoleCode.OPERATOR
                                    kind == CONSTANT && !(after < end && text[after] == '(') -> RoleCode.CONSTANT
                                    (after < end && text[after] == '(') || (wasFirst && index == start) -> RoleCode.FUNCTION
                                    else -> 0
                                }
                            }
                        if (role != 0) out.span(index, after, role)
                    }
                    index = after
                }
                char == '!' || char == ';' -> {
                    out.span(index, index + 1, if (char == '!') RoleCode.KEYWORD else RoleCode.OPERATOR)
                    index += 1
                }
                char == '(' || char == ')' || char == '[' || char == ']' || char == '{' || char == '}' || char == ',' || char == '|' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                }
                isSymbol(char) -> {
                    var after = index + 1
                    while (after < end && isSymbol(text[after]) && !(text[after] == '/' && after + 1 < end && text[after + 1] == '*')) after += 1
                    val length = after - index
                    val role =
                        when {
                            length == 1 && char == '.' && (after == end || text[after] == ' ' || text[after] == '\t' || text[after] == '%') ->
                                RoleCode.PUNCTUATION
                            length == 2 && char == ':' && text[index + 1] == '-' -> RoleCode.KEYWORD
                            length == 2 && char == '?' && text[index + 1] == '-' -> RoleCode.KEYWORD
                            length == 3 && LexerScan.startsWith(text, index, end, "-->") -> RoleCode.KEYWORD
                            else -> RoleCode.OPERATOR
                        }
                    out.span(index, after, role)
                    // `:- module(...)` at the start of a line: the directive's predicate is a keyword.
                    directive = wasFirst && role == RoleCode.KEYWORD && char == ':'
                    index = after
                }
                else -> index += 1
            }
        }
        return 0
    }

    private fun quoteOf(state: Int): Char =
        when (state) {
            QUOTED -> '\''
            DOUBLE -> '"'
            else -> '`'
        }

    /** The end of a `0'c` character code whose character starts at [from]. */
    private fun charCodeEnd(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        if (from >= end) return from
        val char = text[from]
        return when {
            char == '\\' && from + 1 < end -> SymbolicScan.escapeEnd(text, from, end).let { if (it < end && text[it] == '\\' && text[from + 1] == 'x') it + 1 else it }
            char == '\'' && from + 1 < end && text[from + 1] == '\'' -> from + 2
            char == ' ' || char == '\t' -> from
            else -> from + SymbolicScan.charLength(text, from, end)
        }
    }

    private fun nameEnd(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        var index = from
        while (index < end && (isNameStart(text[index]) || isDigit(text[index]))) index += 1
        return index
    }

    private fun isSymbol(char: Char): Boolean =
        when (char) {
            '+', '-', '*', '/', '\\', '^', '<', '>', '=', '~', ':', '.', '?', '@', '#', '&', '$' -> true
            else -> false
        }

    init {
        words.register(KEYWORD, "is", "dynamic", "discontiguous", "initialization", "multifile", "table")
        words.register(CONSTANT, "true", "false", "fail")
        words.register(OPERATOR, "mod", "rem", "xor", "div", "rdiv")
    }
}
