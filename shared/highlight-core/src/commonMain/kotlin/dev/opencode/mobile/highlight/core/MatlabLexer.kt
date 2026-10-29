package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * MATLAB (and the Octave code that reads like it).
 *
 * Roles: `%` comments and `%{` … `%}` block comments (each marker alone on its line; they nest)
 * Comment; `...` Operator with the rest of its line Comment; `'…'` and `"…"` strings String with
 * doubled quotes Constant; numbers (`1e3`, `2i`, `0x1F`, `0b101`) Number; keywords Keyword
 * (`properties`, `methods`, `events`, `enumeration`, and `arguments` only where they open a
 * block); `end` inside `()`, `[]`, or `{}` Constant, elsewhere Keyword; `true`, `false`, `pi`,
 * `Inf`, `NaN`, `eps` and friends Constant; `nargin`, `varargin` and friends Variable; a
 * `function` line's output and input names Variable and its name Function; a field after `.`
 * Property; operators (`'` and `.'` transposes included) Operator; brackets, `,`, `;`, `.`
 * Punctuation. Command syntax (`hold on`, `format long`): a lowercase name opening a
 * statement and followed by a blank and a word is Function, and its arguments up to `;`, `,`, or
 * `%` String. A `!` opening a statement passes the line to the shell: String.
 *
 * A `'` is a transpose right after a name, a number, a closing bracket, or another transpose,
 * with no blank between; anywhere else it opens a string.
 *
 * States: the block comment depth (bits 0 to 3, capped at 15), how many `[` and `{` are open
 * (bits 4 to 6, capped at 7; a matrix spans lines), and after a line ending with `...` the open
 * parentheses (bits 7 to 9) and a continuation flag (bit 10). Strings end with their line.
 */
internal object MatlabLexer : HighlightLexer() {
    private const val COMMENT_MASK = 15
    private const val BRACKET_SHIFT = 4
    private const val PAREN_SHIFT = 7
    private const val MAX_DEPTH = 7
    private const val CONTINUED = 1 shl 10

    // Word kinds.
    private const val KEYWORD = 1
    private const val BLOCK = 2
    private const val END = 3
    private const val FUNCTION = 4
    private const val CONSTANT = 5
    private const val VARIABLE = 6

    private val words =
        LexerWords().apply {
            register(
                KEYWORD, "if", "elseif", "else", "for", "parfor", "while", "switch", "case", "otherwise", "try", "catch", "return",
                "break", "continue", "global", "persistent", "classdef", "spmd", "do", "until", "unwind_protect",
                "unwind_protect_cleanup", "endfunction", "endif", "endfor", "endwhile", "endswitch",
            )
            register(BLOCK, "properties", "methods", "events", "enumeration", "arguments")
            register(END, "end")
            register(FUNCTION, "function")
            register(CONSTANT, "true", "false", "pi", "Inf", "inf", "NaN", "nan", "eps", "realmax", "realmin", "intmax", "intmin", "NA")
            register(VARIABLE, "nargin", "nargout", "varargin", "varargout")
        }

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int = MatlabLine(text, start, end, out).run(state)

