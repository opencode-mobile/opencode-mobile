package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isDigit

/**
 * CoffeeScript.
 *
 * Roles: `#` comments and `###` block comments are Comment. Strings (`"..."`, `'...'`, block
 * strings `"""` and `'''`, all across lines), regexes (`/.../g`), heregexes (`///...///`,
 * across lines, with their `#` comments), and embedded JavaScript in backticks are String with
 * escapes as Constant; in double-quoted strings and heregexes `#{` and `}` are Punctuation
 * around code. `@name` is Variable (`this.name`), as are `this` and function parameters; `->`
 * and `=>` are Keyword. A name assigned a function (`name = (a) ->`, `name: ->`) or called
 * (`name(`) is Function; an object key (`key:`) and a name after `.` or `::` are Property
 * (Function when called). Keywords are Keyword; `true`, `false`, `yes`, `no`, `on`, `off`,
 * `null`, `undefined`, `NaN`, `Infinity`, and ALL_CAPS names are Constant; other capitalized
 * names are Type. Numbers are
 * Number; operators are Operator; brackets, `,`, `;`, `:`, `.`, and `::` are Punctuation.
 *
 * A `/` reads as a regex where a value is expected (line start, after an operator, `(`, `,`, or
 * a keyword) and the line has another `/`; otherwise it divides.
 *
 * States: a [LiteralStackLexer] stack of strings, block comments, heregexes, and `#{`
 * interpolations; a one-line regex ends with its line.
 *
 * Known gaps: function detection looks at most [LOOKAHEAD] characters ahead and only for
 * parameter lists without nested parentheses; implicit calls (`f x`) keep `f` plain.
 */
