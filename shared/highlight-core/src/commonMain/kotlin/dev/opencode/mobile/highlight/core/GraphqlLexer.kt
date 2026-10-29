package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isDigit

/**
 * GraphQL: executable documents (operations, fragments) and the schema language.
 *
 * Roles: `#` comments Comment; `"…"` strings and `"""…"""` block strings (across lines) String,
 * with escapes Constant; numbers Number; `true`, `false`, `null`, and enum values Constant;
 * definition keywords (`query`, `mutation`, `subscription`, `fragment`, `type`, `interface`,
 * `union`, `enum`, `input`, `scalar`, `schema`, `extend`, `directive`, `implements`,
 * `repeatable`, `on`) Keyword at the top level, and `on` after a spread; an operation, fragment,
 * or spread fragment name Function; directives (`@include`) Function; variables (`$id`)
 * Variable; type names (after a definition keyword, `on`, `implements`, `&`, `|`, and after `:`
 * where a type goes) Type; fields and field definitions (aliases included) Property; argument
 * and input-object field names Variable; directive locations Constant; `!`, `&`, and `...`
 * Operator; other punctuation Punctuation.
 *
 * Where a type goes: after `:` anywhere in a type system definition, and in an operation's
 * variable definitions; elsewhere `:` is followed by a value. Whether a name is a field or an
 * argument depends on the innermost open bracket: braces hold fields (enum values in an enum),
 * parentheses and the object values inside them hold arguments.
 *
 * States: whether a block string is open (bit 0), the definition kind (bits 1 to 3), how many
 * brackets are open (bits 4 to 8, capped at 31), and which of the innermost 16 open brackets
 * hold arguments (bits 9 to 24). A plain string ends with its line.
 */
internal object GraphqlLexer : HighlightLexer() {
    const val BLOCK_STRING = 1
    private const val KIND_SHIFT = 1
    const val NONE = 0
    const val EXECUTABLE = 1
    const val TYPE_DEFINITION = 2
    const val ENUM_DEFINITION = 3
    const val UNION_DEFINITION = 4
    const val SCHEMA_DEFINITION = 5
    const val DIRECTIVE_DEFINITION = 6
    const val DIRECTIVE_LOCATIONS = 7
    private const val DEPTH_SHIFT = 4
    const val MAX_DEPTH = 31
    private const val STACK_SHIFT = 9
    const val STACK_LEVELS = 16

    // Word kinds.
    private const val KEYWORD = 1
    private const val CONSTANT = 2

    private val words =
        LexerWords().apply {
            register(
                KEYWORD, "query", "mutation", "subscription", "fragment", "type", "interface", "union", "enum", "input", "scalar",
                "schema", "extend", "directive", "implements", "repeatable", "on",
            )
            register(CONSTANT, "true", "false", "null")
        }

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int = GraphqlLine(text, start, end, out).run(state)

