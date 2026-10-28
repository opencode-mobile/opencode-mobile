package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit

/**
 * Crystal.
 *
 * Roles: `#` comments are Comment. Strings (`"..."`, backticks, `%(...)`, `%Q`, `%q`, `%w`,
 * `%x`), heredocs (`<<-ID`, `<<-'ID'`; opener and terminator included), regexes (`/.../i`,
 * `%r{...}`), and `'c'` characters are String with escapes as Constant; in interpolating ones
 * `#{` and `}` are Punctuation around code. Paired percent-literal delimiters nest. Symbols
 * (`:name`, `:"..."`, `:+`, `%i[...]`) and named-argument keys (`key:`) are Constant, as are
 * `true`, `false`, `nil`, and ALL_CAPS names; other capitalized names are Type. Annotations
 * `@[Name(...)]` have `@[` and `]` as Punctuation and the name as Attribute. Macro delimiters
 * `{{ }}` and `{% %}` are Punctuation around code. `@ivar`, `@@cvar`, `$~`, `self`, method
 * parameters (`def f(x : Int32)`) and block parameters (`|x|`) are Variable. A name after `def`
 * or `macro`, before `(`, or ending in `?` or `!` is Function; a name after `.` is Property
 * (Function when called). Keywords are Keyword; numbers (with `_i64`-style suffixes) Number;
 * operators Operator; brackets, `,`, `;`, `.`, `::`, and a type annotation's ` : ` Punctuation.
 *
 * A `/`, `%`, or `<<-` reads as a literal where a value is expected (line start, after an
 * operator, `(`, `,`, or a keyword); `%` and `<<-` also after a blank (`puts %w(a b)`,
 * `puts <<-EOS`).
 *
 * States: a [LiteralStackLexer] stack of literals and `#{` interpolations (literals stay open
 * across lines, a `/regex/` ends with its line); in a heredoc body, bit 29 marks the mode, bit
 * 28 a quoted (raw) id, and bits 0-27 a 28-bit [LexerDelimiter] hash of the id.
 *
 * Known gaps: only the first heredoc of a line is followed, and only when no literal stays open
 * at its line end; an interpolation inside a heredoc colors only when it closes on its line.
 */
internal object CrystalLexer : LiteralStackLexer(crystalKinds()) {
    private const val STRING = FIRST_KIND
    private const val COMMAND = FIRST_KIND + 1
    private const val REGEX = FIRST_KIND + 2
    private const val SYMBOL = FIRST_KIND + 3
    private const val PERCENT = FIRST_KIND + 4
    private const val RAW_PERCENT = FIRST_KIND + 9
    private const val SYMBOL_PERCENT = FIRST_KIND + 14
    private const val REGEX_PERCENT = FIRST_KIND + 19

    private const val HEREDOC = 1 shl 29
    private const val RAW_HEREDOC = 1 shl 28
    private const val HASH_BITS = 28

    // Word kinds: a role code in the low byte plus flags.
    private const val VALUE = 1 shl 8
    private const val DEF = 1 shl 9
    private const val BLOCK = 1 shl 10

