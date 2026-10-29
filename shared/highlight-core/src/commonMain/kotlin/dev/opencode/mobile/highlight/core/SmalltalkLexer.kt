package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.SymbolicScan.isNameStart

/**
 * Smalltalk (Pharo, Squeak, GNU Smalltalk syntax).
 *
 * Roles: `"..."` comments are Comment and `'...'` strings String with `''` as Constant; both run
 * across lines. Character literals (`$a`) are String. Symbols (`#name`, `#at:put:`, `#+`,
 * `#'quoted'`), `nil`, `true`, and `false` are Constant, as is every bare name inside a literal
 * array `#( ... )`, which also runs across lines. Keyword message parts (`at:`, `put:`) are
 * Function; block parameters (`:x`) are Variable; `self`, `super`, `thisContext`, and the return
 * caret `^` are Keyword; capitalized names (classes, globals) are Type. Numbers (`42`, `3.14`,
 * `1e10`, radix `16r1F`, scaled `1.5s2`) are Number. Binary selectors (`+`, `->`, `,`, `>>`)
 * and assignment (`:=`) are Operator; brackets, `.`, `;`, `|`, `#(`, `#[`, and `#{` Punctuation.
 *
 * States: the low 2 bits hold the open construct ([COMMENT] or [STRING]); bits 2-9 hold how
 * many literal arrays are open (up to 255), kept through comments and strings inside them.
 *
 * Known gaps: `<primitive: 60>` pragmas and the `!` chunk format get no roles of their own;
 * temporaries between `|` bars read as plain names.
 */
internal object SmalltalkLexer : HighlightLexer() {
    private const val MODE_MASK = 3
    private const val COMMENT = 1
    private const val STRING = 2
    private const val ARRAY_SHIFT = 2
    private const val MAX_ARRAYS = 255

    // Word kinds.
    private const val KEYWORD = 1
    private const val CONSTANT = 2

