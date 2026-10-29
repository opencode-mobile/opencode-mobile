package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * PowerShell.
 *
 * Roles: `#` comments and `<# ... #>` block comments are Comment. `"..."` expandable strings and
 * `'...'` verbatim strings (both may span lines) and here-strings (`@"` ... `"@`, `@'` ...
 * `'@`) are String, with backtick escapes and doubled quotes as Constant. In expandable ones
 * `$name`, `$env:Path`, and `${...}` are Variable, and `$(` ... `)` subexpressions are
 * Punctuation around code. Variables (`$x`, `$script:x`, `$_`, `@splat`) are Variable, with
 * `$true`, `$false`, and `$null` as Constant. Keywords (any case) are Keyword. A `Verb-Noun`
 * command, the name after `function` or `filter`, and a method called after `.` or `::` are
 * Function; other members are Property. A type literal or attribute (`[int]`,
 * `[System.IO.File]`, `[Parameter(...)]`) and the name after `class` or `enum` are Type.
 * Comparison and logical operators (`-eq`, `-like`, `-and`, `-f`, `-ceq`, ...) are Operator;
 * other `-Parameters` are Constant, like a shell option. Numbers (hex, `1.5e3`, `10L`, `4kb`)
 * are Number; operators Operator; brackets, `,`, `;`, `.`, and `::` Punctuation.
 *
 * States: bits 0-2 hold the depth of a stack of up to [MAX_FRAMES] open constructs, 4 bits each
 * from bit 3: bit 0 tells an expandable string from a `$( )` subexpression, bits 1-3 count the
 * parentheses open inside a subexpression (0-7). Bits 19-21 hold a construct open on top that
 * nothing nests in: a verbatim string, a block comment, or an expandable or verbatim
 * here-string. Here-strings keep the stack beneath them, so they may sit inside a
 * subexpression.
 *
 * Known gaps: a here-string body colors a `$( )` only when it closes on its line; a stack
 * deeper than [MAX_FRAMES] colors a string to its close on the line; generic types
 * (`[List[string]]`) color only the innermost name; command names other than `Verb-Noun` are
 * plain.
 */
internal object PowerShellLexer : HighlightLexer() {
    private const val DEPTH_MASK = 7
    private const val FRAME_SHIFT = 3
    private const val FRAME_BITS = 4
    private const val FRAME_MASK = 0xF
    private const val MAX_FRAMES = 4
    private const val DQUOTE = 0
    private const val SUBEXPR = 1
    private const val MAX_PARENS = 7
    private const val LEAF_SHIFT = 19
    private const val LEAF_MASK = 7 shl LEAF_SHIFT
    private const val SINGLE = 1
    private const val BLOCK_COMMENT = 2
    private const val HERE_EXPANDABLE = 3
    private const val HERE_VERBATIM = 4

    // Word kinds.
    private const val KEYWORD = 1
    private const val DECLARES_FUNCTION = 2
    private const val DECLARES_TYPE = 3

