package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit

/**
 * Makefiles (GNU make, with the POSIX subset).
 *
 * Roles: `#` comments are Comment (a trailing `\` continues them, as in make). Rule targets
 * (`.PHONY`, `%.o`, `all`) are Function with `:`, `::`, `&:` as Punctuation, and the `|` of
 * order-only prerequisites is Operator. In an assignment the name is Variable and `=`, `:=`,
 * `::=`, `?=`, `+=`, `!=` are Operator, also for target-specific ones (`app: CFLAGS += -g`).
 * Directives (`include`, `ifeq`, `ifdef`, `else`, `endif`, `define`, `endef`, `export`,
 * `override`, `vpath`, ...) are Keyword. References `$(VAR)`, `${VAR}`, `$@`, `$<`, and the name
 * in a substitution reference (`$(SRC:.c=.o)`) are Variable; a function call
 * (`$(patsubst ...)`, `$(shell ...)`) has its name as Function and its `$(`, `,`, and `)` as
 * Punctuation, as are a conditional's `(`, `,`, and `)`; `$$` is Constant outside recipes.
 * Recipe lines (after a tab, or after `;` on a rule line) get simple shell rules: `@`, `-`, `+`
 * prefixes are Keyword, the command word Function, shell keywords Keyword (a `for` loop's name
 * Variable), quotes String (with make references inside), `$$name` Variable, options Constant,
 * `&&`, `|`, `;`, `>` Operator, and a `#` word starts a Comment. A `define` body is plain text
 * with its references colored.
 *
 * States: 0 at a line start. A line ending in `\` continues: bits 3-5 hold what continues
 * ([VALUE], [RECIPE], or [COMMENT]) and bits 0-2 how many references or parentheses stay open;
 * a recipe also keeps its open quote in bits 6-7 and in bits 10-11 the word it expects next (a
 * command, a `for` loop's name, or its `in`). Inside a `define` body bits 3-5 hold [DEFINE] and
 * bits 8-9 how many nested `define`s are open.
 *
 * Known gaps: any line that starts with a tab outside a `define` is a recipe; a multi-line
 * target list colors its continuation lines as prerequisites; `.RECIPEPREFIX` is ignored.
 */
internal object MakeLexer : HighlightLexer() {
    private const val DEPTH_MASK = 7
    private const val MAX_DEPTH = 7
    private const val KIND_SHIFT = 3
    private const val KIND_MASK = 7 shl KIND_SHIFT
    private const val VALUE = 1
    private const val RECIPE = 2
    private const val COMMENT = 3
    private const val DEFINE = 4
    private const val QUOTE_SHIFT = 6
    private const val SINGLE = 1
    private const val DOUBLE = 2
    private const val NEST_SHIFT = 8
    private const val EXPECT_SHIFT = 10

    // What a recipe expects next.
    private const val ARGUMENT = 0
    private const val COMMAND = 1
    private const val LOOP_NAME = 2
    private const val IN_WORD = 3

    // Value text modes.
    private const val PLAIN_TEXT = 0
    private const val PREREQUISITES = 1
    private const val CONDITION = 2

    // Line classes.
    private const val OTHER = 0
    private const val ASSIGN = 1
    private const val RULE = 2

    // Shell word kinds.
    private const val STARTS_COMMAND = 1
    private const val ENDS_COMMAND = 2
    private const val LOOP = 3

    // Directive kinds.
    private const val CONDITIONAL = 1
    private const val NAMED = 2
    private const val PLAIN_DIRECTIVE = 3
    private const val ELSE = 4
    private const val DEFINE_WORD = 5
    private const val ENDEF = 6
    private const val PREFIX = 7