    private val words = LexerWords()

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        var index = start
        var arrays = state ushr ARRAY_SHIFT
        when (state and MODE_MASK) {
            COMMENT -> {
                val close = commentEnd(text, start, end)
                out.span(start, if (close < 0) end else close, RoleCode.COMMENT)
                if (close < 0) return state
                index = close
            }
            STRING -> {
                val close = SymbolicScan.doubledEnd(text, start, end, '\'', false, RoleCode.STRING, out)
                if (close < 0) return state
                index = close
            }
        }
        while (index < end && !out.stopped) {
            val char = text[index]
            val next = if (index + 1 < end) text[index + 1] else ' '
            when {
                char == ' ' || char == '\t' -> index += 1
                char == '"' -> {
                    val close = commentEnd(text, index + 1, end)
                    out.span(index, if (close < 0) end else close, RoleCode.COMMENT)
                    if (close < 0) return COMMENT or (arrays shl ARRAY_SHIFT)
                    index = close
                }
                char == '\'' -> {
                    out.span(index, index + 1, RoleCode.STRING)
                    val close = SymbolicScan.doubledEnd(text, index + 1, end, '\'', false, RoleCode.STRING, out)
                    if (close < 0) return STRING or (arrays shl ARRAY_SHIFT)
                    index = close
                }
                char == '$' -> {
                    val after = if (index + 1 < end) index + 1 + SymbolicScan.charLength(text, index + 1, end) else end
                    out.span(index, after, RoleCode.STRING)
                    index = after
                }
                char == '#' -> {
                    when {
                        next == '(' || next == '[' || next == '{' -> {
                            out.span(index, index + 2, RoleCode.PUNCTUATION)
                            index += 2
                            if (next == '(' && arrays < MAX_ARRAYS) arrays += 1
                        }
                        next == '\'' -> {
                            // A quoted symbol stays on its line.
                            out.span(index, index + 2, RoleCode.CONSTANT)
                            val close = SymbolicScan.doubledEnd(text, index + 2, end, '\'', false, RoleCode.CONSTANT, out)
                            index = if (close < 0) end else close
                        }
                        else -> {
                            var after = index + 1
                            while (after < end && text[after] == '#') after += 1
                            after = if (after < end && isNameStart(text[after])) selectorEnd(text, after, end) else binaryEnd(text, after, end)
                            out.span(index, after, if (after > index + 1) RoleCode.CONSTANT else RoleCode.PUNCTUATION)
                            index = after
                        }
                    }
                }
                isDigit(char) -> {
                    val after = numberEnd(text, index, end)
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                }
                isNameStart(char) -> {
                    var after = nameEnd(text, index, end)
                    val keywordPart = after < end && text[after] == ':' && !(after + 1 < end && text[after + 1] == '=')
                    if (arrays > 0) {
                        // In a literal array a name is a symbol, and `at:put:` one keyword selector.
                        if (keywordPart) after = selectorEnd(text, index, end)
                        out.span(index, after, RoleCode.CONSTANT)
                    } else if (keywordPart) {
                        out.span(index, after + 1, RoleCode.FUNCTION)
                        after += 1
                    } else if (out.collecting) {
                        val role =
                            when (words.lookup(text, index, after)) {
                                KEYWORD -> RoleCode.KEYWORD
                                CONSTANT -> RoleCode.CONSTANT
                                else -> if (SymbolicScan.isUpper(char)) RoleCode.TYPE else 0
                            }
                        if (role != 0) out.span(index, after, role)
                    }
                    index = after
                }
                char == ':' -> {
                    when {
                        next == '=' -> {
                            out.span(index, index + 2, RoleCode.OPERATOR)
                            index += 2
                        }
                        isNameStart(next) -> {
                            val after = nameEnd(text, index + 1, end)
                            out.span(index, after, RoleCode.VARIABLE)
                            index = after
                        }
                        else -> {
                            out.span(index, index + 1, RoleCode.PUNCTUATION)
                            index += 1
                        }
                    }
                }
                char == '^' -> {
                    out.span(index, index + 1, RoleCode.KEYWORD)
                    index += 1
                }
                char == '(' || char == ')' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    if (arrays > 0) {
                        if (char == '(') {
                            if (arrays < MAX_ARRAYS) arrays += 1
                        } else {
                            arrays -= 1
                        }
                    }
                }
                char == '[' || char == ']' || char == '{' || char == '}' || char == '.' || char == ';' || char == '|' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                }
                char == '-' && isDigit(next) && arrays > 0 -> {
                    val after = numberEnd(text, index + 1, end)
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                }
                isBinary(char) -> {
                    val after = binaryEnd(text, index, end)
                    out.span(index, after, RoleCode.OPERATOR)
                    index = after
                }
                else -> index += 1
            }
        }
        return arrays shl ARRAY_SHIFT
    }

    /** The offset after the `"` closing a comment whose text starts at [from], or -1. */
    private fun commentEnd(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        var index = from
        while (index < end) {
            if (text[index] == '"') return index + 1
            index += 1
        }
        return -1
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

    /** A unary or keyword selector from [from]: `name`, or `at:put:` with its colons. */
    private fun selectorEnd(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        var index = nameEnd(text, from, end)
        while (index < end && text[index] == ':' && !(index + 1 < end && text[index + 1] == '=')) {
            index += 1
            if (index < end && isNameStart(text[index])) index = nameEnd(text, index, end) else break
        }
        return index
    }

    private fun binaryEnd(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        var index = from
        while (index < end && isBinary(text[index])) index += 1
        return index
    }

    /** Integers, decimals, exponents, radix (`16r1F`), and scaled (`1.5s2`) numbers. */
    private fun numberEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at
        while (index < end && isDigit(text[index])) index += 1
        if (index + 1 < end && text[index] == 'r' && (isDigit(text[index + 1]) || text[index + 1] in 'A'..'Z')) {
            index += 1
            while (index < end && (isDigit(text[index]) || text[index] in 'A'..'Z')) index += 1
            return index
        }
        if (index + 1 < end && text[index] == '.' && isDigit(text[index + 1])) {
            index += 1
            while (index < end && isDigit(text[index])) index += 1
        }
        if (index + 1 < end && (text[index] == 'e' || text[index] == 'd' || text[index] == 'q')) {
            var exponent = index + 1
            if (exponent < end && text[exponent] == '-') exponent += 1
            if (exponent < end && isDigit(text[exponent])) {
                index = exponent
                while (index < end && isDigit(text[index])) index += 1
            }
        }
        if (index < end && text[index] == 's') {
            index += 1
            while (index < end && isDigit(text[index])) index += 1
        }
        return index
    }

    private fun isBinary(char: Char): Boolean =
        when (char) {
            '+', '-', '*', '/', '\\', '<', '>', '=', '~', '@', '%', '&', '?', '!', ',' -> true
            else -> false
        }

    init {
        words.register(KEYWORD, "self", "super", "thisContext")
        words.register(CONSTANT, "nil", "true", "false")
    }
}
