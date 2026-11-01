package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * Python 3, up to 3.12 f-strings (PEP 701) and 3.14 t-strings.
 *
 * Roles: comments are Comment; strings (docstrings too) are String with escapes as Constant
 * outside raw strings; a string prefix (`r`, `b`, `f`, `rb`, ...) is Keyword. In f- and
 * t-strings, a replacement field's `{` `}` and the `{{` `}}` escapes are Constant, a `!r`
 * conversion and a `:spec` are Keyword, and the expression inside lexes as code. Numbers are
 * Number; keywords are Keyword, with `and`, `or`, `not`, `in`, `is` as Operator; `True`,
 * `False`, `None` and ALL_CAPS names are Constant; soft keywords (`match`, `case`, `type`,
 * and `_` in a case pattern) are Keyword where they start a statement of that shape. A name
 * after `def` or before `(` is Function, after `class` or capitalized is Type, after `.` is
 * Property (Function when called); a decorator, `@` included, is Function. Builtin functions are
 * Function, builtin types Type, `self` and `cls` Variable. Operators are Operator; brackets,
 * `,`, `;`, `:`, `.` and `->` are Punctuation.
 *
 * States pack a stack of up to [MAX_FRAMES] open strings, outermost in the low byte, with the
 * stack depth in bits 24 and 25; 0 is plain code. A frame holds the quote, triple and raw flags,
 * its mode ([PLAIN] string, or an f-string's [LITERAL] text, [EXPRESSION], [SPEC], or the
 * [SPEC_EXPRESSION] nested in a spec), and the brackets open in its expression (0 to 3). At a
 * line end, triple-quoted strings stay open; a one-line string stays open only after a trailing
 * backslash or inside an expression with brackets open (the 3.12 multi-line form), so an
 * unterminated one-line string (common while a fence streams in) ends at its line.
 *
 * Known gaps: a string opened inside a fourth level of f-string nesting colors to its close on
 * the same line without f-string parts; a bracket depth past 3 inside an f-string expression is
 * not counted; statement-level rules (decorators, soft keywords) do not know about open
 * brackets of plain code.
 */
internal object PythonLexer : HighlightLexer() {
    private const val DOUBLE = 1
    private const val TRIPLE = 2
    private const val RAW = 4
    private const val MODE_SHIFT = 3
    private const val MODE_MASK = 7 shl MODE_SHIFT
    private const val PLAIN = 1
    private const val LITERAL = 2
    private const val EXPRESSION = 3
    private const val SPEC = 4
    private const val SPEC_EXPRESSION = 5
    private const val BRACKET_SHIFT = 6
    private const val MAX_BRACKETS = 3
    private const val FRAME_BITS = 8
    private const val FRAME_MASK = 0xFF
    private const val DEPTH_SHIFT = 24
    private const val MAX_FRAMES = 3

    // Outcomes of the string scanners, packed above the offset they return.
    private const val AT_LINE_END = 0
    private const val CLOSED = 1
    private const val FIELD_OPENED = 2
    private const val CONTINUED = 3
    private const val FIELD_CLOSED = 4

    // Word kinds: a role code in the low byte plus flags.
    private const val HARD = 1 shl 8
    private const val DEF = 1 shl 9
    private const val CLASS = 1 shl 10
    private const val SOFT_MATCH = 1 shl 11
    private const val SOFT_TYPE = 1 shl 12
    private const val SOFT_CASE = 1 shl 13

