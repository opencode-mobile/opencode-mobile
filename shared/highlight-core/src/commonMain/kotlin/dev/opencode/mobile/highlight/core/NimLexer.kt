package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit

/**
 * Nim. Lexing ignores indentation: every construct is found by its own delimiters.
 *
 * Roles: `#` and `##` doc comments and nested `#[ ]#` / `##[ ]##` blocks are Comment. Strings
 * (`"..."` with escapes as Constant, raw `r"..."` where `""` is a quote, and triple-quoted
 * `"""..."""` across lines) and `'c'` characters are String; a generalized raw string's prefix
 * (`sql"..."`, `fmt"""..."""`) is Function. In a pragma `{. ... .}` (which may span lines) the
 * delimiters are Punctuation and pragma names Attribute. A name after `proc`, `func`,
 * `template`, `macro`, `method`, `iterator`, or `converter` (backticked operators too) or before
 * `(` is Function, as are a few builtins (`echo`); after `.` it is Property (Function when
 * called). Keywords are Keyword (word operators like `and`, `div`, `notin` too); `true`,
 * `false`, `nil` are Constant; builtin types (`int`, `string`, `seq`, ...) and capitalized names
 * are Type; `result` is Variable. Numbers (with `'i32`-style suffixes) are Number; operators,
 * `:` and `=` included, are Operator; brackets, `,`, `;`, `.`, and backticks are Punctuation.
 *
 * States: a [LiteralStackLexer] stack holding an open block comment (nesting counted to 15) or
 * triple-quoted string; one-line strings end with their line. Bit 29 marks an open pragma.
 *
 * Known gaps: `fmt"{x}"` and `&"..."` interpolation is not colored inside the string; a
 * pragma left open runs until a `.}`.
 */
