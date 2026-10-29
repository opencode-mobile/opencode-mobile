package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * One kind of literal a [LiteralStackLexer] keeps open across lines: a string, a sigil, a block
 * comment. [close] is 1 to 3 characters; a non-empty [open] nests (paired sigil delimiters,
 * `#=` comments), counted up to 15 deep.
 */
internal class LiteralKind(
    val close: String,
    val role: Int = RoleCode.STRING,
    val open: String = "",
    /** [LiteralStackLexer.NO_INTERPOLATION], [LiteralStackLexer.HASH_BRACE] (`#{}`), or [LiteralStackLexer.DOLLAR] (`$name`, `$()`). */
    val interpolation: Int = LiteralStackLexer.NO_INTERPOLATION,
    /** [LiteralStackLexer.ESCAPE_ALL], [LiteralStackLexer.ESCAPE_CLOSER] (only `\\` and a backslash before the closer), or [LiteralStackLexer.ESCAPE_NONE]. */
    val escapes: Int = LiteralStackLexer.ESCAPE_ALL,
    /** ASCII letters right after the closer belong to the literal (regex flags, sigil modifiers). */
    val suffix: Boolean = false,
    /** The literal cannot span lines: an unterminated one ends with its line. */
    val singleLine: Boolean = false,
    /** A doubled closer inside is one literal character (Nim raw strings: `""`). */
    val doubledClose: Boolean = false,
    /** Extra closer characters right after the closer belong to it (Nim `""""`). */
    val greedyClose: Boolean = false,
    /** `[...]` classes hide the closer (regex literals). */
    val charClass: Boolean = false,
    /** Erlang `~p`, `~5.2f` format directives are Constant. */
    val format: Boolean = false,
    /** `#` starts a comment to the line end (CoffeeScript heregex). */
    val comments: Boolean = false,
) {
    init {
        require(close.isNotEmpty()) { "A literal needs a closer." }
    }

    val first: Char = close[0]

    /** True when the literal nests: [open] is non-empty. */
    val nests: Boolean = open.isNotEmpty()
}

/**
 * Shared machinery of the Julia, Crystal, Nim, CoffeeScript, Elixir, and Erlang lexers: a stack
 * of open literals and interpolations packed into the line state, the scanner for a literal's
 * contents, and character helpers.
 *
 * State layout: bits 0-1 hold the stack depth (0 to [MAX_FRAMES]); frames of 9 bits each from
 * bit 2, outermost first, unused frames zero. A frame's low 5 bits are [BRACE_INTERPOLATION],
 * [PAREN_INTERPOLATION], or [FIRST_KIND] plus an index into the language's [kinds]; its high
 * 4 bits count the brackets open inside an interpolation, or the nested openers inside a literal
 * (both saturate at 15). Bits 29-30 belong to the language (Crystal heredocs); bit 31 stays clear
 * so states are non-negative, as embedding hosts require.
 *
 * Past [MAX_FRAMES], a literal opened in code colors to its close on the same line without
 * interpolation parts, and an interpolation inside a literal colors as part of it.
 */