    private val words = LexerWords()

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        if (state and HEREDOC != 0) return heredocLine(text, start, end, state, out)
        var st = state
        var index = start
        var valueEnded = false
        var spaceBefore = true
        var afterDot = false
        var afterDef = false
        var defParens = 0
        var defParamsNext = false
        var expectBlockParams = false
        var blockParams = false
        var annotationNext = false
        var heredoc = 0
        while (index < end && !out.stopped) {
            if (inLiteral(st)) {
                val result = literal(text, index, end, st, out)
                val closed = depthOf(stateOf(result)) < depthOf(st)
                st = stateOf(result)
                index = offsetOf(result)
                valueEnded = closed
                spaceBefore = false
                continue
            }
            val char = text[index]
            val wasSpace = spaceBefore
            spaceBefore = false
            val wasAfterDot = afterDot
            afterDot = false
            val wasAfterDef = afterDef
            afterDef = false
            val expectingParams = expectBlockParams
            expectBlockParams = false
            val wasAnnotation = annotationNext
            annotationNext = false
            val next = if (index + 1 < end) text[index + 1] else '\u0000'
            when {
                char == ' ' || char == '\t' -> {
                    index += 1
                    spaceBefore = true
                    afterDot = wasAfterDot
                    afterDef = wasAfterDef
                    expectBlockParams = expectingParams
                }
                char == '#' -> {
                    out.span(index, end, RoleCode.COMMENT)
                    index = end
                }
                char == '"' || char == '`' -> {
                    val result = openLiteral(text, index, index + 1, end, if (char == '"') STRING else COMMAND, st, out)
                    st = stateOf(result)
                    index = offsetOf(result)
                    valueEnded = !inLiteral(st)
                }
                char == '\'' -> {
                    val after = characterEnd(text, index, end)
                    out.span(index, after, RoleCode.STRING)
                    index = after
                    valueEnded = true
                }
                char == ':' && next == ':' -> {
                    out.span(index, index + 2, RoleCode.PUNCTUATION)
                    index += 2
                    afterDot = true
                    annotationNext = wasAnnotation
                    valueEnded = false
                }
                char == ':' && next == '"' -> {
                    val result = openLiteral(text, index, index + 2, end, SYMBOL, st, out)
                    st = stateOf(result)
                    index = offsetOf(result)
                    valueEnded = !inLiteral(st)
                }
                char == ':' && isNameStart(next) -> {
                    var after = nameEnd(text, index + 1, end)
                    if (after < end && (text[after] == '?' || text[after] == '!' || text[after] == '=') && !isOperatorPair(text, after, end)) after += 1
                    out.span(index, after, RoleCode.CONSTANT)
                    index = after
                    valueEnded = true
                }
                char == ':' && !valueEnded && isOperatorChar(next) -> {
                    var after = index + 2
                    while (after < end && after < index + 4 && isOperatorChar(text[after])) after += 1
                    out.span(index, after, RoleCode.CONSTANT)
                    index = after
                    valueEnded = true
                }
                char == ':' -> {
                    // A type annotation (`x : Int32`) or the ternary's `:`.
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    valueEnded = false
                }
                char == '%' && (index > start && text[index - 1] == '{' || next == '}') -> {
                    // Macro delimiters: `{%` and `%}`.
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    valueEnded = false
                }
                char == '%' && percentLiteralAt(text, index, end, valueEnded, wasSpace) > 0 -> {
                    val open = percentLiteralAt(text, index, end, valueEnded, wasSpace)
                    val slot = OPENERS.indexOf(text[open])
                    val code =
                        when (if (open > index + 1) text[index + 1] else '\u0000') {
                            'q', 'w' -> RAW_PERCENT
                            'i' -> SYMBOL_PERCENT
                            'r' -> REGEX_PERCENT
                            else -> PERCENT
                        }
                    val result = openLiteral(text, index, open + 1, end, code + slot, st, out)
                    st = stateOf(result)
                    index = offsetOf(result)
                    valueEnded = !inLiteral(st)
                }
                char == '/' && !valueEnded -> {
                    val result = openLiteral(text, index, index + 1, end, REGEX, st, out)
                    st = stateOf(result)
                    index = offsetOf(result)
                    valueEnded = !inLiteral(st)
                }
                char == '<' && next == '<' && heredocAhead(text, index, end, valueEnded, wasSpace) -> {
                    val result = heredocStart(text, index, end, out)
                    index = offsetOf(result)
                    if (heredoc == 0) heredoc = stateOf(result)
                    valueEnded = true
                }
                isDigit(char) -> {
                    val after = numberSuffixEnd(text, numberEnd(text, index, end), end)
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                    valueEnded = true
                }
                char == '@' && next == '[' -> {
                    out.span(index, index + 2, RoleCode.PUNCTUATION)
                    index += 2
                    annotationNext = true
                    valueEnded = false
                }
                char == '@' -> {
                    var after = index + 1
                    if (after < end && text[after] == '@') after += 1
                    after = nameEnd(text, after, end)
                    out.span(index, after, RoleCode.VARIABLE)
                    index = after
                    valueEnded = true
                }
                char == '$' -> {
                    val after =
                        when {
                            isNameStart(next) -> nameEnd(text, index + 1, end)
                            next == '~' || next == '?' || isDigit(next) -> index + 2
                            else -> index + 1
                        }
                    out.span(index, after, RoleCode.VARIABLE)
                    index = after
                    valueEnded = true
                }
                nameStartLength(text, index, end) > 0 -> {
                    var after = nameEnd(text, index, end)
                    if (after < end && (text[after] == '?' || text[after] == '!') && !isOperatorPair(text, after, end)) after += 1
                    val suffixed = text[after - 1] == '?' || text[after - 1] == '!'
                    val label = !wasAfterDot && !wasAfterDef && after + 1 < end && text[after] == ':' && text[after + 1] != ':' && text[after + 1] != '"'
                    if (label && (text[after + 1] == ' ' || text[after + 1] == '\t')) {
                        // A named argument or named-tuple key: `key: value`.
                        if (defParens > 0 || blockParams) {
                            out.span(index, after, RoleCode.VARIABLE)
                            out.span(after, after + 1, RoleCode.PUNCTUATION)
                        } else {
                            out.span(index, after + 1, RoleCode.CONSTANT)
                        }
                        index = after + 1
                        valueEnded = false
                    } else {
                        val kind = if (wasAfterDot) 0 else words.lookup(text, index, after)
                        val call = after < end && text[after] == '('
                        val upper = text[index] in 'A'..'Z'
                        if (wasAfterDef && !upper && LexerScan.equals(text, index, after, "self") && after < end && text[after] == '.') {
                            out.span(index, after, RoleCode.VARIABLE)
                            afterDef = true
                            index = after
                            continue
                        }
                        var stop = after
                        if (wasAfterDef && stop < end && text[stop] == '=' && !isOperatorPair(text, stop, end)) stop += 1
                        val role =
                            when {
                                wasAnnotation -> {
                                    annotationNext = after + 1 < end && text[after] == ':' && text[after + 1] == ':'
                                    RoleCode.ATTRIBUTE
                                }
                                wasAfterDef -> RoleCode.FUNCTION
                                kind != 0 -> kind and 0xFF
                                upper -> if (isAllCaps(text, index, after)) RoleCode.CONSTANT else RoleCode.TYPE
                                defParens > 0 || blockParams -> RoleCode.VARIABLE
                                call || suffixed -> RoleCode.FUNCTION
                                wasAfterDot -> RoleCode.PROPERTY
                                else -> 0
                            }
                        if (role != 0) out.span(index, stop, role)
                        if (kind and DEF != 0) afterDef = true
                        if (kind and BLOCK != 0) expectBlockParams = true
                        if (wasAfterDef && stop < end && text[stop] == '(') defParamsNext = true
                        valueEnded = kind == 0 || kind and VALUE != 0
                        index = stop
                    }
                }
                char == '{' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    st = openBracket(st, char)
                    index += 1
                    expectBlockParams = true
                    valueEnded = false
                }
                char == '}' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    st = closeBracket(st, char)
                    index += 1
                    valueEnded = true
                }
                char == '|' && (expectingParams || blockParams) -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    blockParams = !blockParams
                    index += 1
                    valueEnded = false
                }
                char == '(' || char == '[' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    if (char == '(' && defParamsNext) {
                        defParens = 1
                    } else if (char == '(' && defParens > 0) {
                        defParens += 1
                    }
                    defParamsNext = false
                    index += 1
                    valueEnded = false
                }
                char == ')' || char == ']' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    if (char == ')' && defParens > 0) defParens -= 1
                    index += 1
                    valueEnded = true
                }
                char == ',' || char == ';' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    valueEnded = false
                }
                char == '.' -> {
                    if (next == '.') {
                        val after = if (index + 2 < end && text[index + 2] == '.') index + 3 else index + 2
                        out.span(index, after, RoleCode.OPERATOR)
                        index = after
                    } else {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                        afterDot = true
                        afterDef = wasAfterDef
                    }
                    valueEnded = false
                }
                char == '&' && next == '.' -> {
                    out.span(index, index + 2, RoleCode.PUNCTUATION)
                    index += 2
                    afterDot = true
                    valueEnded = false
                }
                wasAfterDef && isOperatorChar(char) -> {
                    // `def ==(other)`, `def <=>(o)`, `def []=(k, v)`.
                    var after = index + 1
                    while (after < end && after < index + 3 && isOperatorChar(text[after])) after += 1
                    out.span(index, after, RoleCode.FUNCTION)
                    if (after < end && text[after] == '(') defParamsNext = true
                    index = after
                }
                isOperatorChar(char) -> {
                    var after = index + 1
                    while (after < end && after < index + 3 && isOperatorChar(text[after])) after += 1
                    out.span(index, after, RoleCode.OPERATOR)
                    index = after
                    valueEnded = false
                }
                else -> index += 1
            }
        }
        if (out.stopped) return st
        val ended = finishLine(st)
        if (heredoc != 0 && ended == 0) return heredoc
        return ended
    }

    /** For `%` at [at]: the offset of a percent literal's opening delimiter, or 0 when it is not one. */
    private fun percentLiteralAt(
        text: CharArray,
        at: Int,
        end: Int,
        valueEnded: Boolean,
        spaceBefore: Boolean,
    ): Int {
        if (at + 1 >= end) return 0
        var open = at + 1
        val letter = text[open]
        if (letter == 'q' || letter == 'Q' || letter == 'w' || letter == 'i' || letter == 'r' || letter == 'x') open += 1
        if (open >= end || OPENERS.indexOf(text[open]) < 0) return 0
        // After a value, `puts %w(a b)` is still a literal when a blank precedes it.
        return if (!valueEnded || spaceBefore) open else 0
    }

    /** True when `<<` at [at] starts a heredoc (`<<-ID`, `<<-'ID'`) rather than the operator. */
    private fun heredocAhead(
        text: CharArray,
        at: Int,
        end: Int,
        valueEnded: Boolean,
        spaceBefore: Boolean,
    ): Boolean {
        val index = at + 3
        if (index >= end || (text[at + 2] != '-' && text[at + 2] != '~')) return false
        val char = text[index]
        if (!(char == '\'' || char == '"' || isNameStart(char))) return false
        return !valueEnded || spaceBefore
    }

    /** Lexes `<<-ID` or `<<-'ID'` as String; returns the heredoc state above the offset after it. */
    private fun heredocStart(
        text: CharArray,
        at: Int,
        end: Int,
        out: SpanSink,
    ): Long {
        var index = at + 3
        var raw = false
        val idStart: Int
        val idEnd: Int
        if (text[index] == '\'' || text[index] == '"') {
            val quote = text[index]
            raw = quote == '\''
            idStart = index + 1
            var close = idStart
            while (close < end && text[close] != quote) close += 1
            idEnd = close
            index = minOf(close + 1, end)
        } else {
            idStart = index
            idEnd = nameEnd(text, index, end)
            index = idEnd
        }
        out.span(at, index, RoleCode.STRING)
        var bits = HEREDOC or LexerDelimiter.of(text, idStart, idEnd, HASH_BITS)
        if (raw) bits = bits or RAW_HEREDOC
        return packed(bits, index)
    }

    /** A heredoc body line, or its (possibly indented) terminator, which returns to code. */
    private fun heredocLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        val from = LexerScan.skipBlanks(text, start, end)
        if (LexerDelimiter.of(text, from, end, HASH_BITS) == state and ((1 shl HASH_BITS) - 1)) {
            out.span(from, end, RoleCode.STRING)
            return 0
        }
        if (state and RAW_HEREDOC != 0) {
            out.span(start, end, RoleCode.STRING)
            return state
        }
        var segment = start
        var index = start
        while (index < end && !out.stopped) {
            val char = text[index]
            if (char == '\\') {
                val length = if (index + 1 < end) 2 else 1
                out.span(segment, index, RoleCode.STRING)
                out.span(index, index + length, RoleCode.CONSTANT)
                index += length
                segment = index
            } else if (char == '#' && index + 1 < end && text[index + 1] == '{') {
                val close = matchingClose(text, index + 2, end, '{', '}')
                if (close < 0) break
                out.span(segment, index, RoleCode.STRING)
                out.span(index, index + 2, RoleCode.PUNCTUATION)
                // The code inside lexes on its own; whatever it leaves open ends at the `}`.
                lexLine(text, index + 2, close - 1, 0, out)
                out.span(close - 1, close, RoleCode.PUNCTUATION)
                index = close
                segment = close
            } else {
                index += 1
            }
        }
        out.span(segment, end, RoleCode.STRING)
        return state
    }

    /** The offset after a `'c'` character at [at] (`'a'`, `'\n'`, `'\u{1F600}'`, `'😀'`); just the quote when none closes. */
    private fun characterEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at + 1
        if (index >= end) return index
        if (text[index] == '\\') {
            index += 2
            val limit = minOf(end, at + 14)
            while (index < limit && text[index] != '\'') index += 1
            return if (index < limit) index + 1 else at + 1
        }
        index += if (text[index].isHighSurrogate() && index + 1 < end && text[index + 1].isLowSurrogate()) 2 else 1
        return if (index < end && text[index] == '\'') index + 1 else at + 1
    }

    /** Integer and float suffixes: `_i64`, `u8`, `f32`. */
    private fun numberSuffixEnd(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        var index = from
        if (index < end && text[index] == '_') index += 1
        if (index < end && (text[index] == 'i' || text[index] == 'u' || text[index] == 'f') && index + 1 < end && isDigit(text[index + 1])) {
            index += 1
            while (index < end && isDigit(text[index])) index += 1
            return index
        }
        return from
    }

    /** `!=`, `?=`, `==`: a `?`/`!`/`=` at [at] that starts an operator, not a name suffix. */
    private fun isOperatorPair(
        text: CharArray,
        at: Int,
        end: Int,
    ): Boolean {
        if (at + 1 >= end) return false
        val next = text[at + 1]
        return next == '=' || next == '~' || (text[at] == '=' && next == '>')
    }

    private fun isOperatorChar(char: Char): Boolean =
        when (char) {
            '+', '-', '*', '/', '%', '=', '<', '>', '!', '&', '|', '^', '~', '?' -> true
            else -> false
        }

    private const val OPENERS = "([{<|"

    init {
        words.register(
            RoleCode.KEYWORD,
            "abstract", "alias", "annotation", "as", "asm", "begin", "break", "case", "class", "else", "elsif", "ensure",
            "enum", "extend", "for", "fun", "if", "in", "include", "lib", "module", "next", "of", "out", "private",
            "protected", "require", "rescue", "return", "select", "struct", "super", "then", "type", "union", "unless",
            "until", "verbatim", "when", "while", "with", "yield", "raise", "getter", "setter", "property", "record",
        )
        words.register(RoleCode.KEYWORD or DEF, "def", "macro")
        words.register(RoleCode.KEYWORD or BLOCK, "do")
        words.register(
            RoleCode.KEYWORD or VALUE,
            "end", "uninitialized", "typeof", "sizeof", "instance_sizeof", "offsetof", "pointerof", "is_a?", "responds_to?",
            "nil?", "as?", "__FILE__", "__LINE__", "__DIR__",
        )
        words.register(RoleCode.CONSTANT or VALUE, "true", "false", "nil")
        words.register(RoleCode.VARIABLE or VALUE, "self")
        words.register(RoleCode.FUNCTION or VALUE, "puts", "print", "p", "pp", "printf", "sprintf", "spawn", "loop", "sleep", "exit", "abort")
    }
}

