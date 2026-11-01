package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isDigit

/**
 * Julia.
 *
 * Roles: `#` comments and nested `#= ... =#` blocks are Comment. Strings (`"..."`, `"""..."""`,
 * and backtick commands) are String with escapes as Constant; `$name` interpolation is Variable
 * and `$(` `)` are Punctuation around code. A string macro (`r"..."`, `raw"""..."""`, `b"..."`)
 * has its prefix as Function and a raw body (only `\"` and `\\` escape) with trailing flags. A
 * `'` after a value with no blank between (`A'`, `x[1]'`) is the transpose Operator; elsewhere
 * it opens a character literal (`'a'`, `'\n'`, `'😀'`), String. `:name` symbols are Constant
 * where a value is expected (not in ranges like `1:n`). Keywords are Keyword; `true`, `false`,
 * `nothing`, `missing`, `Inf`, `NaN`, and ALL_CAPS names are Constant; other capitalized
 * names, and names after `::` or `<:`, are Type. A name after `function` or `macro`, or before `(`, is Function, as is an
 * `@macro` call; a name after `.` is Property (Function when called). Numbers (with `im`)
 * are Number; operators (Unicode math symbols too) are Operator; brackets, `,`, `;`, and `.`
 * are Punctuation. Identifiers may be Unicode, including emoji.
 *
 * States: a [LiteralStackLexer] stack of strings, block comments (nesting counted to 15), and
 * `$(` interpolations; Julia strings stay open across lines.
 *
 * Known gaps: a string opened past three levels of nesting colors to its close on its line
 * without interpolation; prefixed strings other than macros (`b"..."`) are raw; `$` in code
 * (quoting) is an Operator; `x :y` after a value reads `:` as a range.
 */