internal object NimLexer : LiteralStackLexer(
    arrayOf(
        LiteralKind("\"", singleLine = true),
        LiteralKind("\"\"\"", escapes = ESCAPE_NONE, greedyClose = true),
        LiteralKind("\"", escapes = ESCAPE_NONE, singleLine = true, doubledClose = true),
        LiteralKind("]#", role = RoleCode.COMMENT, open = "#[", escapes = ESCAPE_NONE),
        LiteralKind("]##", role = RoleCode.COMMENT, open = "##[", escapes = ESCAPE_NONE),
    ),
) {
    private const val STRING = FIRST_KIND
    private const val TRIPLE = FIRST_KIND + 1
    private const val RAW = FIRST_KIND + 2
    private const val BLOCK_COMMENT = FIRST_KIND + 3
    private const val DOC_COMMENT = FIRST_KIND + 4

    private const val PRAGMA = 1 shl 29

    // Word kinds: a role code in the low byte plus flags.
    private const val DEFINES = 1 shl 8

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
        var afterDot = false
        var defineNext = false
        var pragmaNameNext = false
        while (index < end && !out.stopped) {
            if (inLiteral(st)) {
                val result = literal(text, index, end, st, out)
                st = stateOf(result)
                index = offsetOf(result)
                afterDot = false
                continue
            }
            val char = text[index]
            if (char == ' ' || char == '\t') {
                index += 1
                continue
            }
            val wasAfterDot = afterDot
            afterDot = false
            val wasDefine = defineNext
            defineNext = false
            val wasPragmaName = pragmaNameNext
            pragmaNameNext = false
            val next = if (index + 1 < end) text[index + 1] else '\u0000'
            when {
                char == '#' -> {
                    val doc = next == '#'
                    val bracket = if (doc) index + 2 else index + 1
                    if (bracket < end && text[bracket] == '[') {
                        val result = openLiteral(text, index, bracket + 1, end, if (doc) DOC_COMMENT else BLOCK_COMMENT, st, out)
                        st = stateOf(result)
                        index = offsetOf(result)
                    } else {
                        out.span(index, end, RoleCode.COMMENT)
                        index = end
                    }
                }
                char == '"' -> {
                    val result = openString(text, index, end, st, false, out)
                    st = stateOf(result)
                    index = offsetOf(result)
                }
                char == '\'' -> {
                    val after = characterEnd(text, index, end)
                    out.span(index, after, if (after > index + 1) RoleCode.STRING else RoleCode.PUNCTUATION)
                    index = after
                }
                char == '{' && next == '.' && !(index + 2 < end && text[index + 2] == '.') -> {
                    out.span(index, index + 2, RoleCode.PUNCTUATION)
                    index += 2
                    st = st or PRAGMA
                    pragmaNameNext = true
                }
                char == '.' && next == '}' && st and PRAGMA != 0 -> {
                    out.span(index, index + 2, RoleCode.PUNCTUATION)
                    index += 2
                    st = st and PRAGMA.inv()
                }
                isDigit(char) -> {
                    val after = numberSuffixEnd(text, numberEnd(text, index, end), end)
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                }
                char == '`' -> {
                    var close = index + 1
                    while (close < end && close < index + 64 && text[close] != '`') close += 1
                    if (close < end && text[close] == '`') {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        if (wasDefine) out.span(index + 1, close, RoleCode.FUNCTION)
                        out.span(close, close + 1, RoleCode.PUNCTUATION)
                        index = close + 1
                    } else {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                    }
                }
                nameStartLength(text, index, end) > 0 -> {
                    val after = nameEnd(text, index, end)
                    if (after < end && text[after] == '"' && !wasAfterDot) {
                        val raw = after - index == 1 && (char == 'r' || char == 'R')
                        if (!raw) out.span(index, after, RoleCode.FUNCTION)
                        val result = openString(text, if (raw) index else after, end, st, true, out)
                        st = stateOf(result)
                        index = offsetOf(result)
                        continue
                    }
                    val kind = if (wasAfterDot) 0 else words.lookup(text, index, after)
                    val role =
                        when {
                            st and PRAGMA != 0 && wasPragmaName -> RoleCode.ATTRIBUTE
                            wasDefine -> RoleCode.FUNCTION
                            kind != 0 -> kind and 0xFF
                            after < end && text[after] == '(' -> RoleCode.FUNCTION
                            wasAfterDot -> RoleCode.PROPERTY
                            text[index] in 'A'..'Z' -> RoleCode.TYPE
                            else -> 0
                        }
                    if (role != 0) out.span(index, after, role)
                    defineNext = kind and DEFINES != 0
                    index = after
                }
                char == '(' || char == ')' || char == '[' || char == ']' || char == '{' || char == '}' || char == ';' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                }
                char == ',' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    pragmaNameNext = st and PRAGMA != 0
                }
                char == '.' && next != '.' && !isOperatorChar(next) -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    afterDot = true
                }
                isOperatorChar(char) -> {
                    var after = index + 1
                    while (after < end && after < index + 3 && isOperatorChar(text[after]) && !(text[after] == '.' && after + 1 < end && text[after + 1] == '}')) after += 1
                    out.span(index, after, RoleCode.OPERATOR)
                    index = after
                }
                else -> index += 1
            }
        }
        if (out.stopped) return st
        return finishLine(st)
    }

    /**
     * Opens a string at the quote (or `r` prefix) [at]: `"""` triple-quoted, else `"..."` (raw
     * when [raw]). Returns the new state above the offset to continue from.
     */
    private fun openString(
        text: CharArray,
        at: Int,
        end: Int,
        state: Int,
        raw: Boolean,
        out: SpanSink,
    ): Long {
        val quote = if (text[at] == '"') at else at + 1
        if (LexerScan.startsWith(text, quote, end, "\"\"\"")) return openLiteral(text, at, quote + 3, end, TRIPLE, state, out)
        return openLiteral(text, at, quote + 1, end, if (raw) RAW else STRING, state, out)
    }

    /** The offset after a `'c'` character at [at] (`'a'`, `'\n'`, `'\x41'`), or [at] + 1 when none closes. */
    private fun characterEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at + 1
        if (index >= end) return index
        if (text[index] == '\\') {
            index += 2
            val limit = minOf(end, at + 8)
            while (index < limit && text[index] != '\'') index += 1
            return if (index < limit) index + 1 else at + 1
        }
        index += if (text[index].isHighSurrogate() && index + 1 < end && text[index + 1].isLowSurrogate()) 2 else 1
        return if (index < end && text[index] == '\'') index + 1 else at + 1
    }

    /** Type suffixes: `'i32`, `'u8`, `'f64`, and the bare `u`, `f32`, `i8` forms. */
    private fun numberSuffixEnd(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        var index = from
        if (index + 1 < end && text[index] == '\'' && isAsciiLetter(text[index + 1])) {
            index += 2
            while (index < end && (isAsciiLetter(text[index]) || isDigit(text[index]))) index += 1
            return index
        }
        if (index < end && (text[index] == 'i' || text[index] == 'u' || text[index] == 'f' || text[index] == 'd')) {
            var after = index + 1
            while (after < end && isDigit(text[after])) after += 1
            if (after >= end || !isNamePart(text[after])) return after
        }
        return from
    }

    private fun isOperatorChar(char: Char): Boolean =
        when (char) {
            '=', '+', '-', '*', '/', '<', '>', '@', '$', '~', '&', '%', '|', '!', '?', '^', '.', ':', '\\' -> true
            else -> false
        }

    init {
        words.register(
            RoleCode.KEYWORD,
            "addr", "and", "as", "asm", "bind", "block", "break", "case", "cast", "concept", "const", "continue", "defer",
            "discard", "distinct", "div", "do", "elif", "else", "end", "enum", "except", "export", "finally", "for", "from",
            "if", "import", "in", "include", "interface", "is", "isnot", "let", "mixin", "mod", "not", "notin", "object",
            "of", "or", "out", "ptr", "raise", "ref", "return", "shl", "shr", "static", "try", "tuple", "type", "using",
            "var", "when", "while", "xor", "yield",
        )
        words.register(RoleCode.KEYWORD or DEFINES, "proc", "func", "template", "macro", "method", "iterator", "converter")
        words.register(RoleCode.CONSTANT, "true", "false", "nil")
        words.register(RoleCode.VARIABLE, "result")
        words.register(
            RoleCode.TYPE,
            "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32", "uint64", "float", "float32",
            "float64", "bool", "char", "string", "cstring", "pointer", "seq", "array", "set", "openArray", "varargs", "void",
            "auto", "any", "untyped", "typed", "typedesc", "byte", "range", "Natural", "Positive",
        )
        words.register(RoleCode.FUNCTION, "echo", "len", "add", "inc", "dec", "new", "high", "low", "ord", "chr", "assert", "doAssert", "quit")
    }
}