    private class MatlabLine(
        private val text: CharArray,
        private val start: Int,
        private val end: Int,
        private val out: SpanSink,
    ) {
        private var index = start
        private var brackets = 0
        private var parens = 0
        private var continued = false

        // Per line only.
        private var valueBefore = false
        private var statementStart = false
        private var afterDot = false

        fun run(state: Int): Int {
            val commentDepth = state and COMMENT_MASK
            val markerAt = LexerScan.skipBlanks(text, start, end)
            if (commentDepth > 0) {
                out.span(start, end, RoleCode.COMMENT)
                val nested =
                    when {
                        isMarkerLine(markerAt, '{') -> minOf(commentDepth + 1, COMMENT_MASK)
                        isMarkerLine(markerAt, '}') -> commentDepth - 1
                        else -> commentDepth
                    }
                return nested or (state and COMMENT_MASK.inv())
            }
            brackets = (state ushr BRACKET_SHIFT) and MAX_DEPTH
            val wasContinued = state and CONTINUED != 0
            parens = if (wasContinued) (state ushr PAREN_SHIFT) and MAX_DEPTH else 0
            if (isMarkerLine(markerAt, '{')) {
                out.span(start, end, RoleCode.COMMENT)
                return 1 or (brackets shl BRACKET_SHIFT)
            }
            statementStart = !wasContinued && brackets == 0
            while (index < end && !out.stopped) {
                val char = text[index]
                if (char == ' ' || char == '\t' || char == '\r') {
                    index += 1
                    valueBefore = false
                    continue
                }
                val wasStatementStart = statementStart
                statementStart = false
                val wasAfterDot = afterDot
                afterDot = false
                when {
                    char == '%' -> {
                        out.span(index, end, RoleCode.COMMENT)
                        index = end
                    }
                    char == '.' && LexerScan.startsWith(text, index, end, "...") -> {
                        out.span(index, index + 3, RoleCode.OPERATOR)
                        out.span(index + 3, end, RoleCode.COMMENT)
                        index = end
                        continued = true
                    }
                    char == '\'' && valueBefore -> {
                        out.span(index, index + 1, RoleCode.OPERATOR)
                        index += 1
                    }
                    char == '\'' || char == '"' -> {
                        string(char)
                        valueBefore = true
                    }
                    char == '!' && wasStatementStart && !(index + 1 < end && text[index + 1] == '=') -> {
                        out.span(index, end, RoleCode.STRING)
                        index = end
                    }
                    isDigit(char) || (char == '.' && index + 1 < end && isDigit(text[index + 1])) -> {
                        number()
                        valueBefore = true
                    }
                    isAsciiLetter(char) -> word(wasStatementStart, wasAfterDot)
                    else -> symbol(char)
                }
            }
            var result = (minOf(brackets, MAX_DEPTH) shl BRACKET_SHIFT)
            if (continued) result = result or CONTINUED or (minOf(parens, MAX_DEPTH) shl PAREN_SHIFT)
            return result
        }

        /** True when the line holds only `%` + [brace] (a block comment marker) from [at], blanks around. */
        private fun isMarkerLine(
            at: Int,
            brace: Char,
        ): Boolean {
            if (at + 1 >= end || text[at] != '%' || text[at + 1] != brace) return false
            var after = at + 2
            while (after < end && (text[after] == ' ' || text[after] == '\t' || text[after] == '\r')) after += 1
            return after == end
        }

        private fun isWordPart(char: Char): Boolean = isAsciiLetter(char) || isDigit(char) || char == '_'

        private fun wordEnd(from: Int): Int {
            var at = from
            while (at < end && isWordPart(text[at])) at += 1
            return at
        }

        private fun symbol(char: Char) {
            val next = if (index + 1 < end) text[index + 1] else ' '
            when (char) {
                '(' -> {
                    open()
                    parens += 1
                }
                '[', '{' -> {
                    open()
                    brackets += 1
                }
                ')' -> {
                    if (parens > 0) parens -= 1
                    close()
                }
                ']', '}' -> {
                    if (brackets > 0) brackets -= 1
                    close()
                }
                ';', ',' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    valueBefore = false
                    statementStart = parens == 0 && brackets == 0
                }
                '.' ->
                    when (next) {
                        '\'' -> {
                            out.span(index, index + 2, RoleCode.OPERATOR)
                            index += 2
                            valueBefore = true
                        }
                        '*', '/', '\\', '^' -> operator(2)
                        else -> {
                            out.span(index, index + 1, RoleCode.PUNCTUATION)
                            index += 1
                            valueBefore = false
                            afterDot = true
                        }
                    }
                '=', '~', '<', '>', '!' -> operator(if (next == '=') 2 else 1)
                '&' -> operator(if (next == '&') 2 else 1)
                '|' -> operator(if (next == '|') 2 else 1)
                '+', '-', '*', '/', '\\', '^', ':', '@' -> operator(1)
                else -> {
                    index += 1
                    valueBefore = false
                }
            }
        }

        private fun open() {
            out.span(index, index + 1, RoleCode.PUNCTUATION)
            index += 1
            valueBefore = false
        }

        private fun close() {
            out.span(index, index + 1, RoleCode.PUNCTUATION)
            index += 1
            valueBefore = true
        }

        private fun operator(length: Int) {
            out.span(index, index + length, RoleCode.OPERATOR)
            index += length
            valueBefore = false
        }

