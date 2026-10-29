package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * AWK (POSIX awk, gawk, mawk).
 *
 * Roles: `#` comments are Comment. `"..."` strings and regex literals (`/.../`, read where a
 * value is expected, with `[...]` bracket expressions) are String, with escapes as Constant.
 * The patterns `BEGIN`, `END`, `BEGINFILE`, and `ENDFILE` and statements (`if`, `print`,
 * `getline`, `next`, `delete`, `function`, `in`, ...) are Keyword. Fields (`$1`, `$NF`, the `$`
 * of `$(i + 1)`) and built-in variables (`NR`, `FS`, `OFS`, `FILENAME`, ...) are Variable.
 * Built-in functions (`length`, `substr`, `gsub`, `sprintf`, ...), calls of user functions, and
 * the name after `function` are Function; that function's parameters are Variable. Numbers are
 * Number; operators (`~`, `!~`, `&&`, `>>`, `|&`, ...) are Operator; brackets, `,`, and `;` are
 * Punctuation.
 *
 * States: 0, or 1 inside a string whose line ended in `\` (a gawk continuation).
 *
 * Known gaps: a `/` after `)` or a name always reads as division; a parameter list that runs
 * past its line colors its names plain.
 */
internal object AwkLexer : HighlightLexer() {
    private const val IN_STRING = 1

    // Word kinds.
    private const val KEYWORD = 1
    private const val VALUE_KEYWORD = 2
    private const val FUNCTION_KEYWORD = 3
    private const val BUILTIN = 4
    private const val VARIABLE = 5
    private const val PATTERN = 6

    private val words = LexerWords()

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        var index = start
        if (state == IN_STRING) {
            val close = string(text, start, end, out)
            if (close < 0) return if (close == CONTINUES) IN_STRING else 0
            index = close
        }
        var valueEnded = false
        var afterFunction = false
        var params = false
        while (index < end && !out.stopped) {
            val char = text[index]
            if (char == ' ' || char == '\t') {
                index += 1
                continue
            }
            val wasAfterFunction = afterFunction
            afterFunction = false
            val next = if (index + 1 < end) text[index + 1] else '\u0000'
            when {
                char == '#' -> {
                    out.span(index, end, RoleCode.COMMENT)
                    index = end
                }
                char == '"' -> {
                    out.span(index, index + 1, RoleCode.STRING)
                    val close = string(text, index + 1, end, out)
                    if (close == CONTINUES) return IN_STRING
                    index = if (close < 0) end else close
                    valueEnded = true
                }
                char == '/' && !valueEnded -> {
                    index = regex(text, index, end, out)
                    valueEnded = true
                }
                isDigit(char) || (char == '.' && isDigit(next)) -> {
                    val after = numberEnd(text, index, end)
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                    valueEnded = true
                }
                char == '$' -> {
                    var after = index + 1
                    if (after < end && isDigit(text[after])) {
                        while (after < end && isDigit(text[after])) after += 1
                    } else if (after < end && isNameStart(text[after])) {
                        while (after < end && isNamePart(text[after])) after += 1
                    }
                    out.span(index, after, RoleCode.VARIABLE)
                    index = after
                    valueEnded = true
                }
                isNameStart(char) -> {
                    var after = index + 1
                    while (after < end && isNamePart(text[after])) after += 1
                    val kind = words.lookup(text, index, after)
                    valueEnded = true
                    when {
                        kind == KEYWORD -> {
                            out.span(index, after, RoleCode.KEYWORD)
                            valueEnded = false
                        }
                        kind == VALUE_KEYWORD -> out.span(index, after, RoleCode.KEYWORD)
                        kind == PATTERN -> out.span(index, after, RoleCode.KEYWORD)
                        kind == FUNCTION_KEYWORD -> {
                            out.span(index, after, RoleCode.KEYWORD)
                            afterFunction = true
                            valueEnded = false
                        }
                        kind == BUILTIN -> out.span(index, after, RoleCode.FUNCTION)
                        kind == VARIABLE -> out.span(index, after, RoleCode.VARIABLE)
                        wasAfterFunction -> {
                            out.span(index, after, RoleCode.FUNCTION)
                            afterFunction = true
                        }
                        params -> out.span(index, after, RoleCode.VARIABLE)
                        after < end && text[after] == '(' -> out.span(index, after, RoleCode.FUNCTION)
                    }
                    index = after
                }
                char == '(' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    if (wasAfterFunction) params = true
                    index += 1
                    valueEnded = false
                }
                char == ')' || char == ']' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    if (char == ')') params = false
                    index += 1
                    valueEnded = true
                }
                char == '[' || char == '{' || char == '}' || char == ',' || char == ';' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    valueEnded = false
                }
                isOperator(char) -> {
                    var after = index + 1
                    while (after < end && after < index + 3 && isOperator(text[after]) && text[after] != '/') after += 1
                    out.span(index, after, RoleCode.OPERATOR)
                    // `x++ / 2` divides; any other operator expects a value.
                    val last = text[after - 1]
                    valueEnded = after - index >= 2 && (last == '+' || last == '-') && text[after - 2] == last && valueEnded
                    index = after
                }
                else -> index += 1
            }
        }
        return 0
    }

    private const val CONTINUES = -1
    private const val UNTERMINATED = -2

    /** A string's text from [from]: the offset after its close, [CONTINUES] on a trailing `\`, or [UNTERMINATED]. */
    private fun string(
        text: CharArray,
        from: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        var segment = from
        var index = from
        while (index < end) {
            val char = text[index]
            if (char == '"') {
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
                index = after
                segment = after
                continue
            }
            index += 1
        }
        out.span(segment, end, RoleCode.STRING)
        return UNTERMINATED
    }

    /** A regex literal from the `/` at [at]; returns the offset after it (or the line end). */
    private fun regex(
        text: CharArray,
        at: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        var segment = at
        var index = at + 1
        var inBracket = false
        while (index < end) {
            val char = text[index]
            when {
                char == '\\' -> {
                    val after = minOf(index + 2, end)
                    out.span(segment, index, RoleCode.STRING)
                    out.span(index, after, RoleCode.CONSTANT)
                    index = after
                    segment = after
                    continue
                }
                inBracket -> {
                    if (char == ']') inBracket = false
                }
                char == '[' -> {
                    inBracket = true
                    // `[]...]` and `[^]...]` keep a leading `]` literal.
                    if (index + 1 < end && text[index + 1] == '^') index += 1
                    if (index + 1 < end && text[index + 1] == ']') index += 1
                }
                char == '/' -> {
                    out.span(segment, index + 1, RoleCode.STRING)
                    return index + 1
                }
            }
            index += 1
        }
        out.span(segment, end, RoleCode.STRING)
        return end
    }

    private fun escapeEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at + 2
        val kind = text[at + 1]
        if (kind in '0'..'7') {
            while (index < end && index < at + 4 && text[index] in '0'..'7') index += 1
        } else if (kind == 'x') {
            while (index < end && index < at + 4 && isHexDigit(text[index])) index += 1
        }
        return index
    }

    private fun numberEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at
        if (text[at] == '0' && at + 1 < end && (text[at + 1] == 'x' || text[at + 1] == 'X')) {
            index += 2
            while (index < end && isHexDigit(text[index])) index += 1
            return index
        }
        while (index < end && (isDigit(text[index]) || text[index] == '.')) index += 1
        if (index < end && (text[index] == 'e' || text[index] == 'E')) {
            var exponent = index + 1
            if (exponent < end && (text[exponent] == '+' || text[exponent] == '-')) exponent += 1
            if (exponent < end && isDigit(text[exponent])) {
                index = exponent
                while (index < end && isDigit(text[index])) index += 1
            }
        }
        return index
    }

    private fun isOperator(char: Char): Boolean =
        when (char) {
            '+', '-', '*', '/', '%', '^', '!', '<', '>', '=', '~', '?', ':', '|', '&' -> true
            else -> false
        }

    private fun isNameStart(char: Char): Boolean = isAsciiLetter(char) || char == '_'

    private fun isNamePart(char: Char): Boolean = isAsciiLetter(char) || isDigit(char) || char == '_'

    init {
        words.register(
            KEYWORD,
            "if", "else", "while", "for", "do", "in", "delete", "print", "printf", "return", "exit", "switch", "case", "default",
        )
        words.register(VALUE_KEYWORD, "break", "continue", "next", "nextfile", "getline")
        words.register(FUNCTION_KEYWORD, "function", "func")
        words.register(PATTERN, "BEGIN", "END", "BEGINFILE", "ENDFILE")
        words.register(
            BUILTIN,
            "length", "substr", "index", "split", "sub", "gsub", "gensub", "match", "sprintf", "sin", "cos", "atan2", "exp", "log",
            "sqrt", "int", "rand", "srand", "tolower", "toupper", "system", "close", "fflush", "systime", "strftime", "mktime",
            "asort", "asorti", "patsplit", "isarray", "typeof", "and", "or", "xor", "compl", "lshift", "rshift", "strtonum",
        )
        words.register(
            VARIABLE,
            "NR", "NF", "FNR", "FS", "OFS", "RS", "ORS", "FILENAME", "SUBSEP", "RSTART", "RLENGTH", "ENVIRON", "ARGC", "ARGV",
            "CONVFMT", "OFMT", "RT", "FPAT", "FIELDWIDTHS", "IGNORECASE", "BINMODE", "LINT", "PROCINFO", "TEXTDOMAIN", "ERRNO",
            "ARGIND", "SYMTAB", "FUNCTAB",
        )
    }
}