internal object CoffeeScriptLexer : LiteralStackLexer(
    arrayOf(
        LiteralKind("\"", interpolation = HASH_BRACE),
        LiteralKind("'"),
        LiteralKind("\"\"\"", interpolation = HASH_BRACE),
        LiteralKind("'''"),
        LiteralKind("###", role = RoleCode.COMMENT, escapes = ESCAPE_NONE),
        LiteralKind("///", interpolation = HASH_BRACE, suffix = true, comments = true),
        LiteralKind("/", suffix = true, singleLine = true, charClass = true),
        LiteralKind("`", escapes = ESCAPE_CLOSER),
        LiteralKind("```", escapes = ESCAPE_CLOSER),
    ),
) {
    private const val STRING = FIRST_KIND
    private const val SINGLE = FIRST_KIND + 1
    private const val BLOCK = FIRST_KIND + 2
    private const val SINGLE_BLOCK = FIRST_KIND + 3
    private const val BLOCK_COMMENT = FIRST_KIND + 4
    private const val HEREGEX = FIRST_KIND + 5
    private const val REGEX = FIRST_KIND + 6
    private const val EMBEDDED = FIRST_KIND + 7
    private const val EMBEDDED_BLOCK = FIRST_KIND + 8

    /** How far ahead a name or `(` looks for `->` or `=>`. */
    private const val LOOKAHEAD = 120

    // Word kinds: a role code in the low byte plus flags.
    private const val VALUE = 1 shl 8

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
        var valueEnded = false
        var afterDot = false
        // Names before this offset are parameters of a function literal.
        var paramsEnd = -1
        while (index < end && !out.stopped) {
            if (inLiteral(st)) {
                val result = literal(text, index, end, st, out)
                val closed = depthOf(stateOf(result)) < depthOf(st)
                st = stateOf(result)
                index = offsetOf(result)
                valueEnded = closed
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
            val wasValue = valueEnded
            valueEnded = false
            val next = if (index + 1 < end) text[index + 1] else '\u0000'
            when {
                char == '#' -> {
                    if (LexerScan.startsWith(text, index, end, "###") && (index + 3 >= end || text[index + 3] != '#')) {
                        val result = openLiteral(text, index, index + 3, end, BLOCK_COMMENT, st, out)
                        st = stateOf(result)
                        index = offsetOf(result)
                    } else {
                        out.span(index, end, RoleCode.COMMENT)
                        index = end
                    }
                }
                char == '"' || char == '\'' || char == '`' -> {
                    val triple = next == char && index + 2 < end && text[index + 2] == char
                    val code =
                        when (char) {
                            '"' -> if (triple) BLOCK else STRING
                            '\'' -> if (triple) SINGLE_BLOCK else SINGLE
                            else -> if (triple) EMBEDDED_BLOCK else EMBEDDED
                        }
                    val result = openLiteral(text, index, index + if (triple) 3 else 1, end, code, st, out)
                    st = stateOf(result)
                    index = offsetOf(result)
                    valueEnded = !inLiteral(st)
                }
                char == '/' && LexerScan.startsWith(text, index, end, "///") -> {
                    val result = openLiteral(text, index, index + 3, end, HEREGEX, st, out)
                    st = stateOf(result)
                    index = offsetOf(result)
                    valueEnded = !inLiteral(st)
                }
                char == '/' && !wasValue && next != ' ' && next != '=' && hasLaterSlash(text, index + 1, end) -> {
                    val result = openLiteral(text, index, index + 1, end, REGEX, st, out)
                    st = stateOf(result)
                    index = offsetOf(result)
                    valueEnded = !inLiteral(st)
                }
                isDigit(char) || (char == '.' && isDigit(next) && !wasValue) -> {
                    var after = numberEnd(text, index, end)
                    if (after < end && text[after] == 'n') after += 1
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                    valueEnded = true
                }
                char == '@' -> {
                    val after = coffeeNameEnd(text, index + 1, end)
                    val named = after > index + 1
                    val function = named && out.collecting && (functionFollows(text, after, end) || (after < end && text[after] == '('))
                    out.span(index, after, if (function) RoleCode.FUNCTION else RoleCode.VARIABLE)
                    index = after
                    valueEnded = true
                }
                nameStartLength(text, index, end) > 0 || char == '$' -> {
                    val after = coffeeNameEnd(text, index, end)
                    val kind = if (wasAfterDot) 0 else words.lookup(text, index, after)
                    if (out.collecting) {
                        val role =
                            when {
                                kind != 0 -> kind and 0xFF
                                index < paramsEnd -> RoleCode.VARIABLE
                                functionFollows(text, after, end) -> RoleCode.FUNCTION
                                after < end && text[after] == '(' -> RoleCode.FUNCTION
                                wasAfterDot -> RoleCode.PROPERTY
                                isKey(text, after, end) -> RoleCode.PROPERTY
                                text[index] in 'A'..'Z' -> if (isAllCaps(text, index, after)) RoleCode.CONSTANT else RoleCode.TYPE
                                else -> 0
                            }
                        if (role != 0) out.span(index, after, role)
                    }
                    valueEnded = kind == 0 || kind and VALUE != 0
                    index = after
                }
                char == '-' && next == '>' || char == '=' && next == '>' -> {
                    out.span(index, index + 2, RoleCode.KEYWORD)
                    index += 2
                }
                char == '(' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    if (out.collecting && index >= paramsEnd) paramsEnd = parametersEnd(text, index, end)
                    index += 1
                }
                char == '{' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    st = openBracket(st, char)
                    index += 1
                }
                char == '}' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    st = closeBracket(st, char)
                    index += 1
                    valueEnded = true
                }
                char == ')' || char == ']' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    valueEnded = true
                }
                char == '[' || char == ',' || char == ';' || char == ':' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                }
                char == ':' && next == ':' -> {
                    out.span(index, index + 2, RoleCode.PUNCTUATION)
                    index += 2
                    afterDot = true
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
                    }
                }
                isOperatorChar(char) -> {
                    var after = index + 1
                    while (after < end && after < index + 3 && isOperatorChar(text[after]) && !(text[after] == '-' && after + 1 < end && text[after + 1] == '>')) after += 1
                    out.span(index, after, RoleCode.OPERATOR)
                    index = after
                    // `x?` and `a++` end a value; other operators expect one.
                    valueEnded = char == '?' && wasValue
                }
                else -> index += 1
            }
        }
        if (out.stopped) return st
        return finishLine(st)
    }

    /** The end of a name (with `$`) from [from]; [from] when none starts there. */
    private fun coffeeNameEnd(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        var index = from
        while (index < end) {
            if (text[index] == '$') {
                index += 1
                continue
            }
            val length = if (index == from) nameStartLength(text, index, end) else namePartLength(text, index, end)
            if (length == 0) break
            index += length
        }
        return index
    }

    /** True when a `/` after [from] closes a regex on this line (unescaped). */
    private fun hasLaterSlash(
        text: CharArray,
        from: Int,
        end: Int,
    ): Boolean {
        var index = from
        while (index < end) {
            when (text[index]) {
                '\\' -> index += 1
                '/' -> return true
            }
            index += 1
        }
        return false
    }

    /** True when the name ending at [after] is assigned a function: `= (a, b) ->`, `: =>`. */
    private fun functionFollows(
        text: CharArray,
        after: Int,
        end: Int,
    ): Boolean {
        var index = LexerScan.skipBlanks(text, after, end)
        if (index >= end) return false
        val char = text[index]
        if (char == ':' && (index + 1 >= end || text[index + 1] != ':')) {
            index += 1
        } else if (char == '=' && (index + 1 >= end || (text[index + 1] != '=' && text[index + 1] != '>'))) {
            index += 1
        } else {
            return false
        }
        index = LexerScan.skipBlanks(text, index, end)
        if (index < end && text[index] == '(') {
            val close = parametersEnd(text, index, end)
            return close > 0
        }
        return isArrow(text, index, end)
    }

    /**
     * For `(` at [open]: the offset of its `)` when the parameter list is followed by `->` or
     * `=>` within [LOOKAHEAD] characters (no nested parentheses), or -1.
     */
    private fun parametersEnd(
        text: CharArray,
        open: Int,
        end: Int,
    ): Int {
        val limit = minOf(end, open + LOOKAHEAD)
        var index = open + 1
        while (index < limit && text[index] != ')') {
            val char = text[index]
            if (char == '(' || char == '"' || char == '\'' || char == '#') return -1
            index += 1
        }
        if (index >= limit) return -1
        val close = index
        return if (isArrow(text, LexerScan.skipBlanks(text, close + 1, end), end)) close else -1
    }

    private fun isArrow(
        text: CharArray,
        at: Int,
        end: Int,
    ): Boolean = at + 1 < end && (text[at] == '-' || text[at] == '=') && text[at + 1] == '>'

    /** True when the name ending at [after] is an object key: `key: value` (not `::`). */
    private fun isKey(
        text: CharArray,
        after: Int,
        end: Int,
    ): Boolean {
        val at = LexerScan.skipBlanks(text, after, end)
        return at < end && text[at] == ':' && (at + 1 >= end || text[at + 1] != ':')
    }

    private fun isOperatorChar(char: Char): Boolean =
        when (char) {
            '+', '-', '*', '/', '%', '=', '<', '>', '!', '&', '|', '^', '~', '?' -> true
            else -> false
        }

    init {
        words.register(
            RoleCode.KEYWORD,
            "if", "else", "unless", "then", "while", "until", "loop", "for", "in", "of", "by", "when", "switch", "return",
            "break", "continue", "try", "catch", "finally", "throw", "new", "delete", "typeof", "instanceof", "class",
            "extends", "super", "and", "or", "not", "is", "isnt", "do", "await", "yield", "import", "export", "from", "default",
            "as", "own",
        )
        words.register(RoleCode.VARIABLE or VALUE, "this")
        words.register(
            RoleCode.CONSTANT or VALUE,
            "true", "false", "yes", "no", "on", "off", "null", "undefined", "NaN", "Infinity",
        )
    }
}