        private fun word(
            statementStart: Boolean,
            afterDot: Boolean,
        ) {
            val from = index
            val to = wordEnd(from)
            index = to
            valueBefore = true
            if (afterDot) {
                out.span(from, to, RoleCode.PROPERTY)
                return
            }
            when (words.lookup(text, from, to)) {
                KEYWORD -> {
                    out.span(from, to, RoleCode.KEYWORD)
                    valueBefore = false
                }
                FUNCTION -> {
                    out.span(from, to, RoleCode.KEYWORD)
                    valueBefore = false
                    signature()
                }
                END ->
                    if (parens > 0 || brackets > 0) {
                        out.span(from, to, RoleCode.CONSTANT)
                    } else {
                        out.span(from, to, RoleCode.KEYWORD)
                        valueBefore = false
                    }
                BLOCK -> {
                    val after = LexerScan.skipBlanks(text, to, end)
                    val call = to < end && (text[to] == '(' || text[to] == '.') || (after < end && text[after] == '=')
                    if (statementStart && !call) {
                        out.span(from, to, RoleCode.KEYWORD)
                        valueBefore = false
                    }
                }
                CONSTANT -> out.span(from, to, RoleCode.CONSTANT)
                VARIABLE -> out.span(from, to, RoleCode.VARIABLE)
                else -> if (statementStart && text[from] in 'a'..'z') command(from, to)
            }
        }

        /** Command syntax after the name `text[from, to)`: a blank, then a word or `-option`. */
        private fun command(
            from: Int,
            to: Int,
        ) {
            if (to >= end || (text[to] != ' ' && text[to] != '\t')) return
            val argument = LexerScan.skipBlanks(text, to, end)
            if (argument >= end) return
            val char = text[argument]
            val opens = isAsciiLetter(char) || (char == '-' && argument + 1 < end && isAsciiLetter(text[argument + 1]))
            if (!opens) return
            // `x y = 1` or `a b(1)` would be an expression error in MATLAB; still require a word that is not assigned.
            val wordAfter = wordEnd(argument + 1)
            val afterWord = LexerScan.skipBlanks(text, wordAfter, end)
            if (afterWord < end && text[afterWord] == '=' && !(afterWord + 1 < end && text[afterWord + 1] == '=')) return
            var stop = argument
            while (stop < end && text[stop] != ';' && text[stop] != ',' && text[stop] != '%') stop += 1
            var contentEnd = stop
            while (contentEnd > argument && (text[contentEnd - 1] == ' ' || text[contentEnd - 1] == '\t' || text[contentEnd - 1] == '\r')) contentEnd -= 1
            out.span(from, to, RoleCode.FUNCTION)
            out.span(argument, contentEnd, RoleCode.STRING)
            index = stop
            valueBefore = false
        }

        /**
         * A `function` line after the keyword: `function name`, `function out = name(in)`, or
         * `function [a, b] = name(in, ~)`. Stops at anything else, which the main loop lexes.
         */
        private fun signature() {
            var at = LexerScan.skipBlanks(text, index, end)
            if (at >= end) return
            if (text[at] == '[') {
                out.span(at, at + 1, RoleCode.PUNCTUATION)
                at = names(at + 1, RoleCode.VARIABLE)
                if (at >= end || text[at] != ']') {
                    index = at
                    return
                }
                out.span(at, at + 1, RoleCode.PUNCTUATION)
                at = LexerScan.skipBlanks(text, at + 1, end)
                if (at >= end || text[at] != '=') {
                    index = at
                    return
                }
                out.span(at, at + 1, RoleCode.OPERATOR)
                at = LexerScan.skipBlanks(text, at + 1, end)
            } else if (isAsciiLetter(text[at])) {
                val first = wordEnd(at)
                val after = LexerScan.skipBlanks(text, first, end)
                if (after < end && text[after] == '=' && !(after + 1 < end && text[after + 1] == '=')) {
                    out.span(at, first, RoleCode.VARIABLE)
                    out.span(after, after + 1, RoleCode.OPERATOR)
                    at = LexerScan.skipBlanks(text, after + 1, end)
                }
            }
            if (at >= end || !isAsciiLetter(text[at])) {
                index = at
                return
            }
            // The name, with `get.`/`set.` property accessors.
            var nameEnd = wordEnd(at)
            while (nameEnd + 1 < end && text[nameEnd] == '.' && isAsciiLetter(text[nameEnd + 1])) nameEnd = wordEnd(nameEnd + 1)
            out.span(at, nameEnd, RoleCode.FUNCTION)
            at = LexerScan.skipBlanks(text, nameEnd, end)
            if (at < end && text[at] == '(') {
                out.span(at, at + 1, RoleCode.PUNCTUATION)
                at = names(at + 1, RoleCode.VARIABLE)
                if (at < end && text[at] == ')') {
                    out.span(at, at + 1, RoleCode.PUNCTUATION)
                    at += 1
                } else {
                    // Parameters continued on the next line keep their parenthesis open.
                    parens += 1
                }
            }
            index = at
            valueBefore = false
        }