    private val words = LexerWords(caseInsensitive = true)
    private val operators = LexerWords(caseInsensitive = true)

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
        var declares = 0
        while (index < end && !out.stopped) {
            val leaf = (st and LEAF_MASK) ushr LEAF_SHIFT
            if (leaf != 0) {
                when (leaf) {
                    SINGLE -> {
                        val close = verbatim(text, index, end, out)
                        if (close < 0) return st
                        index = close
                        valueEnded = true
                    }
                    BLOCK_COMMENT -> {
                        var close = index
                        while (close + 1 < end && !(text[close] == '#' && text[close + 1] == '>')) close += 1
                        if (close + 1 >= end) {
                            out.span(index, end, RoleCode.COMMENT)
                            return st
                        }
                        out.span(index, close + 2, RoleCode.COMMENT)
                        index = close + 2
                    }
                    else -> {
                        val quote = if (leaf == HERE_EXPANDABLE) '"' else '\''
                        if (index == start && end - start >= 2 && text[start] == quote && text[start + 1] == '@') {
                            out.span(start, start + 2, RoleCode.STRING)
                            index = start + 2
                            valueEnded = true
                        } else {
                            if (leaf == HERE_EXPANDABLE) expandableText(text, index, end, out) else out.span(index, end, RoleCode.STRING)
                            return st
                        }
                    }
                }
                st = st and LEAF_MASK.inv()
                continue
            }
            val depth = st and DEPTH_MASK
            val frame = if (depth == 0) -1 else frameAt(st, depth - 1)
            val char = text[index]
            val next = if (index + 1 < end) text[index + 1] else '\u0000'
            if (frame >= 0 && frame and 1 == DQUOTE) {
                when (char) {
                    '"' -> {
                        if (next == '"') {
                            out.span(index, index + 2, RoleCode.CONSTANT)
                            index += 2
                        } else {
                            out.span(index, index + 1, RoleCode.STRING)
                            st = pop(st)
                            index += 1
                            valueEnded = true
                        }
                    }
                    '`' -> {
                        val after = if (index + 1 < end) index + 2 else index + 1
                        out.span(index, after, RoleCode.CONSTANT)
                        index = after
                    }
                    '$' -> {
                        if (next == '(' && depth < MAX_FRAMES) {
                            out.span(index, index + 2, RoleCode.PUNCTUATION)
                            st = push(st, SUBEXPR)
                            index += 2
                            valueEnded = false
                        } else {
                            val after = variableEnd(text, index, end)
                            if (after > index + 1) {
                                out.span(index, after, variableRole(text, index, after))
                                index = after
                            } else {
                                out.span(index, index + 1, RoleCode.STRING)
                                index += 1
                            }
                        }
                    }
                    else -> {
                        var after = index + 1
                        while (after < end && text[after] != '"' && text[after] != '`' && text[after] != '$') after += 1
                        out.span(index, after, RoleCode.STRING)
                        index = after
                    }
                }
                continue
            }
            // Code: the top level or a subexpression.
            val wasAfterDot = afterDot
            afterDot = false
            val wasDeclares = declares
            if (char != ' ' && char != '\t') declares = 0
            when {
                char == ' ' || char == '\t' -> {
                    index += 1
                    afterDot = wasAfterDot
                }
                char == '#' -> {
                    out.span(index, end, RoleCode.COMMENT)
                    index = end
                }
                char == '<' && next == '#' -> {
                    out.span(index, index + 2, RoleCode.COMMENT)
                    st = st or (BLOCK_COMMENT shl LEAF_SHIFT)
                    index += 2
                }
                char == '"' -> {
                    if (depth < MAX_FRAMES) {
                        out.span(index, index + 1, RoleCode.STRING)
                        st = push(st, DQUOTE)
                        index += 1
                    } else {
                        index = inlineString(text, index, end, out)
                        valueEnded = true
                    }
                }
                char == '\'' -> {
                    out.span(index, index + 1, RoleCode.STRING)
                    st = st or (SINGLE shl LEAF_SHIFT)
                    index += 1
                }
                char == '@' && (next == '"' || next == '\'') && LexerScan.skipBlanks(text, index + 2, end) == end -> {
                    out.span(index, index + 2, RoleCode.STRING)
                    return st or ((if (next == '"') HERE_EXPANDABLE else HERE_VERBATIM) shl LEAF_SHIFT)
                }
                char == '@' && (next == '(' || next == '{') -> {
                    out.span(index, index + 2, RoleCode.PUNCTUATION)
                    if (next == '(') st = openParen(st)
                    index += 2
                    valueEnded = false
                }
                char == '@' && isNameStart(next) -> {
                    var after = index + 2
                    while (after < end && isNamePart(text[after])) after += 1
                    out.span(index, after, RoleCode.VARIABLE)
                    index = after
                    valueEnded = true
                }
                char == '$' -> {
                    if (next == '(') {
                        out.span(index, index + 2, RoleCode.PUNCTUATION)
                        st = openParen(st)
                        index += 2
                        valueEnded = false
                    } else {
                        val after = variableEnd(text, index, end)
                        if (after > index + 1) out.span(index, after, variableRole(text, index, after))
                        index = after
                        valueEnded = true
                    }
                }
                char == '(' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    st = openParen(st)
                    index += 1
                    valueEnded = false
                }
                char == ')' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    if (frame >= 0) {
                        val parens = frame ushr 1
                        st = if (parens == 0) pop(st) else setTop(st, frame - 2)
                    }
                    index += 1
                    valueEnded = true
                }
                char == '[' && (!valueEnded || (index > start && text[index - 1] == ']')) && typeEnd(text, index, end) > 0 -> {
                    // A type literal, also right after an attribute (`[Parameter()][string]`).
                    val typeEnd = typeEnd(text, index, end)
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    out.span(index + 1, typeEnd, RoleCode.TYPE)
                    index = typeEnd
                    if (text[typeEnd] == ']') {
                        out.span(typeEnd, typeEnd + 1, RoleCode.PUNCTUATION)
                        index += 1
                    }
                    valueEnded = false
                }
                char == '[' || char == '{' || char == ',' || char == ';' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    valueEnded = false
                }
                char == ']' || char == '}' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    valueEnded = char == ']'
                }
                char == '-' && isAsciiLetter(next) -> {
                    var after = index + 2
                    while (after < end && (isAsciiLetter(text[after]) || isDigit(text[after]))) after += 1
                    val operator =
                        operators.lookup(text, index + 1, after) != 0 ||
                            ((next == 'c' || next == 'i' || next == 'C' || next == 'I') && operators.lookup(text, index + 2, after) != 0)
                    out.span(index, after, if (operator) RoleCode.OPERATOR else RoleCode.CONSTANT)
                    index = after
                    valueEnded = false
                }
                isDigit(char) || (char == '.' && isDigit(next) && !valueEnded) -> {
                    val after = numberEnd(text, index, end)
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                    valueEnded = true
                }
                isNameStart(char) -> {
                    var after = index + 1
                    var command = false
                    while (after < end) {
                        if (isNamePart(text[after])) {
                            after += 1
                        } else if (text[after] == '-' && after + 1 < end && isAsciiLetter(text[after + 1]) && !wasAfterDot) {
                            command = true
                            after += 2
                        } else {
                            break
                        }
                    }
                    if (wasDeclares == DECLARES_FUNCTION && after + 1 < end && text[after] == ':' && isNameStart(text[after + 1])) {
                        // `function global:Name`: the scope, then the name.
                        out.span(after, after + 1, RoleCode.PUNCTUATION)
                        declares = DECLARES_FUNCTION
                        index = after + 1
                        continue
                    }
                    val call = after < end && text[after] == '('
                    val kind = if (command || wasAfterDot) 0 else words.lookup(text, index, after)
                    when {
                        wasAfterDot -> out.span(index, after, if (call) RoleCode.FUNCTION else RoleCode.PROPERTY)
                        kind == KEYWORD -> out.span(index, after, RoleCode.KEYWORD)
                        kind == DECLARES_FUNCTION || kind == DECLARES_TYPE -> {
                            out.span(index, after, RoleCode.KEYWORD)
                            declares = kind
                        }
                        wasDeclares == DECLARES_TYPE -> out.span(index, after, RoleCode.TYPE)
                        wasDeclares == DECLARES_FUNCTION || command -> out.span(index, after, RoleCode.FUNCTION)
                    }
                    index = after
                    valueEnded = kind != KEYWORD
                }
                char == '.' -> {
                    if (next == '.') {
                        out.span(index, index + 2, RoleCode.OPERATOR)
                        index += 2
                    } else {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        afterDot = true
                        index += 1
                    }
                    valueEnded = false
                }
                char == ':' && next == ':' -> {
                    out.span(index, index + 2, RoleCode.PUNCTUATION)
                    afterDot = true
                    index += 2
                    valueEnded = false
                }
                char == '`' -> {
                    val after = if (index + 1 < end) index + 2 else index + 1
                    out.span(index, after, RoleCode.CONSTANT)
                    index = after
                }
                isOperator(char) -> {
                    out.span(index, index + 1, RoleCode.OPERATOR)
                    index += 1
                    valueEnded = false
                }
                else -> index += 1
            }
        }
        return st
    }

    /** Verbatim string text from [from]: the offset after its closing `'`, or -1 when it runs past the line. */
    private fun verbatim(
        text: CharArray,
        from: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        var segment = from
        var index = from
        while (index < end) {
            if (text[index] == '\'') {
                if (index + 1 < end && text[index + 1] == '\'') {
                    out.span(segment, index, RoleCode.STRING)
                    out.span(index, index + 2, RoleCode.CONSTANT)
                    index += 2
                    segment = index
                    continue
                }
                out.span(segment, index + 1, RoleCode.STRING)
                return index + 1
            }
            index += 1
        }
        out.span(segment, end, RoleCode.STRING)
        return -1
    }

    /** A line of an expandable here-string body: text with variables, escapes, and `$( )` closing on the line. */
    private fun expandableText(
        text: CharArray,
        from: Int,
        end: Int,
        out: SpanSink,
    ) {
        var segment = from
        var index = from
        var subexpressions = true
        while (index < end && !out.stopped) {
            val char = text[index]
            if (char == '`') {
                val after = minOf(index + 2, end)
                out.span(segment, index, RoleCode.STRING)
                out.span(index, after, RoleCode.CONSTANT)
                index = after
                segment = index
            } else if (char == '$' && index + 1 < end && text[index + 1] == '(' && subexpressions) {
                val close = matchingParen(text, index + 2, end)
                if (close < 0) {
                    // Unclosed on this line: stop looking, so a line of `$(` stays linear.
                    subexpressions = false
                    index += 1
                    continue
                }
                out.span(segment, index, RoleCode.STRING)
                out.span(index, index + 2, RoleCode.PUNCTUATION)
                lexLine(text, index + 2, close, 0, out)
                out.span(close, close + 1, RoleCode.PUNCTUATION)
                index = close + 1
                segment = index
            } else if (char == '$') {
                val after = variableEnd(text, index, end)
                if (after > index + 1) {
                    out.span(segment, index, RoleCode.STRING)
                    out.span(index, after, variableRole(text, index, after))
                    segment = after
                }
                index = maxOf(after, index + 1)
            } else {
                index += 1
            }
        }
        out.span(segment, end, RoleCode.STRING)
    }

    /** The offset of the `)` that closes a `(` just before [from] on this line, or -1. */
    private fun matchingParen(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        var depth = 0
        var index = from
        while (index < end) {
            when (text[index]) {
                '(' -> depth += 1
                ')' -> if (depth == 0) return index else depth -= 1
            }
            index += 1
        }
        return -1
    }

    /** A double-quoted string too deep for the stack: String up to its close on this line. */
    private fun inlineString(
        text: CharArray,
        quote: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        var index = quote + 1
        while (index < end) {
            val char = text[index]
            if (char == '`') {
                index += 2
                continue
            }
            if (char == '"') {
                if (index + 1 < end && text[index + 1] == '"') {
                    index += 2
                    continue
                }
                break
            }
            index += 1
        }
        val after = minOf(index + 1, end)
        out.span(quote, after, RoleCode.STRING)
        return after
    }

    /**
     * The offset after a variable at the `$` at [at]: `$name`, `$scope:name`, `${...}`, `$_`,
     * `$$`, `$?`, `$^`; [at] + 1 when none follows.
     */
    private fun variableEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        if (at + 1 >= end) return at + 1
        val next = text[at + 1]
        if (next == '{') {
            var index = at + 2
            while (index < end && text[index] != '}') index += if (text[index] == '`' && index + 1 < end) 2 else 1
            return minOf(index + 1, end)
        }
        if (next == '$' || next == '?' || next == '^') return at + 2
        if (!isNameStart(next)) return at + 1
        var index = at + 2
        while (index < end && isNamePart(text[index])) index += 1
        if (index + 1 < end && text[index] == ':' && isNameStart(text[index + 1])) {
            index += 2
            while (index < end && isNamePart(text[index])) index += 1
        }
        return index
    }

    private fun variableRole(
        text: CharArray,
        from: Int,
        to: Int,
    ): Int {
        val name = from + 1
        val constant =
            equalsIgnoreCase(text, name, to, "true") || equalsIgnoreCase(text, name, to, "false") || equalsIgnoreCase(text, name, to, "null")
        return if (constant) RoleCode.CONSTANT else RoleCode.VARIABLE
    }

    private fun equalsIgnoreCase(
        text: CharArray,
        from: Int,
        to: Int,
        word: String,
    ): Boolean {
        if (to - from != word.length) return false
        for (index in word.indices) {
            val char = text[from + index]
            val folded = if (char in 'A'..'Z') char + 32 else char
            if (folded != word[index]) return false
        }
        return true
    }

    /**
     * For a `[` at [at] that opens a type literal or attribute (`[int]`, `[string[]]`,
     * `[Parameter(`), the offset after the type name (at its `]` or `(`); 0 otherwise.
     */
    private fun typeEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at + 1
        if (index >= end || !isNameStart(text[index])) return 0
        while (index < end && (isNamePart(text[index]) || text[index] == '.')) index += 1
        if (index + 1 < end && text[index] == '[' && text[index + 1] == ']') index += 2
        return if (index < end && (text[index] == ']' || text[index] == '(')) index else 0
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
        } else {
            while (index < end && isDigit(text[index])) index += 1
            if (index + 1 < end && text[index] == '.' && isDigit(text[index + 1])) {
                index += 1
                while (index < end && isDigit(text[index])) index += 1
            }
            if (index < end && (text[index] == 'e' || text[index] == 'E')) {
                var exponent = index + 1
                if (exponent < end && (text[exponent] == '+' || text[exponent] == '-')) exponent += 1
                if (exponent < end && isDigit(text[exponent])) {
                    index = exponent
                    while (index < end && isDigit(text[index])) index += 1
                }
            }
        }
        if (index < end && (text[index] == 'l' || text[index] == 'L' || text[index] == 'd' || text[index] == 'D')) index += 1
        if (index + 1 < end && text[index + 1].let { it == 'b' || it == 'B' } && text[index].lowercaseChar() in "kmgtp") index += 2
        return index
    }

    private fun openParen(state: Int): Int {
        val depth = state and DEPTH_MASK
        if (depth == 0) return state
        val frame = frameAt(state, depth - 1)
        if (frame and 1 != SUBEXPR || frame ushr 1 >= MAX_PARENS) return state
        return setTop(state, frame + 2)
    }

    private fun frameAt(
        state: Int,
        level: Int,
    ): Int = (state ushr (FRAME_SHIFT + level * FRAME_BITS)) and FRAME_MASK

    private fun push(
        state: Int,
        kind: Int,
    ): Int {
        val depth = state and DEPTH_MASK
        return (state and DEPTH_MASK.inv()) or (kind shl (FRAME_SHIFT + depth * FRAME_BITS)) or (depth + 1)
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

    private fun isOperator(char: Char): Boolean =
        when (char) {
            '+', '-', '*', '/', '%', '=', '!', '|', '<', '>', '&', '?', ':' -> true
            else -> false
        }

    private fun isNameStart(char: Char): Boolean = isAsciiLetter(char) || char == '_'

    private fun isNamePart(char: Char): Boolean = isAsciiLetter(char) || isDigit(char) || char == '_'

    init {
        words.register(
            KEYWORD,
            "begin", "break", "catch", "continue", "data", "default", "define", "do", "dynamicparam", "else", "elseif", "end",
            "exit", "finally", "for", "foreach", "from", "hidden", "if", "in", "param", "process", "return", "static", "switch",
            "throw", "trap", "try", "until", "using", "var", "while", "parallel", "sequence", "inlinescript",
        )
        words.register(DECLARES_FUNCTION, "function", "filter", "workflow", "configuration")
        words.register(DECLARES_TYPE, "class", "enum")
        operators.register(
            1,
            "eq", "ne", "gt", "ge", "lt", "le", "like", "notlike", "match", "notmatch", "contains", "notcontains", "in", "notin",
            "replace", "split", "join", "is", "isnot", "as", "and", "or", "not", "xor", "band", "bor", "bxor", "bnot", "shl", "shr",
            "f",
        )
    }
}
