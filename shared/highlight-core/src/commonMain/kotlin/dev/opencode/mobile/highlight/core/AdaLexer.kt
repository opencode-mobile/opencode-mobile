package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.ClassicScan.isLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * Ada (83 through 2022).
 *
 * Roles: reserved words are Keyword in any case; predefined types (`Integer`, `String`, ...)
 * and the name after `type` or `subtype` are Type; `True` and `False` are Constant; the name
 * after `procedure`, `function`, `package` (and `package body`), `task`, `protected`, `entry`,
 * or `end`, and any name before `(`, is Function. A tick after a name or `)` is Punctuation and
 * the attribute after it (`X'First`, `T'Image`) is Attribute; elsewhere `'x'` is a character
 * literal (String). Strings `"..."` are String with a doubled `""` as Constant. Numbers
 * (`1_000`, `3.14E-2`, based `16#FF#` and `2#1.1#E4`) are Number; `--` comments are Comment;
 * operators (`:=`, `=>`, `..`, `**`, `/=`, `<>`, `<<`, `>>`, `&`, `|`, ...) are Operator;
 * `( ) , ; . :` are Punctuation.
 *
 * States: always 0. Nothing in Ada continues past its line, so the state-only pass returns at once.
 */
internal object AdaLexer : HighlightLexer() {
    // Word kinds.
    private const val KEYWORD = 1
    private const val TYPE = 2
    private const val CONSTANT = 3
    private const val KIND_MASK = 3

    /** The name after this keyword is Function. */
    private const val NAMES_FUNCTION = 4

    /** The name after this keyword is Type. */
    private const val NAMES_TYPE = 8

    /** Keeps what the keyword before expects (`package body X`). */
    private const val KEEPS_NAME = 16