        /** Names separated by blanks and commas from [from] ([role]; `~` Operator); returns where they stop. */
        private fun names(
            from: Int,
            role: Int,
        ): Int {
            var at = from
            while (at < end) {
                val char = text[at]
                when {
                    char == ' ' || char == '\t' -> at += 1
                    char == ',' -> {
                        out.span(at, at + 1, RoleCode.PUNCTUATION)
                        at += 1
                    }
                    char == '~' -> {
                        out.span(at, at + 1, RoleCode.OPERATOR)
                        at += 1
                    }
                    isAsciiLetter(char) -> {
                        val wordEnd = wordEnd(at)
                        out.span(at, wordEnd, role)
                        at = wordEnd
                    }
                    else -> return at
                }
            }
            return at
        }

        private fun number() {
            val from = index
            var at = index
            var based = true
            if (text[at] == '0' && at + 2 < end && (text[at + 1] == 'x' || text[at + 1] == 'X') && isHexDigit(text[at + 2])) {
                at += 2
                while (at < end && isHexDigit(text[at])) at += 1
            } else if (text[at] == '0' && at + 2 < end && (text[at + 1] == 'b' || text[at + 1] == 'B') && (text[at + 2] == '0' || text[at + 2] == '1')) {
                at += 2
                while (at < end && (text[at] == '0' || text[at] == '1')) at += 1
            } else {
                based = false
                while (at < end && isDigit(text[at])) at += 1
                if (at + 1 < end && text[at] == '.' && isDigit(text[at + 1])) {
                    at += 1
                    while (at < end && isDigit(text[at])) at += 1
                } else if (at < end && text[at] == '.' && !(at + 1 < end && (text[at + 1] == '*' || text[at + 1] == '/' || text[at + 1] == '\\' || text[at + 1] == '^' || text[at + 1] == '\'' || text[at + 1] == '.'))) {
                    at += 1
                }
                if (at < end && (text[at] == 'e' || text[at] == 'E')) {
                    var exponent = at + 1
                    if (exponent < end && (text[exponent] == '+' || text[exponent] == '-')) exponent += 1
                    if (exponent < end && isDigit(text[exponent])) {
                        at = exponent
                        while (at < end && isDigit(text[at])) at += 1
                    }
                }
                if (at < end && (text[at] == 'i' || text[at] == 'j') && !(at + 1 < end && isWordPart(text[at + 1]))) at += 1
            }
            // Integer type suffixes of hex and binary literals (`0xFFu8`, `0b1s16`).
            if (based && at + 1 < end && (text[at] == 'u' || text[at] == 's') && isDigit(text[at + 1])) {
                at += 1
                while (at < end && isDigit(text[at])) at += 1
            }
            out.span(from, at, RoleCode.NUMBER)
            index = at
        }

        /** A `'` or `"` string at [index]; a doubled quote is an escape (Constant). It ends with its line. */
        private fun string(quote: Char) {
            var segment = index
            var at = index + 1
            while (at < end) {
                if (text[at] == quote) {
                    if (at + 1 < end && text[at + 1] == quote) {
                        out.span(segment, at, RoleCode.STRING)
                        out.span(at, at + 2, RoleCode.CONSTANT)
                        at += 2
                        segment = at
                        continue
                    }
                    at += 1
                    out.span(segment, at, RoleCode.STRING)
                    index = at
                    return
                }
                at += 1
            }
            out.span(segment, end, RoleCode.STRING)
            index = end
        }
    }
}