internal object JuliaLexer : LiteralStackLexer(
    arrayOf(
        LiteralKind("\"", interpolation = DOLLAR),
        LiteralKind("\"\"\"", interpolation = DOLLAR),
        LiteralKind("`", interpolation = DOLLAR),
        LiteralKind("```", interpolation = DOLLAR),
        LiteralKind("\"", escapes = ESCAPE_CLOSER, suffix = true),
        LiteralKind("\"\"\"", escapes = ESCAPE_CLOSER, suffix = true),
        LiteralKind("=#", role = RoleCode.COMMENT, open = "#=", escapes = ESCAPE_NONE),
    ),
) {
    private const val STRING = FIRST_KIND
    private const val TRIPLE = FIRST_KIND + 1
    private const val COMMAND = FIRST_KIND + 2
    private const val TRIPLE_COMMAND = FIRST_KIND + 3
    private const val RAW = FIRST_KIND + 4
    private const val RAW_TRIPLE = FIRST_KIND + 5
    private const val BLOCK_COMMENT = FIRST_KIND + 6

    // Word kinds: a role code in the low byte plus flags.
    private const val VALUE = 1 shl 8
    private const val DEFINES = 1 shl 9
    private const val TYPE_NEXT = 1 shl 10

    private val words = LexerWords()

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        var st = state
        var index = start
        // The offset where the last value ended (a name, number, closing bracket, or string); -1 when none.
        var valueEnd = -1
        var afterDot = false
        var defineNext = false
        var typeNext = false
        while (index < end && !out.stopped) {
            if (inLiteral(st)) {
                val result = literal(text, index, end, st, out)
                val closed = depthOf(stateOf(result)) < depthOf(st)
                st = stateOf(result)
                index = offsetOf(result)
                valueEnd = if (closed) index else -1
                continue
            }
            val char = text[index]
            val wasAfterDot = afterDot
            afterDot = false
            val wasDefine = defineNext
            defineNext = false
            val wasTypeNext = typeNext
            typeNext = false
            val next = if (index + 1 < end) text[index + 1] else '\u0000'
            when {
                char == ' ' || char == '\t' -> {
                    index += 1
                    afterDot = wasAfterDot
                    defineNext = wasDefine
                    typeNext = wasTypeNext
                    continue
                }
                char == '#' -> {
                    if (next == '=') {
                        val result = openLiteral(text, index, index + 2, end, BLOCK_COMMENT, st, out)
                        st = stateOf(result)
                        index = offsetOf(result)
                        continue
                    }
                    out.span(index, end, RoleCode.COMMENT)
                    index = end
                    continue
                }
                char == '"' -> {
                    val triple = next == '"' && index + 2 < end && text[index + 2] == '"'
                    val result = openLiteral(text, index, index + if (triple) 3 else 1, end, if (triple) TRIPLE else STRING, st, out)
                    st = stateOf(result)
                    index = offsetOf(result)
                    valueEnd = if (inLiteral(st)) -1 else index
                    continue
                }
                char == '`' -> {
                    val triple = next == '`' && index + 2 < end && text[index + 2] == '`'
                    val result = openLiteral(text, index, index + if (triple) 3 else 1, end, if (triple) TRIPLE_COMMAND else COMMAND, st, out)
                    st = stateOf(result)
                    index = offsetOf(result)
                    valueEnd = if (inLiteral(st)) -1 else index
                    continue
                }
                char == '\'' -> {
                    if (valueEnd == index) {
                        out.span(index, index + 1, RoleCode.OPERATOR)
                        index += 1
                        valueEnd = index
                        continue
                    }
                    val after = characterEnd(text, index, end)
                    out.span(index, after, if (after > index + 1) RoleCode.STRING else RoleCode.OPERATOR)
                    index = after
                    valueEnd = index
                    continue
                }
                isDigit(char) || (char == '.' && isDigit(next) && valueEnd != index) -> {
                    var after = numberEnd(text, index, end)
                    if (after < end && (text[after] == 'f') && after + 1 < end && (isDigit(text[after + 1]) || text[after + 1] == '-')) {
                        // `1f0`, `1f-3`: Float32 exponents.
                        after += 2
                        while (after < end && isDigit(text[after])) after += 1
                    }
                    if (LexerScan.startsWith(text, after, end, "im") && (after + 2 >= end || namePartLength(text, after + 2, end) == 0)) after += 2
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                    valueEnd = index
                    continue
                }
                char == '@' && nameEnd(text, index + 1, end) > index + 1 -> {
                    var after = nameEnd(text, index + 1, end)
                    while (after < end && text[after] == '!') after += 1
                    // `@Base.kwdef`: a qualified macro.
                    while (after + 1 < end && text[after] == '.' && nameEnd(text, after + 1, end) > after + 1) after = nameEnd(text, after + 1, end)
                    out.span(index, after, RoleCode.FUNCTION)
                    index = after
                    valueEnd = -1
                    continue
                }
                char == ':' -> {
                    if (next == ':') {
                        out.span(index, index + 2, RoleCode.OPERATOR)
                        index += 2
                        typeNext = true
                        valueEnd = -1
                        continue
                    }
                    if (valueEnd != index && !wasAfterDot && isSymbolStart(text, index + 1, end, valueEnd >= 0)) {
                        var after = nameEnd(text, index + 1, end)
                        while (after < end && text[after] == '!') after += 1
                        out.span(index, after, RoleCode.CONSTANT)
                        index = after
                        valueEnd = index
                        continue
                    }
                    out.span(index, index + 1, RoleCode.OPERATOR)
                    index += 1
                    valueEnd = -1
                    continue
                }
                char == '<' && next == ':' -> {
                    out.span(index, index + 2, RoleCode.OPERATOR)
                    index += 2
                    typeNext = true
                    valueEnd = -1
                    continue
                }
                nameStartLength(text, index, end) > 0 -> {
                    var after = nameEnd(text, index, end)
                    while (after < end && text[after] == '!') after += 1
                    val kind = words.lookup(text, index, after)
                    if (after < end && text[after] == '"' && !wasAfterDot && kind == 0) {
                        // A string macro: `r"..."`, `raw"""..."""`.
                        out.span(index, after, RoleCode.FUNCTION)
                        val triple = LexerScan.startsWith(text, after, end, "\"\"\"")
                        val result = openLiteral(text, after, after + if (triple) 3 else 1, end, if (triple) RAW_TRIPLE else RAW, st, out)
                        st = stateOf(result)
                        index = offsetOf(result)
                        valueEnd = if (inLiteral(st)) -1 else index
                        continue
                    }
                    val qualified = wasDefine && after < end && text[after] == '.'
                    val role =
                        when {
                            kind != 0 && !wasAfterDot -> kind and 0xFF
                            qualified -> RoleCode.TYPE
                            wasDefine -> RoleCode.FUNCTION
                            wasTypeNext -> RoleCode.TYPE
                            after < end && text[after] == '(' -> RoleCode.FUNCTION
                            wasAfterDot -> RoleCode.PROPERTY
                            text[index] in 'A'..'Z' -> if (isAllCaps(text, index, after)) RoleCode.CONSTANT else RoleCode.TYPE
                            else -> 0
                        }
                    if (role != 0) out.span(index, after, role)
                    defineNext = (kind and DEFINES != 0 && !wasAfterDot) || qualified
                    typeNext = kind and TYPE_NEXT != 0 && !wasAfterDot
                    valueEnd = if (kind == 0 || kind and VALUE != 0 || wasAfterDot) after else -1
                    index = after
                    continue
                }
                char == '(' || char == '{' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    st = openBracket(st, char)
                    index += 1
                    valueEnd = -1
                }
                char == ')' || char == '}' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    st = closeBracket(st, char)
                    index += 1
                    valueEnd = index
                }
                char == '[' || char == ',' || char == ';' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    valueEnd = -1
                }
                char == ']' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    valueEnd = index
                }
                char == '.' -> {
                    if (next == '.') {
                        val after = if (index + 2 < end && text[index + 2] == '.') index + 3 else index + 2
                        out.span(index, after, RoleCode.OPERATOR)
                        index = after
                    } else if (isOperatorChar(next)) {
                        // Broadcast: `.+`, `.=`, `.*`.
                        var after = index + 2
                        while (after < end && after < index + 4 && isOperatorChar(text[after])) after += 1
                        out.span(index, after, RoleCode.OPERATOR)
                        index = after
                    } else {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                        afterDot = true
                        defineNext = wasDefine
                    }
                    valueEnd = -1
                }
                isOperatorChar(char) -> {
                    var after = index + 1
                    while (after < end && after < index + 3 && isOperatorChar(text[after]) && text[after] != ':') after += 1
                    out.span(index, after, RoleCode.OPERATOR)
                    index = after
                    valueEnd = -1
                }
                char.category == CharCategory.MATH_SYMBOL -> {
                    out.span(index, index + 1, RoleCode.OPERATOR)
                    index += 1
                    valueEnd = -1
                }
                else -> {
                    index += 1
                    valueEnd = -1
                }
            }
        }
        if (out.stopped) return st
        return finishLine(st)
    }

    /** The offset after a character literal at [at] (`'a'`, `'\n'`, `'é'`, `'😀'`), or [at] + 1 when none closes. */
    private fun characterEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at + 1
        if (index >= end) return at + 1
        if (text[index] == '\\') {
            // An escape: up to 10 more characters before the closing quote.
            index += 2
            val limit = minOf(end, at + 13)
            while (index < limit && text[index] != '\'') index += 1
            return if (index < limit && text[index] == '\'') index + 1 else at + 1
        }
        if (text[index] == '\'') return at + 1
        index += if (text[index].isHighSurrogate() && index + 1 < end && text[index + 1].isLowSurrogate()) 2 else 1
        // A combining mark may follow the character.
        return if (index < end && text[index] == '\'') index + 1 else at + 1
    }

    /** True when `:` at [at] - 1 starts a symbol: a name follows, and the `:` is not a range after a value. */
    private fun isSymbolStart(
        text: CharArray,
        at: Int,
        end: Int,
        afterValue: Boolean,
    ): Boolean {
        if (at >= end || nameStartLength(text, at, end) == 0) return false
        if (!afterValue) return true
        // After a value and a blank (`a ? b :c` aside), `x :y` still reads as a range.
        return false
    }

    private fun isOperatorChar(char: Char): Boolean =
        when (char) {
            '+', '-', '*', '/', '\\', '^', '%', '=', '<', '>', '!', '&', '|', '~', '?', '$', ':', '÷' -> true
            else -> false
        }

    init {
        words.register(
            RoleCode.KEYWORD,
            "if", "elseif", "else", "for", "while", "return", "begin", "let", "local", "global", "const", "try", "catch",
            "finally", "do", "break", "continue", "quote", "using", "import", "export", "in", "isa", "where", "outer", "public",
        )
        words.register(RoleCode.KEYWORD or VALUE, "end")
        words.register(RoleCode.KEYWORD or DEFINES, "function", "macro")
        words.register(RoleCode.KEYWORD or TYPE_NEXT, "struct", "module", "baremodule", "type")
        words.register(RoleCode.KEYWORD, "mutable", "abstract", "primitive")
        words.register(RoleCode.CONSTANT or VALUE, "true", "false", "nothing", "missing", "Inf", "NaN", "Inf32", "NaN32", "ARGS", "ENV", "C_NULL")
    }
}