    private val words = LexerWords(caseInsensitive = true)

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        if (!out.collecting) return 0
        var index = start
        // A name, `)`, or `]` before: a tick after it is an attribute, not a character literal.
        var valueBefore = false
        // The role the next plain name takes after a declaring keyword, or 0.
        var nameRole = 0
        while (index < end && !out.stopped) {
            val char = text[index]
            val next = if (index + 1 < end) text[index + 1] else ' '
            val hadValue = valueBefore
            if (char != ' ' && char != '\t') valueBefore = false
            when {
                char == ' ' || char == '\t' -> index += 1
                char == '-' && next == '-' -> {
                    out.span(index, end, RoleCode.COMMENT)
                    index = end
                }
                char == '"' -> {
                    val close = ClassicScan.doubledQuoteString(text, index, index + 1, end, '"', out)
                    index = if (close < 0) end else close
                    valueBefore = true
                    nameRole = 0
                }
                char == '\'' -> index = tick(text, index, end, hadValue, out).also { valueBefore = it > index + 1 && hadValue }
                isDigit(char) -> {
                    val after = numberEnd(text, index, end)
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                    valueBefore = true
                    nameRole = 0
                }
                isLetter(char) -> {
                    val after = ClassicScan.wordEnd(text, index + 1, end)
                    val kind = words.lookup(text, index, after)
                    when {
                        kind != 0 && kind and KIND_MASK == KEYWORD -> {
                            out.span(index, after, RoleCode.KEYWORD)
                            nameRole =
                                when {
                                    kind and NAMES_FUNCTION != 0 -> RoleCode.FUNCTION
                                    kind and NAMES_TYPE != 0 -> RoleCode.TYPE
                                    kind and KEEPS_NAME != 0 -> nameRole
                                    else -> 0
                                }
                            // `X.all'Access`: the tick after `all` is an attribute.
                            valueBefore = ClassicScan.equalsIgnoringCase(text, index, after, "all")
                        }
                        else -> {
                            val role =
                                when {
                                    nameRole != 0 -> nameRole
                                    kind == TYPE -> RoleCode.TYPE
                                    kind == CONSTANT -> RoleCode.CONSTANT
                                    ClassicScan.nextIs(text, after, end, '(') -> RoleCode.FUNCTION
                                    else -> 0
                                }
                            if (role != 0) out.span(index, after, role)
                            // `Ada.Text_IO`: a dotted name keeps the role of its first part.
                            if (!(after < end && text[after] == '.' && !(after + 1 < end && text[after + 1] == '.'))) nameRole = 0
                            valueBefore = true
                        }
                    }
                    index = after
                }
                char == ')' || char == ']' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    valueBefore = true
                    nameRole = 0
                }
                char == '.' && next == '.' -> {
                    out.span(index, index + 2, RoleCode.OPERATOR)
                    index += 2
                    nameRole = 0
                }
                char == '(' || char == '[' || char == ',' || char == ';' || char == '.' || (char == ':' && next != '=') -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    if (char != '.') nameRole = 0
                }
                isOperator(char) -> {
                    val two = isOperator(next) && isTwoCharOperator(char, next)
                    val after = if (two) index + 2 else index + 1
                    out.span(index, after, RoleCode.OPERATOR)
                    index = after
                    nameRole = 0
                }
                else -> {
                    index += 1
                    nameRole = 0
                }
            }
        }
        return 0
    }

    /**
     * A `'` at [at]: after a value, Punctuation and the attribute name after it as Attribute;
     * otherwise a character literal `'x'` (String) when one fits, else Punctuation.
     */
    private fun tick(
        text: CharArray,
        at: Int,
        end: Int,
        afterValue: Boolean,
        out: SpanSink,
    ): Int {
        if (afterValue) {
            out.span(at, at + 1, RoleCode.PUNCTUATION)
            if (at + 1 < end && isLetter(text[at + 1])) {
                val after = ClassicScan.wordEnd(text, at + 2, end)
                out.span(at + 1, after, RoleCode.ATTRIBUTE)
                return after
            }
            return at + 1
        }
        // One UTF-16 unit, or a surrogate pair, between two ticks.
        val width = if (at + 2 < end && text[at + 1].isHighSurrogate() && text[at + 2].isLowSurrogate()) 2 else 1
        if (at + 1 + width < end && text[at + 1 + width] == '\'') {
            out.span(at, at + 2 + width, RoleCode.STRING)
            return at + 2 + width
        }
        out.span(at, at + 1, RoleCode.PUNCTUATION)
        return at + 1
    }

    /** A decimal or based (`16#FF#`) literal with an optional exponent. */
    private fun numberEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = ClassicScan.digitsEnd(text, at, end)
        if (index < end && text[index] == '#') {
            var close = index + 1
            while (close < end && (isHexDigit(text[close]) || text[close] == '_' || text[close] == '.')) close += 1
            if (close < end && text[close] == '#') {
                return ClassicScan.exponentEnd(text, close + 1, end, "e")
            }
            return index
        }
        if (index + 1 < end && text[index] == '.' && isDigit(text[index + 1])) {
            index = ClassicScan.digitsEnd(text, index + 1, end)
        }
        return ClassicScan.exponentEnd(text, index, end, "e")
    }

    private fun isOperator(char: Char): Boolean =
        when (char) {
            '=', '<', '>', '/', '*', '+', '-', '&', '|', ':' -> true
            else -> false
        }

    private fun isTwoCharOperator(
        first: Char,
        second: Char,
    ): Boolean =
        when (first) {
            ':', '/' -> second == '='
            '=' -> second == '>'
            '<' -> second == '=' || second == '>' || second == '<'
            '>' -> second == '=' || second == '>'
            '*' -> second == '*'
            else -> false
        }

    init {
        words.register(
            KEYWORD,
            "abort", "abs", "abstract", "accept", "access", "aliased", "all", "and", "array", "at", "begin", "case",
            "constant", "declare", "delay", "delta", "digits", "do", "else", "elsif", "exception", "exit", "for",
            "generic", "goto", "if", "in", "interface", "is", "limited", "loop", "mod", "new", "not", "null", "of", "or",
            "others", "out", "overriding", "parallel", "pragma", "private", "raise", "range", "record", "rem", "renames",
            "requeue", "return", "reverse", "select", "separate", "some", "synchronized", "tagged", "terminate", "then",
            "until", "use", "when", "while", "with", "xor",
        )
        words.register(KEYWORD or NAMES_FUNCTION, "procedure", "function", "package", "task", "protected", "entry", "end")
        words.register(KEYWORD or NAMES_TYPE, "type", "subtype")
        words.register(KEYWORD or KEEPS_NAME, "body")
        words.register(
            TYPE,
            "integer", "natural", "positive", "float", "long_float", "long_long_float", "short_float", "long_integer",
            "long_long_integer", "short_integer", "short_short_integer", "boolean", "character", "wide_character",
            "wide_wide_character", "string", "wide_string", "wide_wide_string", "duration", "unbounded_string",
            "address", "exception_occurrence",
        )
        words.register(CONSTANT, "true", "false")
    }
}