private fun crystalKinds(): Array<LiteralKind> {
    val closers = ")]}>|"
    val openers = "([{<|"
    val list =
        mutableListOf(
            LiteralKind("\"", interpolation = LiteralStackLexer.HASH_BRACE),
            LiteralKind("`", interpolation = LiteralStackLexer.HASH_BRACE),
            LiteralKind("/", interpolation = LiteralStackLexer.HASH_BRACE, suffix = true, singleLine = true),
            LiteralKind("\"", role = RoleCode.CONSTANT, interpolation = LiteralStackLexer.HASH_BRACE),
        )
    // Percent literals: interpolating, raw, symbol arrays, regexes; one kind per delimiter.
    for (group in 0 until 4) {
        for (slot in closers.indices) {
            val close = closers[slot].toString()
            val open = if (openers[slot] != closers[slot]) openers[slot].toString() else ""
            list +=
                when (group) {
                    0 -> LiteralKind(close, open = open, interpolation = LiteralStackLexer.HASH_BRACE)
                    1 -> LiteralKind(close, open = open, escapes = LiteralStackLexer.ESCAPE_CLOSER)
                    2 -> LiteralKind(close, role = RoleCode.CONSTANT, open = open, escapes = LiteralStackLexer.ESCAPE_CLOSER)
                    else -> LiteralKind(close, open = open, interpolation = LiteralStackLexer.HASH_BRACE, suffix = true)
                }
        }
    }
    return list.toTypedArray()
}