    private class GraphqlLine(
        private val text: CharArray,
        start: Int,
        private val end: Int,
        private val out: SpanSink,
    ) {
        private var index = start
        private var kind = NONE
        private var depth = 0
        private var stack = 0

        // Per line only: what the next name is.
        private var typeNext = false
        private var nameRole = 0
        private var afterSpread = false

        fun run(state: Int): Int {
            kind = (state ushr KIND_SHIFT) and 7
            depth = (state ushr DEPTH_SHIFT) and MAX_DEPTH
            stack = (state ushr STACK_SHIFT) and ((1 shl STACK_LEVELS) - 1)
            if (state and BLOCK_STRING != 0) {
                if (!blockString(index, index)) return pack(true)
            }
            while (index < end && !out.stopped) {
                val char = text[index]
                when {
                    char == ' ' || char == '\t' || char == '\r' -> index += 1
                    char == '#' -> {
                        out.span(index, end, RoleCode.COMMENT)
                        index = end
                    }
                    char == '"' -> {
                        if (LexerScan.startsWith(text, index, end, "\"\"\"")) {
                            if (!blockString(index + 3, index)) return pack(true)
                        } else {
                            string()
                        }
                        clearPending()
                    }
                    isNameStart(char) -> name()
                    isDigit(char) || (char == '-' && index + 1 < end && isDigit(text[index + 1])) -> number()
                    char == '$' -> {
                        val nameEnd = nameEnd(index + 1)
                        out.span(index, nameEnd, RoleCode.VARIABLE)
                        index = nameEnd
                        clearPending()
                    }
                    char == '@' -> {
                        val nameEnd = nameEnd(index + 1)
                        out.span(index, nameEnd, if (nameEnd > index + 1) RoleCode.FUNCTION else RoleCode.PUNCTUATION)
                        index = nameEnd.coerceAtLeast(index + 1)
                        clearPending()
                    }
                    char == '.' && LexerScan.startsWith(text, index, end, "...") -> {
                        out.span(index, index + 3, RoleCode.OPERATOR)
                        index += 3
                        clearPending()
                        afterSpread = true
                    }
                    else -> punctuation(char)
                }
            }
            return pack(false)
        }

        private fun pack(blockString: Boolean): Int =
            (if (blockString) BLOCK_STRING else 0) or (kind shl KIND_SHIFT) or (depth shl DEPTH_SHIFT) or (stack shl STACK_SHIFT)

        private fun clearPending() {
            typeNext = false
            nameRole = 0
            afterSpread = false
        }

        private fun isNameStart(char: Char): Boolean = LexerScan.isAsciiLetter(char) || char == '_'

        private fun nameEnd(from: Int): Int {
            var at = from
            while (at < end && (isNameStart(text[at]) || isDigit(text[at]))) at += 1
            return at
        }

        /** True when the innermost open bracket holds arguments (parentheses or an object value). */
        private fun inArguments(): Boolean = depth in 1..STACK_LEVELS && (stack ushr (depth - 1)) and 1 != 0

        private fun push(arguments: Boolean) {
            if (depth == MAX_DEPTH) return
            depth += 1
            if (depth <= STACK_LEVELS) {
                val bit = 1 shl (depth - 1)
                stack = if (arguments) stack or bit else stack and bit.inv()
            }
        }

        private fun pop() {
            if (depth == 0) return
            if (depth <= STACK_LEVELS) stack = stack and (1 shl (depth - 1)).inv()
            depth -= 1
        }

        /** Whether a `:` here is followed by a type rather than a value. */
        private fun typeAfterColon(): Boolean =
            when (kind) {
                TYPE_DEFINITION, DIRECTIVE_DEFINITION, SCHEMA_DEFINITION -> true
                EXECUTABLE -> depth == 1 && inArguments()
                else -> false
            }

        private fun punctuation(char: Char) {
            val wasTypeNext = typeNext
            clearPending()
            when (char) {
                '{' -> {
                    if (depth == 0 && kind == NONE) kind = EXECUTABLE
                    push(arguments = inArguments())
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                }
                '(' -> {
                    push(arguments = true)
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                }
                '}', ')' -> {
                    pop()
                    if (char == '}' && depth == 0) kind = NONE
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                }
                ':' -> {
                    typeNext = typeAfterColon()
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                }
                '[', ']' -> {
                    typeNext = wasTypeNext
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                }
                '!' -> {
                    typeNext = wasTypeNext
                    out.span(index, index + 1, RoleCode.OPERATOR)
                }
                '&' -> {
                    typeNext = depth == 0
                    out.span(index, index + 1, RoleCode.OPERATOR)
                }
                '|' -> {
                    typeNext = depth == 0 && kind != DIRECTIVE_LOCATIONS
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                }
                '=' -> {
                    typeNext = depth == 0 && kind == UNION_DEFINITION
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                }
                ',' -> out.span(index, index + 1, RoleCode.PUNCTUATION)
                else -> {}
            }
            index += 1
        }

        private fun name() {
            val from = index
            val to = nameEnd(from)
            index = to
            val word = words.lookup(text, from, to)
            val role: Int
            when {
                afterSpread && word == KEYWORD && LexerScan.equals(text, from, to, "on") -> {
                    role = RoleCode.KEYWORD
                    clearPending()
                    typeNext = true
                }
                afterSpread -> {
                    role = RoleCode.FUNCTION
                    clearPending()
                }
                typeNext -> {
                    role = RoleCode.TYPE
                    clearPending()
                }
                depth == 0 -> role = topLevelName(from, to, word)
                else -> {
                    role = nestedName(to, word)
                    clearPending()
                }
            }
            if (role != 0) out.span(from, to, role)
        }

        /** A name outside every bracket: a definition keyword, a definition's name, a type, or a directive location. */
        private fun topLevelName(
            from: Int,
            to: Int,
            word: Int,
        ): Int {
            val pendingRole = nameRole
            clearPending()
            if (word == KEYWORD) {
                val nextRole =
                    when {
                        equals(from, to, "query") || equals(from, to, "mutation") || equals(from, to, "subscription") -> {
                            kind = EXECUTABLE
                            RoleCode.FUNCTION
                        }
                        equals(from, to, "fragment") -> {
                            kind = EXECUTABLE
                            RoleCode.FUNCTION
                        }
                        equals(from, to, "type") || equals(from, to, "interface") || equals(from, to, "input") ||
                            equals(from, to, "scalar") -> {
                            kind = TYPE_DEFINITION
                            RoleCode.TYPE
                        }
                        equals(from, to, "enum") -> {
                            kind = ENUM_DEFINITION
                            RoleCode.TYPE
                        }
                        equals(from, to, "union") -> {
                            kind = UNION_DEFINITION
                            RoleCode.TYPE
                        }
                        equals(from, to, "schema") -> {
                            kind = SCHEMA_DEFINITION
                            0
                        }
                        equals(from, to, "directive") -> {
                            kind = DIRECTIVE_DEFINITION
                            0
                        }
                        equals(from, to, "on") -> {
                            if (kind == DIRECTIVE_DEFINITION) kind = DIRECTIVE_LOCATIONS
                            if (kind != DIRECTIVE_LOCATIONS) typeNext = true
                            0
                        }
                        equals(from, to, "implements") -> {
                            typeNext = true
                            0
                        }
                        else -> 0
                    }
                nameRole = nextRole
                return RoleCode.KEYWORD
            }
            if (pendingRole != 0) return pendingRole
            return when (kind) {
                DIRECTIVE_LOCATIONS -> RoleCode.CONSTANT
                TYPE_DEFINITION, UNION_DEFINITION, ENUM_DEFINITION -> RoleCode.TYPE
                else -> if (word == CONSTANT) RoleCode.CONSTANT else 0
            }
        }

        /** A name inside brackets: a field, an argument, or a value. */
        private fun nestedName(
            to: Int,
            word: Int,
        ): Int {
            val after = LexerScan.skipBlanks(text, to, end)
            val keyed = after < end && text[after] == ':'
            val arguments = inArguments()
            return when {
                keyed && arguments -> RoleCode.VARIABLE
                keyed && kind == SCHEMA_DEFINITION -> RoleCode.KEYWORD
                keyed -> RoleCode.PROPERTY
                arguments -> RoleCode.CONSTANT
                word == CONSTANT -> RoleCode.CONSTANT
                kind == ENUM_DEFINITION -> RoleCode.CONSTANT
                else -> RoleCode.PROPERTY
            }
        }

        private fun equals(
            from: Int,
            to: Int,
            word: String,
        ): Boolean = LexerScan.equals(text, from, to, word)

        private fun number() {
            val from = index
            var at = if (text[index] == '-') index + 1 else index
            while (at < end && isDigit(text[at])) at += 1
            if (at + 1 < end && text[at] == '.' && isDigit(text[at + 1])) {
                at += 1
                while (at < end && isDigit(text[at])) at += 1
            }
            if (at < end && (text[at] == 'e' || text[at] == 'E')) {
                var exponent = at + 1
                if (exponent < end && (text[exponent] == '+' || text[exponent] == '-')) exponent += 1
                if (exponent < end && isDigit(text[exponent])) {
                    at = exponent
                    while (at < end && isDigit(text[at])) at += 1
                }
            }
            out.span(from, at, RoleCode.NUMBER)
            index = at
            clearPending()
        }

        /** A `"` string, ending with its closing quote or the line; escapes Constant. */
        private fun string() {
            var segment = index
            var at = index + 1
            while (at < end) {
                val char = text[at]
                if (char == '"') {
                    at += 1
                    break
                }
                if (char == '\\' && at + 1 < end) {
                    val escapeEnd = if (text[at + 1] == 'u') minOf(at + 6, end) else at + 2
                    out.span(segment, at, RoleCode.STRING)
                    out.span(at, escapeEnd, RoleCode.CONSTANT)
                    segment = escapeEnd
                    at = escapeEnd
                    continue
                }
                at += 1
            }
            out.span(segment, at, RoleCode.STRING)
            index = at
        }

        /**
         * A block string whose text continues at [from] and whose span starts at [spanFrom]; true
         * when it closes on this line (with `\"""` an escaped quote run).
         */
        private fun blockString(
            from: Int,
            spanFrom: Int,
        ): Boolean {
            var at = from
            while (at < end) {
                val char = text[at]
                if (char == '\\' && LexerScan.startsWith(text, at + 1, end, "\"\"\"")) {
                    at += 4
                    continue
                }
                if (char == '"' && LexerScan.startsWith(text, at, end, "\"\"\"")) {
                    out.span(spanFrom, at + 3, RoleCode.STRING)
                    index = at + 3
                    return true
                }
                at += 1
            }
            out.span(spanFrom, end, RoleCode.STRING)
            index = end
            return false
        }
    }
}