internal abstract class LiteralStackLexer(
    private val kinds: Array<LiteralKind>,
) : HighlightLexer() {
    protected fun depthOf(state: Int): Int = state and DEPTH_MASK

    /** The low 5 bits of the frame on top, or 0 for an empty stack. */
    protected fun topCode(state: Int): Int {
        val depth = state and DEPTH_MASK
        return if (depth == 0) 0 else frameAt(state, depth - 1) and CODE_MASK
    }

    protected fun inLiteral(state: Int): Boolean = topCode(state) >= FIRST_KIND

    /** The state with the language's bits (29-30) cleared: just the stack. */
    protected fun stackOf(state: Int): Int = state and STACK_MASK

    /**
     * Opens the literal of kind [code] whose opener is `text[at, contentStart)`. Pushes it, or,
     * with the stack full, colors it to its close on this line. Returns the new state above the
     * offset to continue from.
     */
    protected fun openLiteral(
        text: CharArray,
        at: Int,
        contentStart: Int,
        end: Int,
        code: Int,
        state: Int,
        out: SpanSink,
    ): Long {
        val kind = kinds[code - FIRST_KIND]
        out.span(at, contentStart, kind.role)
        if (state and DEPTH_MASK < MAX_FRAMES) return packed(push(state, code), contentStart)
        val result = scan(text, contentStart, end, code, false, out)
        val offset = result.toInt()
        return packed(state, if (outcomeOf(result) == CLOSED) offset else end)
    }

    /**
     * Lexes inside the literal on top of the stack from [from]; returns the new state above the
     * offset where it stopped: after its closer (popped), after an interpolation's opener
     * (pushed), or at the line end.
     */
    protected fun literal(
        text: CharArray,
        from: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Long {
        val depth = state and DEPTH_MASK
        val frame = frameAt(state, depth - 1)
        val result = scan(text, from, end, frame, depth < MAX_FRAMES, out)
        val offset = result.toInt()
        val newFrame = ((result ushr 32).toInt()) and FRAME_MASK
        return when (outcomeOf(result)) {
            CLOSED -> packed(pop(state), offset)
            BRACE_OPENED -> packed(push(setTop(state, newFrame), BRACE_INTERPOLATION), offset)
            PAREN_OPENED -> packed(push(setTop(state, newFrame), PAREN_INTERPOLATION), offset)
            else -> packed(setTop(state, newFrame), offset)
        }
    }

    /**
     * Counts an opening bracket in code against the interpolation on top; returns the new state.
     * Call for `{` and `(`.
     */
    protected fun openBracket(
        state: Int,
        char: Char,
    ): Int {
        val code = topCode(state)
        if (!matchesInterpolation(code, char)) return state
        val frame = frameAt(state, (state and DEPTH_MASK) - 1)
        return if (frame ushr COUNT_SHIFT < MAX_COUNT) setTop(state, frame + (1 shl COUNT_SHIFT)) else state
    }

    /**
     * A closing bracket in code: closes the interpolation on top when none of its brackets are
     * open (the depth drops), or counts it. Call for `}` and `)`.
     */
    protected fun closeBracket(
        state: Int,
        char: Char,
    ): Int {
        val code = topCode(state)
        val opening = if (char == '}') '{' else '('
        if (!matchesInterpolation(code, opening)) return state
        val frame = frameAt(state, (state and DEPTH_MASK) - 1)
        return if (frame ushr COUNT_SHIFT == 0) pop(state) else setTop(state, frame - (1 shl COUNT_SHIFT))
    }

    /** True when [char] closes the interpolation on top of [state] right here (none of its brackets open). */
    protected fun closesInterpolation(
        state: Int,
        char: Char,
    ): Boolean {
        val code = topCode(state)
        if (code != BRACE_INTERPOLATION && code != PAREN_INTERPOLATION) return false
        if ((code == BRACE_INTERPOLATION) != (char == '}')) return false
        return frameAt(state, (state and DEPTH_MASK) - 1) ushr COUNT_SHIFT == 0
    }

    /** The state to end a line with: single-line literals (and anything above them) end here. */
    protected fun finishLine(state: Int): Int {
        val depth = state and DEPTH_MASK
        for (level in 0 until depth) {
            val code = frameAt(state, level) and CODE_MASK
            if (code >= FIRST_KIND && kinds[code - FIRST_KIND].singleLine) return truncate(state, level)
        }
        return state
    }

    /** The end of a `$name` interpolation's name at [from] (Julia); [from] when there is none. */
    protected open fun interpolatedNameEnd(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int = nameEnd(text, from, end)

    /**
     * Scans literal contents with [frame] from [from]. Returns the outcome and the updated frame
     * above the offset reached.
     */
    private fun scan(
        text: CharArray,
        from: Int,
        end: Int,
        frame: Int,
        canPush: Boolean,
        out: SpanSink,
    ): Long {
        val code = frame and CODE_MASK
        val kind = kinds[code - FIRST_KIND]
        val role = kind.role
        val close = kind.close
        val first = kind.first
        var nest = frame ushr COUNT_SHIFT
        var segment = from
        var index = from
        var inClass = false
        while (index < end) {
            val char = text[index]
            if (char == '\\' && kind.escapes != ESCAPE_NONE) {
                val length = if (index + 1 < end) 2 else 1
                val colored = kind.escapes == ESCAPE_ALL || (length == 2 && (text[index + 1] == '\\' || text[index + 1] == first))
                if (colored) {
                    out.span(segment, index, role)
                    out.span(index, index + length, RoleCode.CONSTANT)
                    segment = index + length
                }
                index += length
                continue
            }
            if (inClass) {
                if (char == ']') inClass = false
                index += 1
                continue
            }
            if (kind.charClass && char == '[') {
                inClass = true
                index += 1
                continue
            }
            if (kind.comments && char == '#' && (index + 1 >= end || text[index + 1] != '{') && (index == from || text[index - 1] == ' ' || text[index - 1] == '\t')) {
                out.span(segment, index, role)
                out.span(index, end, RoleCode.COMMENT)
                return packedOutcome(AT_END, code or (nest shl COUNT_SHIFT), end)
            }
            // Every branch below advances `index` by at least one: [open] and [close] are non-empty.
            if (kind.nests && char == kind.open[0] && LexerScan.startsWith(text, index, end, kind.open)) {
                if (nest < MAX_COUNT) nest += 1
                index += kind.open.length
                continue
            }
            if (char == first && LexerScan.startsWith(text, index, end, close)) {
                if (kind.doubledClose && index + 1 < end && text[index + 1] == first) {
                    index += 2
                    continue
                }
                if (nest > 0) {
                    nest -= 1
                    index += close.length
                    continue
                }
                var after = index + close.length
                if (kind.greedyClose) while (after < end && text[after] == first) after += 1
                if (kind.suffix) while (after < end && isAsciiLetter(text[after])) after += 1
                out.span(segment, after, role)
                return packedOutcome(CLOSED, 0, after)
            }
            val next = if (index + 1 < end) text[index + 1] else '\u0000'
            when (kind.interpolation) {
                HASH_BRACE ->
                    if (char == '#' && next == '{') {
                        if (canPush) {
                            out.span(segment, index, role)
                            out.span(index, index + 2, RoleCode.PUNCTUATION)
                            return packedOutcome(BRACE_OPENED, code or (nest shl COUNT_SHIFT), index + 2)
                        }
                        val after = matchingClose(text, index + 2, end, '{', '}')
                        if (after < 0) {
                            out.span(segment, end, role)
                            return packedOutcome(AT_END, code or (nest shl COUNT_SHIFT), end)
                        }
                        index = after
                        continue
                    }
                DOLLAR ->
                    if (char == '$') {
                        if (next == '(') {
                            if (canPush) {
                                out.span(segment, index, role)
                                out.span(index, index + 2, RoleCode.PUNCTUATION)
                                return packedOutcome(PAREN_OPENED, code or (nest shl COUNT_SHIFT), index + 2)
                            }
                            val after = matchingClose(text, index + 2, end, '(', ')')
                            if (after < 0) {
                                out.span(segment, end, role)
                                return packedOutcome(AT_END, code or (nest shl COUNT_SHIFT), end)
                            }
                            index = after
                            continue
                        }
                        val nameEnd = interpolatedNameEnd(text, index + 1, end)
                        if (nameEnd > index + 1) {
                            out.span(segment, index, role)
                            out.span(index, nameEnd, RoleCode.VARIABLE)
                            segment = nameEnd
                            index = nameEnd
                            continue
                        }
                    }
            }
            if (kind.format && char == '~') {
                val after = formatDirectiveEnd(text, index, end)
                if (after > index) {
                    out.span(segment, index, role)
                    out.span(index, after, RoleCode.CONSTANT)
                    segment = after
                    index = after
                    continue
                }
            }
            index += 1
        }
        out.span(segment, end, role)
        return packedOutcome(AT_END, code or (nest shl COUNT_SHIFT), end)
    }

    /** Erlang `~p`, `~n`, `~-10.3s`, `~*c`: the offset after the directive, or [at] when none. */
    private fun formatDirectiveEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at + 1
        val limit = minOf(end, at + 12)
        while (index < limit && (isDigit(text[index]) || text[index] == '.' || text[index] == '*' || text[index] == '-' || text[index] == '+' || text[index] == '#')) index += 1
        if (index < limit && (isAsciiLetter(text[index]) || text[index] == '~')) return index + 1
        return at
    }

    /** The offset after the [close] matching an [open] already passed, counting nested pairs on this line; -1 when none. */
    protected fun matchingClose(
        text: CharArray,
        from: Int,
        end: Int,
        open: Char,
        close: Char,
    ): Int {
        var depth = 0
        var index = from
        while (index < end) {
            val char = text[index]
            if (char == open) {
                depth += 1
            } else if (char == close) {
                if (depth == 0) return index + 1
                depth -= 1
            }
            index += 1
        }
        return -1
    }

    private fun matchesInterpolation(
        code: Int,
        opening: Char,
    ): Boolean = (code == BRACE_INTERPOLATION && opening == '{') || (code == PAREN_INTERPOLATION && opening == '(')

    private fun frameAt(
        state: Int,
        level: Int,
    ): Int = (state ushr (FRAME_SHIFT + level * FRAME_BITS)) and FRAME_MASK

    private fun push(
        state: Int,
        frame: Int,
    ): Int {
        val depth = state and DEPTH_MASK
        if (depth >= MAX_FRAMES) return state
        return (state and DEPTH_MASK.inv()) or (frame shl (FRAME_SHIFT + depth * FRAME_BITS)) or (depth + 1)
    }

    private fun pop(state: Int): Int = truncate(state, (state and DEPTH_MASK) - 1)

    /** Keeps the bottom [depth] frames. */
    private fun truncate(
        state: Int,
        depth: Int,
    ): Int {
        val keep = (1 shl (FRAME_SHIFT + depth * FRAME_BITS)) - 1
        val frames = state and STACK_MASK and keep and DEPTH_MASK.inv()
        return (state and STACK_MASK.inv()) or frames or depth
    }

    private fun setTop(
        state: Int,
        frame: Int,
    ): Int {
        val shift = FRAME_SHIFT + ((state and DEPTH_MASK) - 1) * FRAME_BITS
        return (state and (FRAME_MASK shl shift).inv()) or (frame shl shift)
    }

    private fun outcomeOf(result: Long): Int = (result ushr 48).toInt()

    private fun packedOutcome(
        outcome: Int,
        frame: Int,
        offset: Int,
    ): Long = (outcome.toLong() shl 48) or (frame.toLong() shl 32) or (offset.toLong() and 0xFFFFFFFFL)

    companion object {
        const val NO_INTERPOLATION = 0
        const val HASH_BRACE = 1
        const val DOLLAR = 2

        const val ESCAPE_ALL = 0
        const val ESCAPE_CLOSER = 1
        const val ESCAPE_NONE = 2

        const val BRACE_INTERPOLATION = 1
        const val PAREN_INTERPOLATION = 2

        /** Frame code of `kinds[0]`. */
        const val FIRST_KIND = 3

        const val MAX_FRAMES = 3
        private const val DEPTH_MASK = 3
        private const val FRAME_SHIFT = 2
        private const val FRAME_BITS = 9
        private const val FRAME_MASK = 0x1FF
        private const val CODE_MASK = 31
        private const val COUNT_SHIFT = 5
        private const val MAX_COUNT = 15

        /** Bits 0-28: the depth and the frames. */
        const val STACK_MASK = (1 shl 29) - 1

        private const val AT_END = 0
        private const val CLOSED = 1
        private const val BRACE_OPENED = 2
        private const val PAREN_OPENED = 3

        fun packed(
            high: Int,
            offset: Int,
        ): Long = (high.toLong() shl 32) or (offset.toLong() and 0xFFFFFFFFL)

        fun stateOf(result: Long): Int = (result ushr 32).toInt()

        fun offsetOf(result: Long): Int = result.toInt()

        fun isNameStart(char: Char): Boolean = isAsciiLetter(char) || char == '_'

        fun isNamePart(char: Char): Boolean = isAsciiLetter(char) || isDigit(char) || char == '_'

        /**
         * The length of the identifier character at [at]: 1 for an ASCII name part or a
         * non-ASCII letter, digit, mark, or modifier; 2 for a surrogate pair (emoji and other
         * astral identifiers); 0 otherwise (lone surrogates and [end] included).
         */
        fun namePartLength(
            text: CharArray,
            at: Int,
            end: Int,
        ): Int {
            if (at >= end) return 0
            val char = text[at]
            if (char.code < 0x80) return if (isNamePart(char)) 1 else 0
            if (char.isHighSurrogate()) return if (at + 1 < end && text[at + 1].isLowSurrogate()) 2 else 0
            if (char.isLowSurrogate()) return 0
            return when (char.category) {
                CharCategory.UPPERCASE_LETTER, CharCategory.LOWERCASE_LETTER, CharCategory.TITLECASE_LETTER,
                CharCategory.MODIFIER_LETTER, CharCategory.OTHER_LETTER, CharCategory.DECIMAL_DIGIT_NUMBER,
                CharCategory.LETTER_NUMBER, CharCategory.OTHER_NUMBER, CharCategory.NON_SPACING_MARK,
                CharCategory.COMBINING_SPACING_MARK, CharCategory.CONNECTOR_PUNCTUATION,
                -> 1
                else -> 0
            }
        }

        /** Like [namePartLength], but digits and marks cannot start a name. */
        fun nameStartLength(
            text: CharArray,
            at: Int,
            end: Int,
        ): Int {
            if (at >= end) return 0
            val char = text[at]
            if (char.code < 0x80) return if (isNameStart(char)) 1 else 0
            if (char.isHighSurrogate()) return if (at + 1 < end && text[at + 1].isLowSurrogate()) 2 else 0
            return when (char.category) {
                CharCategory.UPPERCASE_LETTER, CharCategory.LOWERCASE_LETTER, CharCategory.TITLECASE_LETTER,
                CharCategory.MODIFIER_LETTER, CharCategory.OTHER_LETTER, CharCategory.LETTER_NUMBER,
                -> 1
                else -> 0
            }
        }

        /** The end of the identifier (Unicode included) starting at [from]; [from] when none starts there. */
        fun nameEnd(
            text: CharArray,
            from: Int,
            end: Int,
        ): Int {
            if (from >= end) return from
            val first = nameStartLength(text, from, end)
            if (first == 0) return from
            var index = from + first
            while (index < end) {
                val length = namePartLength(text, index, end)
                if (length == 0) break
                index += length
            }
            return index
        }

        /**
         * A number at [at]: `0x`/`0o`/`0b` integers, decimals with `_`, a fraction when a digit
         * follows the `.`, and an exponent. Returns the offset after it; suffixes are the caller's.
         */
        fun numberEnd(
            text: CharArray,
            at: Int,
            end: Int,
        ): Int {
            var index = at
            if (text[index] == '0' && index + 1 < end) {
                val radix = text[index + 1]
                if (radix == 'x' || radix == 'X' || radix == 'o' || radix == 'O' || radix == 'b' || radix == 'B') {
                    index += 2
                    while (index < end && (isHexDigit(text[index]) || text[index] == '_')) index += 1
                    return index
                }
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
            return index
        }

        /** True for a capitalized name of 2+ characters with no lowercase letter: `MAX_SIZE`, `HTTP`. */
        fun isAllCaps(
            text: CharArray,
            from: Int,
            to: Int,
        ): Boolean {
            if (to - from < 2 || text[from] !in 'A'..'Z') return false
            for (index in from until to) if (text[index] in 'a'..'z') return false
            return true
        }

        /** True when the next non-blank character at or after [from] is [char]. */
        fun nextNonBlankIs(
            text: CharArray,
            from: Int,
            end: Int,
            char: Char,
        ): Boolean {
            val at = LexerScan.skipBlanks(text, from, end)
            return at < end && text[at] == char
        }
    }
}
