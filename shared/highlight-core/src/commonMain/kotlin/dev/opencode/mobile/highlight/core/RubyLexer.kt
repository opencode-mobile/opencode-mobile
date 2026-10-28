package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * Ruby.
 *
 * Roles: comments, `=begin`...`=end` blocks, and the data after `__END__` are Comment.
 * Strings (`"..."`, `'...'`, backticks, `%q`, `%Q`, `%w`, `%W`, `%x`, `%()`), heredocs
 * (`<<~ID`, `<<-ID`, `<<ID`, quoted ids; opener and terminator included), regex literals
 * (`/.../flags`, `%r{...}`), and `?a` character literals are String, with escapes as Constant;
 * in interpolating ones `#{` and `}` are Punctuation around code, and `#@ivar` is Variable.
 * Symbols (`:name`, `:"..."`, `:+`, `%i[]`, `%s()`) and hash labels (`name:`) are Constant, as
 * are `true`, `false`, `nil`, and ALL_CAPS names; other capitalized names are Type. Keywords are
 * Keyword (so are `require`, `attr_reader`, `private`, and `raise`), with `self` as Variable;
 * `@ivar`, `@@cvar`, `$global`, `def` and block parameters are Variable. A name after `def`,
 * before `(`, or ending in `?` or `!` is Function, as are a few Kernel methods (`puts`, `p`,
 * `format`, ...); a name after `.` or `&.` is Property, Function when called, and a capitalized
 * one (as after `::`) keeps its constant role. Numbers are Number; operators Operator;
 * brackets, `,`, `;`, `.`, `::` Punctuation.
 *
 * A `/`, `%`, `?`, or `<<` reads as a literal where a value is expected (line start, after an
 * operator, `(`, `,`, or a keyword like `if`), and as an operator after a value. `%w(` and
 * friends also read as literals after a blank (`puts %w(a b)`), and `<<ID` (capital or quoted)
 * after a blank (`puts <<EOS`).
 *
 * States: bits 30-31 hold the mode. In code, bits 0-1 hold the depth of a stack of up to
 * [MAX_FRAMES] open literals and interpolations, 9 bits each from bit 2: a literal stores its
 * closing delimiter (an index into [CLOSERS]), its kind (raw, interpolating, regex, symbol),
 * and how many of its paired opening delimiter are open inside it (0-3); an interpolation
 * stores 0 and its open braces (0-15). Literals stay open across lines, as in Ruby. In a
 * heredoc body, bit 29 means the terminator may be indented (`~` or `-`), bit 28 a quoted
 * (raw) id, and bits 0-27 a 28-bit [LexerDelimiter] hash of the id. `=begin` blocks and the
 * `__END__` data have modes of their own.
 *
 * Known gaps: only the first heredoc of a line is followed, and only when no literal stays
 * open at its line end; an interpolation nested past [MAX_FRAMES] (or inside a heredoc body)
 * colors only when it closes on its line; `:"..."` and `%I` symbols do not interpolate; a
 * method call followed by a space and `/` reads `/` as division.
 */
internal object RubyLexer : HighlightLexer() {
    private const val MODE_SHIFT = 30
    private const val HEREDOC = 1
    private const val BEGIN = 2
    private const val DATA = 3
    private const val DEPTH_MASK = 3
    private const val FRAME_SHIFT = 2
    private const val FRAME_BITS = 9
    private const val FRAME_MASK = 0x1FF
    private const val MAX_FRAMES = 3
    private const val KIND_SHIFT = 5
    private const val RAW = 0
    private const val INTERP = 1
    private const val REGEX = 2
    private const val SYMBOL = 3
    private const val NEST_SHIFT = 7
    private const val MAX_NEST = 3
    private const val MAX_BRACES = 15
    private const val INDENTED = 1 shl 29
    private const val RAW_HEREDOC = 1 shl 28
    private const val HASH_BITS = 28