    private const val NAME_NONE = 0

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        var st = state
        var index = start
        var statementStart = state == 0
        var afterDot = false
        var pendingName = NAME_NONE
        var caseLine = false
        var continued = false
        while (index < end && !out.stopped) {
            val depth = st ushr DEPTH_SHIFT
            var expression = false
            if (depth > 0) {
                val frame = (st ushr ((depth - 1) * FRAME_BITS)) and FRAME_MASK
                val mode = (frame and MODE_MASK) ushr MODE_SHIFT
                if (mode == PLAIN || mode == LITERAL || mode == SPEC) {
                    val result = if (mode == SPEC) spec(text, index, end, frame, out) else literal(text, index, end, frame, out)
                    index = result.toInt()
                    when ((result ushr 32).toInt()) {
                        CLOSED -> st = pop(st)
                        FIELD_OPENED -> st = setTop(st, withMode(frame, if (mode == SPEC) SPEC_EXPRESSION else EXPRESSION))
                        FIELD_CLOSED -> st = setTop(st, withMode(frame, LITERAL))
                        CONTINUED -> continued = true
                    }
                    statementStart = false
                    afterDot = false
                    pendingName = NAME_NONE
                    continue
                }
                expression = true
                val char = text[index]
                val brackets = frame ushr BRACKET_SHIFT
                if (brackets == 0) {
                    if (char == '}') {
                        out.span(index, index + 1, RoleCode.CONSTANT)
                        st = setTop(st, withMode(frame, if (mode == EXPRESSION) LITERAL else SPEC))
                        index += 1
                        afterDot = false
                        pendingName = NAME_NONE
                        continue
                    }
                    if (mode == EXPRESSION && char == ':') {
                        out.span(index, index + 1, RoleCode.KEYWORD)
                        st = setTop(st, withMode(frame, SPEC))
                        index += 1
                        continue
                    }
                    if (mode == EXPRESSION && char == '!' && index + 1 < end && isConversion(text[index + 1])) {
                        out.span(index, index + 2, RoleCode.KEYWORD)
                        index += 2
                        continue
                    }
                }
                if (char == '(' || char == '[' || char == '{') {
                    if (brackets < MAX_BRACKETS) st = setTop(st, frame + (1 shl BRACKET_SHIFT))
                } else if (char == ')' || char == ']' || char == '}') {
                    if (brackets > 0) st = setTop(st, frame - (1 shl BRACKET_SHIFT))
                }
            }
            val char = text[index]
            if (char == ' ' || char == '\t' || char == '\u000C') {
                index += 1
                continue
            }
            val wasAfterDot = afterDot
            val name = pendingName
            afterDot = false
            pendingName = NAME_NONE
            val atStatement = statementStart
            statementStart = false
            when {
                char == '#' -> {
                    out.span(index, end, RoleCode.COMMENT)
                    index = end
                }
                char == '"' || char == '\'' -> {
                    val result = openString(text, index, index, end, st, out)
                    st = (result ushr 32).toInt()
                    index = result.toInt()
                }
                isDigit(char) || (char == '.' && index + 1 < end && isDigit(text[index + 1])) -> {
                    val after = numberEnd(text, index, end)
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                }
                char == '.' -> {
                    if (index + 2 < end && text[index + 1] == '.' && text[index + 2] == '.') {
                        out.span(index, index + 3, RoleCode.CONSTANT)
                        index += 3
                    } else {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                        afterDot = true
                    }
                }
                isNameStart(char) -> {
                    var after = index + 1
                    while (after < end && isNamePart(text[after])) after += 1
                    if (after < end && (text[after] == '"' || text[after] == '\'') && isStringPrefix(text, index, after)) {
                        val result = openString(text, index, after, end, st, out)
                        st = (result ushr 32).toInt()
                        index = result.toInt()
                    } else if (!out.collecting) {
                        // State-only pass: names never change the state; the span only feeds polling.
                        out.span(index, after, RoleCode.VARIABLE)
                        index = after
                    } else {
                        val kind = lookup(text, index, after)
                        if (kind and DEF != 0) pendingName = RoleCode.FUNCTION
                        if (kind and CLASS != 0) pendingName = RoleCode.TYPE
                        val topLevel = atStatement && !expression
                        val role =
                            when {
                                kind and HARD != 0 -> kind and 0xFF
                                name != NAME_NONE -> name
                                wasAfterDot -> if (callFollows(text, after, end)) RoleCode.FUNCTION else RoleCode.PROPERTY
                                kind and (SOFT_MATCH or SOFT_CASE) != 0 && topLevel && startsBlock(text, after, end) -> {
                                    if (kind and SOFT_CASE != 0) caseLine = true
                                    RoleCode.KEYWORD
                                }
                                kind and SOFT_TYPE != 0 && topLevel && startsTypeAlias(text, after, end) -> RoleCode.KEYWORD
                                caseLine && after - index == 1 && char == '_' -> RoleCode.KEYWORD
                                kind and 0xFF != 0 -> kind and 0xFF
                                callFollows(text, after, end) -> RoleCode.FUNCTION
                                else -> shapeRole(text, index, after)
                            }
                        if (role != 0) out.span(index, after, role)
                        index = after
                    }
                }
                char == '@' && atStatement && !expression -> {
                    var after = LexerScan.skipBlanks(text, index + 1, end)
                    while (after < end && (isNamePart(text[after]) || text[after] == '.')) after += 1
                    out.span(index, after, RoleCode.FUNCTION)
                    index = after
                }
                char == '(' || char == ')' || char == '[' || char == ']' || char == '{' || char == '}' || char == ',' || char == ';' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                }
                char == ':' -> {
                    if (index + 1 < end && text[index + 1] == '=') {
                        out.span(index, index + 2, RoleCode.OPERATOR)
                        index += 2
                    } else {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                    }
                }
                char == '-' && index + 1 < end && text[index + 1] == '>' -> {
                    out.span(index, index + 2, RoleCode.PUNCTUATION)
                    index += 2
                }
                isOperator(char) -> {
                    out.span(index, index + 1, RoleCode.OPERATOR)
                    index += 1
                }
                else -> index += 1
            }
        }
        if (out.stopped) return st
        return lineEndState(st, continued)
    }

    /**
     * Opens a string whose prefix is `text[prefixStart, quote)`; returns the new state above the
     * offset after the opening quotes. Past [MAX_FRAMES] the string colors to its close on this
     * line without f-string parts, and the state does not change.
     */
    private fun openString(
        text: CharArray,
        prefixStart: Int,
        quote: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Long {
        var raw = false
        var formatted = false
        for (index in prefixStart until quote) {
            when (text[index]) {
                'r', 'R' -> raw = true
                'f', 'F', 't', 'T' -> formatted = true
            }
        }
        if (quote > prefixStart) out.span(prefixStart, quote, RoleCode.KEYWORD)
        val quoteChar = text[quote]
        val triple = quote + 2 < end && text[quote + 1] == quoteChar && text[quote + 2] == quoteChar
        val opened = if (triple) quote + 3 else quote + 1
        val depth = state ushr DEPTH_SHIFT
        if (depth >= MAX_FRAMES) {
            val close = closingQuote(text, opened, end, quoteChar, triple, raw)
            out.span(quote, close, RoleCode.STRING)
            return (state.toLong() shl 32) or close.toLong()
        }
        out.span(quote, opened, RoleCode.STRING)
        var frame = ((if (formatted) LITERAL else PLAIN) shl MODE_SHIFT)
        if (quoteChar == '"') frame = frame or DOUBLE
        if (triple) frame = frame or TRIPLE
        if (raw) frame = frame or RAW
        val pushed = (state and ((1 shl DEPTH_SHIFT) - 1)) or (frame shl (depth * FRAME_BITS)) or ((depth + 1) shl DEPTH_SHIFT)
        return (pushed.toLong() shl 32) or opened.toLong()
    }

    /** The offset after the string closing from [from] on this line, or [end]; for strings nested too deep. */
    private fun closingQuote(
        text: CharArray,
        from: Int,
        end: Int,
        quote: Char,
        triple: Boolean,
        raw: Boolean,
    ): Int {
        var index = from
        while (index < end) {
            val char = text[index]
            if (char == '\\') {
                index += 2
            } else if (char == quote && (!triple || (index + 2 < end && text[index + 1] == quote && text[index + 2] == quote))) {
                return if (triple) index + 3 else index + 1
            } else {
                index += 1
            }
        }
        return end
    }

    /**
     * Lexes string text (a [PLAIN] string or an f-string's [LITERAL] part) from [from]: String,
     * with escapes and `{{` `}}` as Constant. Returns the outcome above the offset where it stopped.
     */
    private fun literal(
        text: CharArray,
        from: Int,
        end: Int,
        frame: Int,
        out: SpanSink,
    ): Long {
        val quote = if (frame and DOUBLE != 0) '"' else '\''
        val triple = frame and TRIPLE != 0
        val raw = frame and RAW != 0
        val formatted = (frame and MODE_MASK) ushr MODE_SHIFT == LITERAL
        var segment = from
        var index = from
        while (index < end) {
            val char = text[index]
            when {
                char == '\\' -> {
                    if (index + 1 >= end) {
                        out.span(segment, end, RoleCode.STRING)
                        return packed(CONTINUED, end)
                    }
                    if (raw) {
                        index += 2
                    } else {
                        val length = escapeLength(text, index, end)
                        if (length == 0) {
                            index += 1
                        } else {
                            out.span(segment, index, RoleCode.STRING)
                            out.span(index, index + length, RoleCode.CONSTANT)
                            index += length
                            segment = index
                        }
                    }
                }
                char == quote -> {
                    if (!triple) {
                        out.span(segment, index + 1, RoleCode.STRING)
                        return packed(CLOSED, index + 1)
                    }
                    if (index + 2 < end && text[index + 1] == quote && text[index + 2] == quote) {
                        out.span(segment, index + 3, RoleCode.STRING)
                        return packed(CLOSED, index + 3)
                    }
                    index += 1
                }
                formatted && (char == '{' || char == '}') -> {
                    if (index + 1 < end && text[index + 1] == char) {
                        out.span(segment, index, RoleCode.STRING)
                        out.span(index, index + 2, RoleCode.CONSTANT)
                        index += 2
                        segment = index
                    } else if (char == '{') {
                        out.span(segment, index, RoleCode.STRING)
                        out.span(index, index + 1, RoleCode.CONSTANT)
                        return packed(FIELD_OPENED, index + 1)
                    } else {
                        // A lone `}` is an error in Python; it stays string text.
                        index += 1
                    }
                }
                else -> index += 1
            }
        }
        out.span(segment, end, RoleCode.STRING)
        return packed(AT_LINE_END, end)
    }

    /** Lexes a format spec after `:` as Keyword, up to a nested `{`, the field's `}`, or the string's close. */
    private fun spec(
        text: CharArray,
        from: Int,
        end: Int,
        frame: Int,
        out: SpanSink,
    ): Long {
        val quote = if (frame and DOUBLE != 0) '"' else '\''
        val triple = frame and TRIPLE != 0
        var index = from
        while (index < end) {
            val char = text[index]
            if (char == '{' || char == '}') {
                out.span(from, index, RoleCode.KEYWORD)
                out.span(index, index + 1, RoleCode.CONSTANT)
                return packed(if (char == '{') FIELD_OPENED else FIELD_CLOSED, index + 1)
            }
            if (char == quote) {
                val close = if (!triple) index + 1 else if (index + 2 < end && text[index + 1] == quote && text[index + 2] == quote) index + 3 else -1
                if (close > 0) {
                    out.span(from, index, RoleCode.KEYWORD)
                    out.span(index, close, RoleCode.STRING)
                    return packed(CLOSED, close)
                }
            }
            index += 1
        }
        out.span(from, end, RoleCode.KEYWORD)
        return packed(AT_LINE_END, end)
    }

    /** The length of the escape at [backslash] (followed by at least one character), or 0 when Python keeps it literally. */
    private fun escapeLength(
        text: CharArray,
        backslash: Int,
        end: Int,
    ): Int =
        when (text[backslash + 1]) {
            '\\', '\'', '"', 'a', 'b', 'f', 'n', 'r', 't', 'v' -> 2
            in '0'..'7' -> {
                var length = 2
                while (length < 4 && backslash + length < end && text[backslash + length] in '0'..'7') length += 1
                length
            }
            'x' -> hexEscape(text, backslash, end, 2)
            'u' -> hexEscape(text, backslash, end, 4)
            'U' -> hexEscape(text, backslash, end, 8)
            'N' -> namedEscape(text, backslash, end)
            else -> 0
        }

    private fun hexEscape(
        text: CharArray,
        backslash: Int,
        end: Int,
        digits: Int,
    ): Int {
        for (offset in 2 until 2 + digits) {
            if (backslash + offset >= end || !isHexDigit(text[backslash + offset])) return 2
        }
        return 2 + digits
    }

    /** `\N{NAME}`: letters, digits, spaces, and hyphens up to a `}` within [MAX_ESCAPE_NAME] characters. */
    private fun namedEscape(
        text: CharArray,
        backslash: Int,
        end: Int,
    ): Int {
        if (backslash + 2 >= end || text[backslash + 2] != '{') return 2
        var index = backslash + 3
        val limit = minOf(end, index + MAX_ESCAPE_NAME)
        while (index < limit) {
            val char = text[index]
            if (char == '}') return if (index > backslash + 3) index + 1 - backslash else 2
            if (!(LexerScan.isAsciiLetter(char) || isDigit(char) || char == ' ' || char == '-')) return 2
            index += 1
        }
        return 2
    }

    private const val MAX_ESCAPE_NAME = 96

    /** The state at a line end: drops the innermost-first one-line strings that cannot continue. */
    private fun lineEndState(
        state: Int,
        continued: Boolean,
    ): Int {
        val depth = state ushr DEPTH_SHIFT
        for (level in 0 until depth) {
            val frame = (state ushr (level * FRAME_BITS)) and FRAME_MASK
            if (frame and TRIPLE != 0) continue
            val mode = (frame and MODE_MASK) ushr MODE_SHIFT
            val survives =
                if (mode == EXPRESSION || mode == SPEC_EXPRESSION) {
                    frame ushr BRACKET_SHIFT > 0
                } else {
                    continued && level == depth - 1
                }
            if (!survives) return if (level == 0) 0 else (state and ((1 shl (level * FRAME_BITS)) - 1)) or (level shl DEPTH_SHIFT)
        }
        return state
    }

    private fun pop(state: Int): Int {
        val depth = state ushr DEPTH_SHIFT
        val kept = state and ((1 shl ((depth - 1) * FRAME_BITS)) - 1)
        return if (depth == 1) 0 else kept or ((depth - 1) shl DEPTH_SHIFT)
    }

    private fun setTop(
        state: Int,
        frame: Int,
    ): Int {
        val shift = ((state ushr DEPTH_SHIFT) - 1) * FRAME_BITS
        return (state and (FRAME_MASK shl shift).inv()) or (frame shl shift)
    }

    /** [frame] in [mode] with no brackets open. */
    private fun withMode(
        frame: Int,
        mode: Int,
    ): Int = (frame and (DOUBLE or TRIPLE or RAW)) or (mode shl MODE_SHIFT)

    private fun packed(
        outcome: Int,
        offset: Int,
    ): Long = (outcome.toLong() shl 32) or offset.toLong()

    private fun isConversion(char: Char): Boolean = char == 'r' || char == 's' || char == 'a'

    private fun isOperator(char: Char): Boolean =
        when (char) {
            '+', '-', '*', '/', '%', '&', '|', '^', '~', '<', '>', '=', '!', '@' -> true
            else -> false
        }

    private fun isNameStart(char: Char): Boolean = LexerScan.isAsciiLetter(char) || char == '_' || (char.code >= 0x80 && char.isLetter())

    private fun isNamePart(char: Char): Boolean =
        LexerScan.isAsciiLetter(char) || isDigit(char) || char == '_' || (char.code >= 0x80 && char.isLetterOrDigit())

    /** `r u f b t` alone, or `r` with one of `b f t` in either order, any case. */
    private fun isStringPrefix(
        text: CharArray,
        from: Int,
        to: Int,
    ): Boolean {
        val length = to - from
        if (length == 1) {
            return when (text[from]) {
                'r', 'R', 'u', 'U', 'f', 'F', 'b', 'B', 't', 'T' -> true
                else -> false
            }
        }
        if (length != 2) return false
        val first = text[from].lowercaseChar()
        val second = text[from + 1].lowercaseChar()
        return (first == 'r' && (second == 'b' || second == 'f' || second == 't')) ||
            (second == 'r' && (first == 'b' || first == 'f' || first == 't'))
    }

    private fun numberEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at
        if (text[index] == '0' && index + 1 < end && text[index + 1] in "xXoObB") {
            index += 2
            while (index < end && (isHexDigit(text[index]) || text[index] == '_')) index += 1
            return index
        }
        while (index < end && (isDigit(text[index]) || text[index] == '_')) index += 1
        if (index < end && text[index] == '.') {
            index += 1
            while (index < end && (isDigit(text[index]) || text[index] == '_')) index += 1
        }
        if (index < end && (text[index] == 'e' || text[index] == 'E')) {
            var exponent = index + 1
            if (exponent < end && (text[exponent] == '+' || text[exponent] == '-')) exponent += 1
            if (exponent < end && isDigit(text[exponent])) {
                index = exponent
                while (index < end && (isDigit(text[index]) || text[index] == '_')) index += 1
            }
        }
        if (index < end && (text[index] == 'j' || text[index] == 'J')) index += 1
        return index
    }

    private fun callFollows(
        text: CharArray,
        after: Int,
        end: Int,
    ): Boolean {
        val next = LexerScan.skipBlanks(text, after, end)
        return next < end && text[next] == '('
    }

    /**
     * `match` / `case` start a block when what follows is not an assignment, attribute, or
     * separator and the line ends with `:` (before any comment).
     */
    private fun startsBlock(
        text: CharArray,
        after: Int,
        end: Int,
    ): Boolean {
        val next = LexerScan.skipBlanks(text, after, end)
        if (next == after || next >= end) {
            // `match(x):` and `case[...]:` are blocks too; anything glued on otherwise is a name.
            if (next >= end || (text[next] != '(' && text[next] != '[')) return false
        }
        when (text[next]) {
            '=', '.', ',', ')', ']', '}', ':', ';' -> return false
        }
        if (isOperator(text[next]) && next + 1 < end && text[next + 1] == '=') return false
        var last = ' '
        var index = next
        var quote = ' '
        while (index < end) {
            val char = text[index]
            if (quote != ' ') {
                if (char == '\\') index += 1 else if (char == quote) quote = ' '
            } else if (char == '#') {
                break
            } else if (char == '"' || char == '\'') {
                quote = char
            }
            if (char != ' ' && char != '\t') last = char
            index += 1
        }
        return quote == ' ' && last == ':'
    }

    /** `type Name = ...` or `type Name[T] = ...`. */
    private fun startsTypeAlias(
        text: CharArray,
        after: Int,
        end: Int,
    ): Boolean {
        var index = LexerScan.skipBlanks(text, after, end)
        if (index == after || index >= end || !isNameStart(text[index])) return false
        while (index < end && isNamePart(text[index])) index += 1
        index = LexerScan.skipBlanks(text, index, end)
        if (index >= end) return false
        return text[index] == '[' || (text[index] == '=' && (index + 1 >= end || text[index + 1] != '='))
    }

    /** ALL_CAPS is Constant, Capitalized (or a single capital) is Type, `__dunder__` is Variable. */
    private fun shapeRole(
        text: CharArray,
        from: Int,
        to: Int,
    ): Int {
        val first = text[from]
        if (first in 'A'..'Z') {
            if (to - from == 1) return RoleCode.TYPE
            for (index in from + 1 until to) {
                if (text[index] in 'a'..'z') return RoleCode.TYPE
            }
            return RoleCode.CONSTANT
        }
        if (first == '_' && to - from > 4 && text[from + 1] == '_' && text[to - 1] == '_' && text[to - 2] == '_') return RoleCode.VARIABLE
        return 0
    }

    private val words = arrayOfNulls<Array<String>>(128)
    private val kinds = arrayOfNulls<IntArray>(128)

    /** The kind of the name `text[from, to)`, or 0. */
    private fun lookup(
        text: CharArray,
        from: Int,
        to: Int,
    ): Int {
        val first = text[from].code
        if (first >= 128) return 0
        val bucket = words[first] ?: return 0
        for (slot in bucket.indices) {
            if (LexerScan.equals(text, from, to, bucket[slot])) return kinds[first]!![slot]
        }
        return 0
    }

    private fun register(
        kind: Int,
        vararg names: String,
    ) {
        for (name in names) {
            val first = name[0].code
            words[first] = (words[first] ?: emptyArray()) + name
            kinds[first] = (kinds[first] ?: IntArray(0)) + kind
        }
    }

    init {
        register(
            HARD or RoleCode.KEYWORD,
            "as", "assert", "async", "await", "break", "continue", "del", "elif", "else", "except", "finally", "for",
            "from", "global", "if", "import", "lambda", "nonlocal", "pass", "raise", "return", "try", "while", "with", "yield",
        )
        register(HARD or DEF or RoleCode.KEYWORD, "def")
        register(HARD or CLASS or RoleCode.KEYWORD, "class")
        register(HARD or RoleCode.OPERATOR, "and", "in", "is", "not", "or")
        register(HARD or RoleCode.CONSTANT, "True", "False", "None")
        register(RoleCode.CONSTANT, "NotImplemented", "Ellipsis", "__debug__")
        register(SOFT_MATCH, "match")
        register(SOFT_CASE, "case")
        register(SOFT_TYPE or RoleCode.TYPE, "type")
        register(RoleCode.VARIABLE, "self", "cls")
        register(
            RoleCode.TYPE,
            "bool", "bytearray", "bytes", "classmethod", "complex", "dict", "float", "frozenset", "int", "list",
            "memoryview", "object", "property", "range", "set", "slice", "staticmethod", "str", "tuple",
        )
        register(
            RoleCode.FUNCTION,
            "abs", "aiter", "all", "anext", "any", "ascii", "bin", "breakpoint", "callable", "chr", "compile", "delattr",
            "dir", "divmod", "enumerate", "eval", "exec", "filter", "format", "getattr", "globals", "hasattr", "hash",
            "help", "hex", "id", "input", "isinstance", "issubclass", "iter", "len", "locals", "map", "max", "min", "next",
            "oct", "open", "ord", "pow", "print", "repr", "reversed", "round", "setattr", "sorted", "sum", "super", "vars",
            "zip", "__import__",
        )
    }
}