    private val directives = LexerWords()
    private val functions = LexerWords()
    private val shellKeywords = LexerWords()

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        when ((state and KIND_MASK) ushr KIND_SHIFT) {
            DEFINE -> return defineLine(text, start, end, state, out)
            COMMENT -> {
                out.span(start, end, RoleCode.COMMENT)
                return if (continued(text, start, end)) state else 0
            }
            VALUE -> return valueText(text, start, start, end, state and DEPTH_MASK, 0, PLAIN_TEXT, out)
            RECIPE -> return recipe(text, start, end, state, false, out)
        }
        if (start < end && text[start] == '\t') return recipe(text, start + 1, end, COMMAND shl EXPECT_SHIFT, true, out)
        val first = LexerScan.skipBlanks(text, start, end)
        if (first >= end) return 0
        if (text[first] == '#') {
            out.span(first, end, RoleCode.COMMENT)
            return if (continued(text, start, end)) COMMENT shl KIND_SHIFT else 0
        }
        return statement(text, start, first, end, out)
    }

    /** A line that is not a recipe or comment: a directive, an assignment, a rule, or text. */
    private fun statement(
        text: CharArray,
        lineStart: Int,
        from: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        var wordEnd = from
        while (wordEnd < end && (isAsciiLetter(text[wordEnd]) || text[wordEnd] == '-')) wordEnd += 1
        val kind = if (isDirectiveEnd(text, wordEnd, end)) directives.lookup(text, from, wordEnd) else 0
        if (kind != 0) {
            out.span(from, wordEnd, RoleCode.KEYWORD)
            val rest = LexerScan.skipBlanks(text, wordEnd, end)
            when (kind) {
                CONDITIONAL -> return valueText(text, lineStart, rest, end, 0, 0, CONDITION, out)
                PLAIN_DIRECTIVE -> return valueText(text, lineStart, rest, end, 0, 0, PLAIN_TEXT, out)
                NAMED -> return valueText(text, lineStart, rest, end, 0, RoleCode.VARIABLE, PLAIN_TEXT, out)
                ELSE -> return if (rest < end && text[rest] != '#') statement(text, lineStart, rest, end, out) else valueText(text, lineStart, rest, end, 0, 0, PLAIN_TEXT, out)
                ENDEF -> return valueText(text, lineStart, rest, end, 0, 0, PLAIN_TEXT, out)
                DEFINE_WORD -> {
                    defineHead(text, rest, end, out)
                    return DEFINE shl KIND_SHIFT
                }
                PREFIX -> {
                    if (rest >= end) return 0
                    val line = classify(text, rest, end)
                    if ((line ushr 32).toInt() == ASSIGN) return assignment(text, lineStart, rest, line.toInt(), end, out)
                    var innerEnd = rest
                    while (innerEnd < end && isAsciiLetter(text[innerEnd])) innerEnd += 1
                    if (isDirectiveEnd(text, innerEnd, end) && directives.lookup(text, rest, innerEnd) != 0) return statement(text, lineStart, rest, end, out)
                    // `export NAME OTHER`: names.
                    return valueText(text, lineStart, rest, end, 0, RoleCode.VARIABLE, PLAIN_TEXT, out)
                }
            }
        }
        val line = classify(text, from, end)
        return when ((line ushr 32).toInt()) {
            ASSIGN -> assignment(text, lineStart, from, line.toInt(), end, out)
            RULE -> rule(text, lineStart, from, line.toInt(), end, out)
            else -> valueText(text, lineStart, from, end, 0, 0, PLAIN_TEXT, out)
        }
    }

    /** `define NAME [op]`: the name as Variable, an operator as Operator. */
    private fun defineHead(
        text: CharArray,
        from: Int,
        end: Int,
        out: SpanSink,
    ) {
        var nameEnd = from
        while (nameEnd < end && !isBlank(text[nameEnd]) && text[nameEnd] != '#' && !isOperatorStart(text, nameEnd, end)) nameEnd += 1
        refs(text, from, nameEnd, 0, RoleCode.VARIABLE, out)
        var index = LexerScan.skipBlanks(text, nameEnd, end)
        if (index < end && isOperatorStart(text, index, end)) {
            val after = operatorEnd(text, index, end)
            out.span(index, after, RoleCode.OPERATOR)
            index = after
        }
        index = LexerScan.skipBlanks(text, index, end)
        if (index < end && text[index] == '#') out.span(index, end, RoleCode.COMMENT)
    }

    /** A line inside a `define` body: plain text with references, or the `endef` that ends it. */
    private fun defineLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        val first = LexerScan.skipBlanks(text, start, end)
        var wordEnd = first
        while (wordEnd < end && isAsciiLetter(text[wordEnd])) wordEnd += 1
        val nest = (state ushr NEST_SHIFT) and 3
        if (isDirectiveEnd(text, wordEnd, end)) {
            val kind = directives.lookup(text, first, wordEnd)
            if (kind == ENDEF) {
                out.span(first, wordEnd, RoleCode.KEYWORD)
                val rest = LexerScan.skipBlanks(text, wordEnd, end)
                if (rest < end && text[rest] == '#') out.span(rest, end, RoleCode.COMMENT)
                return if (nest == 0) 0 else state - (1 shl NEST_SHIFT)
            }
            if (kind == DEFINE_WORD) {
                out.span(first, wordEnd, RoleCode.KEYWORD)
                refs(text, wordEnd, end, 0, 0, out)
                return if (nest < 3) state + (1 shl NEST_SHIFT) else state
            }
        }
        refs(text, start, end, 0, 0, out)
        return state
    }

    /** `NAME op value`, with [operator] at the first operator character. */
    private fun assignment(
        text: CharArray,
        lineStart: Int,
        from: Int,
        operator: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        refs(text, from, operator, 0, RoleCode.VARIABLE, out)
        val after = operatorEnd(text, operator, end)
        out.span(operator, after, RoleCode.OPERATOR)
        return valueText(text, lineStart, LexerScan.skipBlanks(text, after, end), end, 0, 0, PLAIN_TEXT, out)
    }

    /** `targets : prerequisites`, with the rule's colon at [colon] (or the `&` of `&:`). */
    private fun rule(
        text: CharArray,
        lineStart: Int,
        from: Int,
        colon: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        refs(text, from, colon, 0, RoleCode.FUNCTION, out)
        var after = colon + 1
        while (after < end && after < colon + 3 && text[after] == ':') after += 1
        out.span(colon, after, RoleCode.PUNCTUATION)
        val rest = LexerScan.skipBlanks(text, after, end)
        // A target-specific assignment: `target: NAME op value`.
        val tail = classify(text, rest, end)
        if ((tail ushr 32).toInt() == ASSIGN) {
            var nameFrom = rest
            var wordEnd = rest
            while (wordEnd < end && isAsciiLetter(text[wordEnd])) wordEnd += 1
            if (isDirectiveEnd(text, wordEnd, end) && directives.lookup(text, rest, wordEnd) == PREFIX) {
                out.span(rest, wordEnd, RoleCode.KEYWORD)
                nameFrom = LexerScan.skipBlanks(text, wordEnd, end)
            }
            return assignment(text, lineStart, nameFrom, tail.toInt(), end, out)
        }
        return valueText(text, lineStart, rest, end, 0, 0, PREREQUISITES, out)
    }

    /**
     * Value text from [from]: references, open parentheses of function calls, and a trailing
     * comment. In [PREREQUISITES], `|` is Operator, a further `:` Punctuation, and `;` starts a
     * recipe; in a [CONDITION], `(`, `,`, and `)` are Punctuation. Words take [role] (blanks
     * between them stay plain). Returns the line's end state.
     */
    private fun valueText(
        text: CharArray,
        lineStart: Int,
        from: Int,
        end: Int,
        depth: Int,
        role: Int,
        mode: Int,
        out: SpanSink,
    ): Int {
        var level = depth
        var index = from
        var segment = from
        while (index < end && !out.stopped) {
            val char = text[index]
            when {
                char == '$' && index + 1 < end -> {
                    if (level == 0 && role != 0) out.span(segment, index, role)
                    val result = ref(text, index, end, level, true, out)
                    level = (result ushr 32).toInt()
                    index = result.toInt()
                    segment = index
                    continue
                }
                char == '\\' -> {
                    index = if (index + 1 < end) index + 2 else index + 1
                    continue
                }
                char == '#' -> {
                    if (level == 0 && role != 0) out.span(segment, index, role)
                    out.span(index, end, RoleCode.COMMENT)
                    return if (continued(text, lineStart, end)) COMMENT shl KIND_SHIFT else 0
                }
                level > 0 && (char == '(' || char == '{' || char == ')' || char == '}' || char == ',') -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    if (char == '(' || char == '{') {
                        if (level < MAX_DEPTH) level += 1
                    } else if (char != ',') {
                        level -= 1
                    }
                    segment = index + 1
                }
                level == 0 && mode == CONDITION && (char == '(' || char == ')' || char == ',') -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    segment = index + 1
                }
                level == 0 && mode == PREREQUISITES && (char == '|' || char == ':' || char == ';') -> {
                    if (role != 0) out.span(segment, index, role)
                    if (char == ';') {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        return recipe(text, index + 1, end, COMMAND shl EXPECT_SHIFT, true, out)
                    }
                    out.span(index, index + 1, if (char == '|') RoleCode.OPERATOR else RoleCode.PUNCTUATION)
                    segment = index + 1
                }
                isBlank(char) -> {
                    if (level == 0 && role != 0) out.span(segment, index, role)
                    segment = index + 1
                }
            }
            index += 1
        }
        if (level == 0 && role != 0) out.span(segment, end, role)
        return if (continued(text, lineStart, end)) (VALUE shl KIND_SHIFT) or level else 0
    }

    /** Colors `text[from, to)`: references, and words in [role] outside them; returns the open depth. */
    private fun refs(
        text: CharArray,
        from: Int,
        to: Int,
        depth: Int,
        role: Int,
        out: SpanSink,
    ): Int {
        var level = depth
        var index = from
        var segment = from
        while (index < to && !out.stopped) {
            val char = text[index]
            if (char == '$' && index + 1 < to) {
                if (level == 0 && role != 0) out.span(segment, index, role)
                val result = ref(text, index, to, level, true, out)
                level = (result ushr 32).toInt()
                index = result.toInt()
                segment = index
                continue
            }
            if (level > 0 && (char == '(' || char == '{' || char == ')' || char == '}' || char == ',')) {
                out.span(index, index + 1, RoleCode.PUNCTUATION)
                if (char == '(' || char == '{') {
                    if (level < MAX_DEPTH) level += 1
                } else if (char != ',') {
                    level -= 1
                }
                segment = index + 1
            } else if (isBlank(char)) {
                if (level == 0 && role != 0) out.span(segment, index, role)
                segment = index + 1
            }
            index += 1
        }
        if (level == 0 && role != 0) out.span(segment, to, role)
        return level
    }

    /**
     * A `$` reference at [at] (with a character after it); returns the open depth above the offset
     * after the part consumed. [escapes] colors `$$` as Constant.
     */
    private fun ref(
        text: CharArray,
        at: Int,
        end: Int,
        depth: Int,
        escapes: Boolean,
        out: SpanSink,
    ): Long {
        val next = text[at + 1]
        if (next == '$') {
            if (escapes) out.span(at, at + 2, RoleCode.CONSTANT)
            return packed(depth, at + 2)
        }
        if (next == '(' || next == '{') {
            val closer = if (next == '(') ')' else '}'
            var nameEnd = at + 2
            while (nameEnd < end && isRefNamePart(text[nameEnd])) nameEnd += 1
            val deeper = if (depth < MAX_DEPTH) depth + 1 else depth
            if (nameEnd > at + 2 && nameEnd < end && text[nameEnd] == closer) {
                out.span(at, nameEnd + 1, RoleCode.VARIABLE)
                return packed(depth, nameEnd + 1)
            }
            if (nameEnd > at + 2 && nameEnd < end && isBlank(text[nameEnd]) && functions.lookup(text, at + 2, nameEnd) != 0) {
                out.span(at, at + 2, RoleCode.PUNCTUATION)
                out.span(at + 2, nameEnd, RoleCode.FUNCTION)
                return packed(deeper, nameEnd)
            }
            if (nameEnd > at + 2) {
                // A substitution reference (`$(SRC:.c=.o)`) or a name running past the line.
                out.span(at, nameEnd, RoleCode.VARIABLE)
                return packed(deeper, nameEnd)
            }
            out.span(at, at + 2, RoleCode.PUNCTUATION)
            return packed(deeper, at + 2)
        }
        if (isBlank(next)) return packed(depth, at + 1)
        out.span(at, at + 2, RoleCode.VARIABLE)
        return packed(depth, at + 2)
    }

    /**
     * A recipe line from [from] (after its tab, or after a rule's `;`), or its continuation when
     * not [fresh]. [state] carries the quote, open references, and what the recipe expects next.
     */
    private fun recipe(
        text: CharArray,
        from: Int,
        end: Int,
        state: Int,
        fresh: Boolean,
        out: SpanSink,
    ): Int {
        var index = from
        var quote = (state ushr QUOTE_SHIFT) and 3
        var level = state and DEPTH_MASK
        var expect = (state ushr EXPECT_SHIFT) and 3
        if (fresh) {
            index = LexerScan.skipBlanks(text, index, end)
            val prefix = index
            while (index < end && (text[index] == '@' || text[index] == '-' || text[index] == '+')) index += 1
            out.span(prefix, index, RoleCode.KEYWORD)
        }
        var wordStart = true
        while (index < end && !out.stopped) {
            val char = text[index]
            if (quote != 0) {
                when {
                    char == '$' && index + 1 < end && text[index + 1] == '$' -> {
                        if (quote == DOUBLE) {
                            index = shellVariable(text, index, end, out)
                        } else {
                            out.span(index, index + 2, RoleCode.STRING)
                            index += 2
                        }
                    }
                    char == '$' && index + 1 < end -> {
                        val result = ref(text, index, end, level, false, out)
                        level = (result ushr 32).toInt()
                        index = result.toInt()
                    }
                    char == '\\' && quote == DOUBLE && index + 1 < end -> {
                        out.span(index, index + 2, RoleCode.CONSTANT)
                        index += 2
                    }
                    (char == '\'' && quote == SINGLE) || (char == '"' && quote == DOUBLE) -> {
                        out.span(index, index + 1, RoleCode.STRING)
                        quote = 0
                        index += 1
                    }
                    else -> {
                        var after = index + 1
                        while (after < end && text[after] != '$' && text[after] != '\\' && text[after] != '\'' && text[after] != '"') after += 1
                        out.span(index, after, RoleCode.STRING)
                        index = after
                    }
                }
                continue
            }
            when {
                isBlank(char) -> {
                    index += 1
                    wordStart = true
                    continue
                }
                char == '#' && wordStart && level == 0 -> {
                    out.span(index, end, RoleCode.COMMENT)
                    index = end
                }
                char == '\'' || char == '"' -> {
                    out.span(index, index + 1, RoleCode.STRING)
                    quote = if (char == '\'') SINGLE else DOUBLE
                    index += 1
                    expect = ARGUMENT
                }
                char == '$' && index + 1 < end && text[index + 1] == '$' -> {
                    val shellSubstitution = index + 2 < end && text[index + 2] == '('
                    index = shellVariable(text, index, end, out)
                    expect = if (shellSubstitution) COMMAND else ARGUMENT
                }
                char == '$' && index + 1 < end -> {
                    val result = ref(text, index, end, level, false, out)
                    level = (result ushr 32).toInt()
                    index = result.toInt()
                    expect = ARGUMENT
                }
                level > 0 && (char == '(' || char == '{' || char == ')' || char == '}' || char == ',') -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    if (char == '(' || char == '{') {
                        if (level < MAX_DEPTH) level += 1
                    } else if (char != ',') {
                        level -= 1
                    }
                    index += 1
                }
                char == '(' || char == ')' || char == '{' || char == '}' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    expect = if (char == '(' || char == '{') COMMAND else ARGUMENT
                    index += 1
                }
                char == ';' || char == '|' || char == '&' -> {
                    var after = index + 1
                    while (after < end && after < index + 2 && (text[after] == '|' || text[after] == '&' || text[after] == ';')) after += 1
                    out.span(index, after, RoleCode.OPERATOR)
                    expect = COMMAND
                    index = after
                }
                char == '<' || char == '>' -> {
                    var after = index + 1
                    while (after < end && after < index + 3 && (text[after] == '>' || text[after] == '&' || text[after] == '<')) after += 1
                    out.span(index, after, RoleCode.OPERATOR)
                    expect = ARGUMENT
                    index = after
                }
                char == '\\' -> index = if (index + 1 < end) index + 2 else index + 1
                else -> {
                    var after = index + 1
                    while (after < end && !isShellMeta(text[after])) after += 1
                    if (wordStart && level == 0) expect = word(text, index, after, expect, out)
                    index = after
                }
            }
            wordStart = false
        }
        if (out.stopped || !continued(text, from, end)) return 0
        return (RECIPE shl KIND_SHIFT) or (quote shl QUOTE_SHIFT) or level or (expect shl EXPECT_SHIFT)
    }

    /** A recipe word at [from], where the recipe [expect]s a kind of word; returns what it expects after it. */
    private fun word(
        text: CharArray,
        from: Int,
        to: Int,
        expect: Int,
        out: SpanSink,
    ): Int {
        if (expect == LOOP_NAME) {
            out.span(from, to, RoleCode.VARIABLE)
            return IN_WORD
        }
        if (expect == IN_WORD && LexerScan.equals(text, from, to, "in")) {
            out.span(from, to, RoleCode.KEYWORD)
            return ARGUMENT
        }
        if (expect != COMMAND) {
            if (text[from] == '-' && to - from > 1) {
                var stop = from + 1
                while (stop < to && text[stop] != '=') stop += 1
                out.span(from, stop, RoleCode.CONSTANT)
            }
            return ARGUMENT
        }
        val kind = shellKeywords.lookup(text, from, to)
        if (kind != 0) {
            out.span(from, to, RoleCode.KEYWORD)
            return when (kind) {
                LOOP -> LOOP_NAME
                ENDS_COMMAND -> ARGUMENT
                else -> COMMAND
            }
        }
        var nameEnd = from
        while (nameEnd < to && (isAsciiLetter(text[nameEnd]) || isDigit(text[nameEnd]) || text[nameEnd] == '_')) nameEnd += 1
        if (nameEnd > from && nameEnd < to && text[nameEnd] == '=') {
            // `NAME=value cmd`: an environment assignment before the command.
            out.span(from, nameEnd, RoleCode.VARIABLE)
            out.span(nameEnd, nameEnd + 1, RoleCode.OPERATOR)
            return COMMAND
        }
        out.span(from, to, RoleCode.FUNCTION)
        return ARGUMENT
    }

    /** `$$name`, `$${name}`, `$$?`, or the `$$(` of a shell command substitution at [at]. */
    private fun shellVariable(
        text: CharArray,
        at: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        var after = at + 2
        if (after < end) {
            val char = text[after]
            when {
                char == '(' -> {
                    out.span(at, after + 1, RoleCode.PUNCTUATION)
                    return after + 1
                }
                char == '{' -> {
                    var close = after + 1
                    while (close < end && isNamePart(text[close])) close += 1
                    after = if (close < end && text[close] == '}') close + 1 else after + 1
                }
                isAsciiLetter(char) || char == '_' -> while (after < end && isNamePart(text[after])) after += 1
                isDigit(char) || char == '?' || char == '@' || char == '*' || char == '#' || char == '!' || char == '$' -> after += 1
            }
        }
        out.span(at, after, RoleCode.VARIABLE)
        return after
    }

    /**
     * Finds what a statement line is: the first `=` or assignment operator, or the rule colon,
     * outside references and before a comment. Returns the class above the offset.
     */
    private fun classify(
        text: CharArray,
        from: Int,
        end: Int,
    ): Long {
        var depth = 0
        var index = from
        while (index < end) {
            val char = text[index]
            when {
                char == '$' -> {
                    if (index + 1 < end && (text[index + 1] == '(' || text[index + 1] == '{')) {
                        depth += 1
                    }
                    index += 2
                    continue
                }
                depth > 0 -> {
                    if (char == '(' || char == '{') depth += 1
                    if (char == ')' || char == '}') depth -= 1
                }
                char == '\\' -> {
                    index += 2
                    continue
                }
                char == '#' || char == ';' -> return packed(OTHER, index)
                char == '=' -> {
                    val operator = if (index > from && (text[index - 1] == '?' || text[index - 1] == '+' || text[index - 1] == '!')) index - 1 else index
                    return packed(ASSIGN, operator)
                }
                char == ':' -> {
                    var after = index + 1
                    while (after < end && after < index + 3 && text[after] == ':') after += 1
                    if (after < end && text[after] == '=') return packed(ASSIGN, index)
                    return packed(RULE, if (index > from && text[index - 1] == '&') index - 1 else index)
                }
            }
            index += 1
        }
        return packed(OTHER, end)
    }

    /** The offset after the assignment operator starting at [at]. */
    private fun operatorEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at
        while (index < end && index < at + 3 && (text[index] == ':' || text[index] == '?' || text[index] == '+' || text[index] == '!')) index += 1
        if (index < end && text[index] == '=') index += 1
        return index
    }

    /** True when an assignment operator (`=`, `:=`, `::=`, `?=`, `+=`, `!=`) starts at [at]. */
    private fun isOperatorStart(
        text: CharArray,
        at: Int,
        end: Int,
    ): Boolean {
        val char = text[at]
        if (char == '=') return true
        if (char != ':' && char != '?' && char != '+' && char != '!') return false
        val after = operatorEnd(text, at, end)
        return after > at + 1 && text[after - 1] == '='
    }

    /** True when the line ends in an odd run of `\`, which continues it. */
    private fun continued(
        text: CharArray,
        from: Int,
        end: Int,
    ): Boolean {
        var index = end
        while (index > from && text[index - 1] == '\\') index -= 1
        return (end - index) % 2 == 1
    }

    /** True when a directive word ending at [at] stands alone: a blank, `(`, or the line end follows. */
    private fun isDirectiveEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Boolean = at >= end || isBlank(text[at]) || text[at] == '('

    private fun isBlank(char: Char): Boolean = char == ' ' || char == '\t'

    private fun isNamePart(char: Char): Boolean = isAsciiLetter(char) || isDigit(char) || char == '_'

    private fun isRefNamePart(char: Char): Boolean =
        when (char) {
            ' ', '\t', ':', '#', '=', '$', '(', ')', '{', '}', ',', '\'', '"', '\\', ';' -> false
            else -> true
        }

    private fun isShellMeta(char: Char): Boolean =
        when (char) {
            ' ', '\t', '\'', '"', '$', ';', '|', '&', '<', '>', '(', ')', '\\' -> true
            else -> false
        }

    private fun packed(
        high: Int,
        offset: Int,
    ): Long = (high.toLong() shl 32) or (offset.toLong() and 0xFFFFFFFFL)

    init {
        directives.register(CONDITIONAL, "ifeq", "ifneq")
        directives.register(NAMED, "ifdef", "ifndef", "undefine", "unexport")
        directives.register(PLAIN_DIRECTIVE, "include", "-include", "sinclude", "vpath", "endif")
        directives.register(ELSE, "else")
        directives.register(DEFINE_WORD, "define")
        directives.register(ENDEF, "endef")
        directives.register(PREFIX, "export", "override", "private")
        functions.register(
            1,
            "subst", "patsubst", "strip", "findstring", "filter", "filter-out", "sort", "word", "wordlist", "words", "firstword",
            "lastword", "dir", "notdir", "suffix", "basename", "addsuffix", "addprefix", "join", "wildcard", "realpath", "abspath",
            "error", "warning", "info", "shell", "origin", "flavor", "foreach", "if", "or", "and", "call", "eval", "file", "value",
            "let", "intcmp", "guile",
        )
        shellKeywords.register(STARTS_COMMAND, "if", "then", "else", "elif", "do", "while", "until", "!")
        shellKeywords.register(ENDS_COMMAND, "fi", "done", "esac", "exit", "return", "export", "in", "case")
        shellKeywords.register(LOOP, "for", "select")
    }
}