    /** Closing delimiters a literal can have; a frame stores index + 1 (0 marks an interpolation). */
    private const val CLOSERS = "\"'`/|!)]}>#$%&*+,-.:;=?@\\^_~"

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
    ): Int = lexCode(text, start, end, state, out, nested = false)

    private fun lexCode(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
        nested: Boolean,
    ): Int {
        when (state ushr MODE_SHIFT) {
            DATA -> {
                out.span(start, end, RoleCode.COMMENT)
                return state
            }
            BEGIN -> {
                out.span(start, end, RoleCode.COMMENT)
                return if (startsDirective(text, start, end, "=end")) 0 else state
            }
            HEREDOC -> return heredocLine(text, start, end, state, out)
        }
        if (state == 0 && !nested) {
            if (startsDirective(text, start, end, "=begin")) {
                out.span(start, end, RoleCode.COMMENT)
                return BEGIN shl MODE_SHIFT
            }
            if (LexerScan.equals(text, start, end, "__END__")) {
                out.span(start, end, RoleCode.KEYWORD)
                return DATA shl MODE_SHIFT
            }
        }
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
        var heredoc = 0
        while (index < end && !out.stopped) {
            val depth = st and DEPTH_MASK
            val frame = if (depth == 0) 0 else frameAt(st, depth - 1)
            if (depth > 0 && frame and 31 != 0) {
                val result = literal(text, index, end, st, nested, out)
                st = (result ushr 32).toInt()
                index = result.toInt()
                // Closed: a value ended. Opened an interpolation: an expression starts.
                if (st and DEPTH_MASK != depth) valueEnded = st and DEPTH_MASK < depth
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
                char == '"' || char == '\'' || char == '`' -> {
                    out.span(index, index + 1, RoleCode.STRING)
                    st = pushLiteral(st, char, if (char == '\'') RAW else INTERP)
                    index += 1
                    valueEnded = true
                }
                char == ':' && next == ':' -> {
                    out.span(index, index + 2, RoleCode.PUNCTUATION)
                    index += 2
                    afterDot = true
                    valueEnded = false
                }
                char == ':' && (next == '"' || next == '\'') -> {
                    out.span(index, index + 2, RoleCode.CONSTANT)
                    st = pushLiteral(st, next, SYMBOL)
                    index += 2
                    valueEnded = true
                }
                char == ':' && isNameStart(next) -> {
                    var after = nameEnd(text, index + 1, end)
                    if (after < end && (text[after] == '?' || text[after] == '!' || text[after] == '=') && !isOperatorPair(text, after, end)) after += 1
                    out.span(index, after, RoleCode.CONSTANT)
                    index = after
                    valueEnded = true
                }
                char == ':' && !valueEnded && operatorSymbolLength(text, index + 1, end) > 0 -> {
                    val after = index + 1 + operatorSymbolLength(text, index + 1, end)
                    out.span(index, after, RoleCode.CONSTANT)
                    index = after
                    valueEnded = true
                }
                char == '?' && !valueEnded && isCharacterLiteral(text, index, end) -> {
                    val after = if (next == '\\') minOf(index + 3, end) else index + 2
                    out.span(index, after, RoleCode.STRING)
                    index = after
                    valueEnded = true
                }
                char == '%' && percentLiteral(text, index, end, valueEnded, wasSpace) -> {
                    val letter = if (isAsciiLetter(next)) next else '\u0000'
                    val open = if (letter != '\u0000') index + 2 else index + 1
                    val kind =
                        when (letter) {
                            'q', 'w' -> RAW
                            'i', 'I', 's' -> SYMBOL
                            'r' -> REGEX
                            else -> INTERP
                        }
                    out.span(index, open + 1, if (kind == SYMBOL) RoleCode.CONSTANT else RoleCode.STRING)
                    st = pushLiteral(st, closerOf(text[open]), kind)
                    index = open + 1
                    valueEnded = true
                }
                char == '/' && !valueEnded -> {
                    out.span(index, index + 1, RoleCode.STRING)
                    st = pushLiteral(st, '/', REGEX)
                    index += 1
                    valueEnded = true
                }
                char == '<' && next == '<' && heredocAhead(text, index, end, valueEnded, wasSpace) -> {
                    val result = heredocStart(text, index, end, out)
                    index = result.toInt()
                    if (heredoc == 0) heredoc = (result ushr 32).toInt()
                    valueEnded = true
                }
                isDigit(char) -> {
                    val after = numberEnd(text, index, end)
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                    valueEnded = true
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
                            next != '\u0000' && next != ' ' && next != '\t' -> index + 2
                            else -> index + 1
                        }
                    out.span(index, after, RoleCode.VARIABLE)
                    index = after
                    valueEnded = true
                }
                isNameStart(char) -> {
                    val result = name(text, index, end, wasAfterDot, wasAfterDef, defParens > 0 || blockParams, out)
                    index = result.toInt()
                    val kind = (result ushr 32).toInt()
                    valueEnded = kind and VALUE != 0
                    if (kind and DEF != 0) afterDef = true
                    if (kind and BLOCK != 0) expectBlockParams = true
                    if (wasAfterDef && !afterDef && index < end && text[index] == '(') defParamsNext = true
                }
                char == '.' -> {
                    if (next == '.') {
                        val after = if (index + 2 < end && text[index + 2] == '.') index + 3 else index + 2
                        out.span(index, after, RoleCode.OPERATOR)
                        index = after
                        valueEnded = false
                    } else {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                        afterDot = true
                        afterDef = wasAfterDef
                        valueEnded = false
                    }
                }
                char == '&' && next == '.' -> {
                    out.span(index, index + 2, RoleCode.PUNCTUATION)
                    index += 2
                    afterDot = true
                    valueEnded = false
                }
                char == '{' || char == '}' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    val codeFrame = depth > 0
                    if (char == '{') {
                        if (codeFrame && frame ushr KIND_SHIFT < MAX_BRACES) st = setTop(st, frame + (1 shl KIND_SHIFT))
                        expectBlockParams = true
                        valueEnded = false
                    } else {
                        if (codeFrame) st = if (frame ushr KIND_SHIFT == 0) pop(st) else setTop(st, frame - (1 shl KIND_SHIFT))
                        valueEnded = true
                    }
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
                    if (defParens > 0 && char == ')') defParens -= 1
                    index += 1
                    valueEnded = true
                }
                char == ',' || char == ';' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    valueEnded = false
                }
                wasAfterDef && isOperatorChar(char) -> {
                    // `def ==(other)`, `def <=>(o)`, `def []=(k, v)`, `def +@`.
                    var after = index + 1
                    while (after < end && after < index + 3 && (isOperatorChar(text[after]) || text[after] == '@')) after += 1
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
                char.code >= 0x80 && char.isLetter() -> {
                    var after = index + 1
                    while (after < end && (isNamePart(text[after]) || (text[after].code >= 0x80 && text[after].isLetterOrDigit()))) after += 1
                    index = after
                    valueEnded = true
                }
                else -> index += 1
            }
        }
        if (out.stopped) return st
        if (heredoc != 0 && st == 0) return heredoc
        return st
    }

    /**
     * A name at [from]: a keyword, constant, method, label, or variable. Returns the word's
     * kind above the offset after it (and after a label's `:`).
     */
    private fun name(
        text: CharArray,
        from: Int,
        end: Int,
        afterDot: Boolean,
        afterDef: Boolean,
        parameter: Boolean,
        out: SpanSink,
    ): Long {
        var after = nameEnd(text, from, end)
        if (after < end && (text[after] == '?' || text[after] == '!') && !isOperatorPair(text, after, end)) after += 1
        val suffixed = text[after - 1] == '?' || text[after - 1] == '!'
        // A hash label or keyword argument: `name:` but not `name::`.
        if (!afterDot && !afterDef && after < end && text[after] == ':' && (after + 1 >= end || text[after + 1] != ':')) {
            if (parameter) {
                out.span(from, after, RoleCode.VARIABLE)
                out.span(after, after + 1, RoleCode.PUNCTUATION)
            } else {
                out.span(from, after + 1, RoleCode.CONSTANT)
            }
            return packed(0, after + 1)
        }
        val call = after < end && text[after] == '('
        if (afterDef) {
            if (LexerScan.equals(text, from, after, "self") && after < end && text[after] == '.') {
                out.span(from, after, RoleCode.VARIABLE)
                return packed(DEF, after)
            }
            // A setter: `def name=(value)`.
            if (after < end && text[after] == '=' && !isOperatorPair(text, after, end)) after += 1
            out.span(from, after, RoleCode.FUNCTION)
            return packed(0, after)
        }
        if (afterDot) {
            // `Foo::Bar` is a constant; `obj.name` a method, Property unless called.
            val role =
                when {
                    call || suffixed -> RoleCode.FUNCTION
                    text[from] in 'A'..'Z' -> if (isAllCaps(text, from, after)) RoleCode.CONSTANT else RoleCode.TYPE
                    else -> RoleCode.PROPERTY
                }
            out.span(from, after, role)
            return packed(VALUE, after)
        }
        val kind = words.lookup(text, from, after)
        if (kind != 0) {
            out.span(from, after, kind and 0xFF)
            return packed(kind, after)
        }
        val first = text[from]
        val role =
            when {
                first in 'A'..'Z' -> if (isAllCaps(text, from, after)) RoleCode.CONSTANT else RoleCode.TYPE
                parameter -> RoleCode.VARIABLE
                call || suffixed -> RoleCode.FUNCTION
                else -> 0
            }
        if (role != 0) {
            out.span(from, after, role)
        } else if (!out.collecting) {
            out.span(from, after, RoleCode.VARIABLE)
        }
        return packed(VALUE, after)
    }

    /**
     * Lexes inside the literal on top of the stack from [from]; returns the new state above the
     * offset where it stopped (at its close, an interpolation, or the line end).
     */
    private fun literal(
        text: CharArray,
        from: Int,
        end: Int,
        state: Int,
        nested: Boolean,
        out: SpanSink,
    ): Long {
        val depth = state and DEPTH_MASK
        var frame = frameAt(state, depth - 1)
        val closer = CLOSERS[(frame and 31) - 1]
        val kind = (frame ushr KIND_SHIFT) and 3
        val opener = openerOf(closer)
        val interpolates = kind == INTERP || kind == REGEX
        val role = if (kind == SYMBOL) RoleCode.CONSTANT else RoleCode.STRING
        var nest = (frame ushr NEST_SHIFT) and 3
        var segment = from
        var index = from
        while (index < end) {
            val char = text[index]
            when {
                char == '\\' -> {
                    val length = if (index + 1 < end) 2 else 1
                    if (interpolates || (length == 2 && (text[index + 1] == '\\' || text[index + 1] == closer))) {
                        out.span(segment, index, role)
                        out.span(index, index + length, RoleCode.CONSTANT)
                        segment = index + length
                    }
                    index += length
                }
                opener != '\u0000' && char == opener -> {
                    if (nest < MAX_NEST) nest += 1
                    index += 1
                }
                char == closer -> {
                    if (nest > 0) {
                        nest -= 1
                        index += 1
                    } else {
                        var after = index + 1
                        if (kind == REGEX) {
                            while (after < end && isAsciiLetter(text[after])) after += 1
                        }
                        out.span(segment, after, role)
                        return packed(pop(state), after)
                    }
                }
                interpolates && char == '#' && index + 1 < end && text[index + 1] == '{' -> {
                    out.span(segment, index, role)
                    frame = (frame and (MAX_NEST shl NEST_SHIFT).inv()) or (nest shl NEST_SHIFT)
                    val saved = setTop(state, frame)
                    if (depth < MAX_FRAMES) {
                        out.span(index, index + 2, RoleCode.PUNCTUATION)
                        return packed(push(saved, 0), index + 2)
                    }
                    val close = if (nested) -1 else inlineInterpolation(text, index, end, out)
                    if (close < 0) {
                        // The interpolation runs past the line, so the literal cannot close on it.
                        out.span(index, end, role)
                        return packed(saved, end)
                    }
                    index = close
                    segment = close
                }
                interpolates && char == '#' && index + 1 < end && (text[index + 1] == '@' || text[index + 1] == '$') -> {
                    var after = index + 2
                    if (after < end && text[after] == '@') after += 1
                    val nameEnd = nameEnd(text, after, end)
                    if (nameEnd > after) {
                        out.span(segment, index, role)
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        out.span(index + 1, nameEnd, RoleCode.VARIABLE)
                        segment = nameEnd
                        index = nameEnd
                    } else {
                        index += 1
                    }
                }
                else -> index += 1
            }
        }
        out.span(segment, end, role)
        frame = (frame and (MAX_NEST shl NEST_SHIFT).inv()) or (nest shl NEST_SHIFT)
        return packed(setTop(state, frame), end)
    }

    /**
     * `#{...}` that closes on this line, colored without a stack frame: `#{` and `}` as
     * Punctuation and the code between lexed on its own. Returns the offset after `}`, or -1.
     */
    private fun inlineInterpolation(
        text: CharArray,
        hash: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        var braces = 0
        var index = hash + 2
        while (index < end) {
            when (text[index]) {
                '{' -> braces += 1
                '}' -> {
                    if (braces == 0) {
                        out.span(hash, hash + 2, RoleCode.PUNCTUATION)
                        lexCode(text, hash + 2, index, 0, out, nested = true)
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        return index + 1
                    }
                    braces -= 1
                }
            }
            index += 1
        }
        return -1
    }

    /** Lexes `<<~ID`, `<<-ID`, or `<<ID` (or a quoted id) as String; returns the heredoc state above the offset after it. */
    private fun heredocStart(
        text: CharArray,
        at: Int,
        end: Int,
        out: SpanSink,
    ): Long {
        var index = at + 2
        var indented = false
        if (text[index] == '~' || text[index] == '-') {
            indented = true
            index += 1
        }
        var raw = false
        val idStart: Int
        val idEnd: Int
        if (text[index] == '\'' || text[index] == '"' || text[index] == '`') {
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
        var bits = (HEREDOC shl MODE_SHIFT) or LexerDelimiter.of(text, idStart, idEnd, HASH_BITS)
        if (indented) bits = bits or INDENTED
        if (raw) bits = bits or RAW_HEREDOC
        return packed(bits, index)
    }

    /** A heredoc body line, or its terminator (String, which returns to code). */
    private fun heredocLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        val from = if (state and INDENTED != 0) LexerScan.skipBlanks(text, start, end) else start
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
                out.span(segment, index, RoleCode.STRING)
                val close = inlineInterpolation(text, index, end, out)
                if (close < 0) {
                    // No `}` on this line: the rest stays text (and later `#{` need not be searched again).
                    segment = index
                    break
                }
                index = close
                segment = close
            } else {
                index += 1
            }
        }
        out.span(segment, end, RoleCode.STRING)
        return state
    }

    /** True when `%` at [at] opens a percent literal. */
    private fun percentLiteral(
        text: CharArray,
        at: Int,
        end: Int,
        valueEnded: Boolean,
        spaceBefore: Boolean,
    ): Boolean {
        if (at + 1 >= end) return false
        val next = text[at + 1]
        if (next == 'q' || next == 'Q' || next == 'w' || next == 'W' || next == 'i' || next == 'I' || next == 'r' || next == 's' || next == 'x') {
            if (at + 2 >= end) return false
            val delimiter = text[at + 2]
            if (!isDelimiter(delimiter)) return false
            return !valueEnded || (spaceBefore && openerOf(closerOf(delimiter)) == delimiter)
        }
        return !valueEnded && isDelimiter(next) && next != '='
    }

    /** True when `<<` at [at] starts a heredoc rather than the operator. */
    private fun heredocAhead(
        text: CharArray,
        at: Int,
        end: Int,
        valueEnded: Boolean,
        spaceBefore: Boolean,
    ): Boolean {
        var index = at + 2
        if (index >= end) return false
        val squiggly = text[index] == '~' || text[index] == '-'
        if (squiggly) index += 1
        if (index >= end) return false
        val char = text[index]
        val quoted = char == '\'' || char == '"' || char == '`'
        if (squiggly) return quoted || isNameStart(char)
        if (!(quoted || char in 'A'..'Z' || char == '_')) return false
        return !valueEnded || spaceBefore
    }

    /** `?a`, `?\n`: a character literal when the character is not followed by more of a name. */
    private fun isCharacterLiteral(
        text: CharArray,
        at: Int,
        end: Int,
    ): Boolean {
        if (at + 1 >= end) return false
        val char = text[at + 1]
        if (char == ' ' || char == '\t') return false
        val after = if (char == '\\') at + 3 else at + 2
        return after >= end || !isNamePart(text[after])
    }

    /** The length of an operator method name at [from] for a `:+` style symbol, or 0. */
    private fun operatorSymbolLength(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        for (operator in operatorSymbols) {
            if (LexerScan.startsWith(text, from, end, operator)) return operator.length
        }
        return 0
    }

    private val operatorSymbols =
        arrayOf(
            "[]=", "[]", "<=>", "===", "==", "=~", "!=", "!~", "<<", ">>", "<=", ">=", "**", "+@", "-@", "+", "-", "*", "/", "%", "<",
            ">", "!", "&", "|", "^", "~",
        )

    private fun numberEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at
        if (text[index] == '0' && index + 1 < end && text[index + 1] in "xXbBoOdD") {
            index += 2
            while (index < end && (isHexDigit(text[index]) || text[index] == '_')) index += 1
            return index
        }
        while (index < end && (isDigit(text[index]) || text[index] == '_')) index += 1
        if (index + 1 < end && text[index] == '.' && isDigit(text[index + 1])) {
            index += 1
            while (index < end && (isDigit(text[index]) || text[index] == '_')) index += 1
        }
        if (index < end && (text[index] == 'e' || text[index] == 'E')) {
            var exponent = index + 1
            if (exponent < end && (text[exponent] == '+' || text[exponent] == '-')) exponent += 1
            if (exponent < end && isDigit(text[exponent])) {
                index = exponent
                while (index < end && isDigit(text[index])) index += 1
            }
        }
        if (index < end && text[index] == 'r') index += 1
        if (index < end && text[index] == 'i') index += 1
        return index
    }

    /** `=begin` / `=end` at the line start, alone or followed by a blank. */
    private fun startsDirective(
        text: CharArray,
        start: Int,
        end: Int,
        word: String,
    ): Boolean {
        if (!LexerScan.startsWith(text, start, end, word)) return false
        val after = start + word.length
        return after == end || text[after] == ' ' || text[after] == '\t'
    }

    private fun pushLiteral(
        state: Int,
        close: Char,
        kind: Int,
    ): Int {
        val index = CLOSERS.indexOf(close)
        if (state and DEPTH_MASK >= MAX_FRAMES || index < 0) return state
        return push(state, (index + 1) or (kind shl KIND_SHIFT))
    }

    private fun frameAt(
        state: Int,
        level: Int,
    ): Int = (state ushr (FRAME_SHIFT + level * FRAME_BITS)) and FRAME_MASK

    private fun push(
        state: Int,
        frame: Int,
    ): Int {
        val depth = state and DEPTH_MASK
        return (state and DEPTH_MASK.inv()) or (frame shl (FRAME_SHIFT + depth * FRAME_BITS)) or (depth + 1)
    }

    private fun pop(state: Int): Int {
        val depth = state and DEPTH_MASK
        val shift = FRAME_SHIFT + (depth - 1) * FRAME_BITS
        return (state and (FRAME_MASK shl shift).inv() and DEPTH_MASK.inv()) or (depth - 1)
    }

    private fun setTop(
        state: Int,
        frame: Int,
    ): Int {
        val shift = FRAME_SHIFT + ((state and DEPTH_MASK) - 1) * FRAME_BITS
        return (state and (FRAME_MASK shl shift).inv()) or (frame shl shift)
    }

    private fun packed(
        high: Int,
        offset: Int,
    ): Long = (high.toLong() shl 32) or (offset.toLong() and 0xFFFFFFFFL)

    private fun closerOf(open: Char): Char =
        when (open) {
            '(' -> ')'
            '[' -> ']'
            '{' -> '}'
            '<' -> '>'
            else -> open
        }

    private fun openerOf(close: Char): Char =
        when (close) {
            ')' -> '('
            ']' -> '['
            '}' -> '{'
            '>' -> '<'
            else -> '\u0000'
        }

    /** A percent-literal delimiter: ASCII punctuation (an opener stands for its closer). */
    private fun isDelimiter(char: Char): Boolean = char == '(' || char == '[' || char == '{' || char == '<' || CLOSERS.indexOf(char) >= 0

    /** `!=`, `?=`... and `==`: a `?`/`!`/`=` at [at] that starts an operator, not a name suffix. */
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
            '+', '-', '*', '/', '%', '=', '<', '>', '!', '&', '|', '^', '~', '?', ':' -> true
            else -> false
        }

    private fun isAllCaps(
        text: CharArray,
        from: Int,
        to: Int,
    ): Boolean {
        if (to - from < 2) return false
        for (index in from until to) if (text[index] in 'a'..'z') return false
        return true
    }

    private fun nameEnd(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        var index = from
        while (index < end && isNamePart(text[index])) index += 1
        return index
    }

    private fun isNameStart(char: Char): Boolean = isAsciiLetter(char) || char == '_'

    private fun isNamePart(char: Char): Boolean = isAsciiLetter(char) || isDigit(char) || char == '_'

    init {
        words.register(
            RoleCode.KEYWORD,
            "alias", "and", "begin", "BEGIN", "break", "case", "class", "else", "elsif", "END", "ensure", "for", "if", "in",
            "module", "next", "not", "or", "redo", "rescue", "retry", "return", "then", "undef", "unless", "until", "when",
            "while", "yield", "super", "defined?", "raise", "require", "require_relative", "include", "extend", "prepend",
            "attr_accessor", "attr_reader", "attr_writer", "private", "protected", "public", "module_function", "using",
            "private_constant", "refine",
        )
        words.register(RoleCode.KEYWORD or DEF, "def")
        words.register(RoleCode.KEYWORD or BLOCK, "do")
        words.register(RoleCode.KEYWORD or VALUE, "end", "__FILE__", "__LINE__", "__dir__", "__method__", "__ENCODING__")
        words.register(RoleCode.CONSTANT or VALUE, "true", "false", "nil")
        words.register(RoleCode.VARIABLE or VALUE, "self")
        words.register(
            RoleCode.FUNCTION,
            "puts", "print", "p", "pp", "printf", "sprintf", "format", "gets", "loop", "lambda", "proc", "catch", "throw",
            "sleep", "rand", "srand", "exit", "abort", "at_exit", "binding", "block_given?", "freeze", "warn", "fail",
        )
    }
}
