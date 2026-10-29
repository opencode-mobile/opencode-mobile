package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit

/**
 * POSIX shell, bash, and zsh, as people paste them: scripts, one-liners, and `$ ` free
 * terminal snippets.
 *
 * Roles: comments (a shebang too) are Comment; quoted text is String, with `\` escapes in
 * double quotes and `$'...'` as Constant; `$name`, `$1`, `$@`, and `${...}` are Variable;
 * `$(`, `$((`, backticks, and their closers are Punctuation, with the command inside lexed as
 * code. Reserved words (`if`, `then`, `for`, `case`, `[[`, `return`, ...) and the declaration
 * builtins (`export`, `local`, `declare`, `readonly`, `typeset`) are Keyword. Any other word in
 * command position (the first word of a command) is Function, builtins included; the name in
 * an assignment (`NAME=`, also after `export`, `local`, `declare`, `readonly`) is
 * Variable with `=` as Operator. Options (`-x`, `--long`, up to a `=`) are Constant, as
 * TextMate's `constant.other.option` scope; all-digit words are Number. Control and
 * redirection operators (`|`, `&&`, `;`, `>`, `2>&1`'s `>&`, `<<<`) are Operator; `(`, `)`,
 * `{`, `}`, `[`, `]` are Punctuation. A heredoc's `<<` is Operator, its delimiter word Keyword
 * (where it opens and where it ends), and its body String, with expansions as in double
 * quotes unless the delimiter was quoted.
 *
 * States: outside a heredoc, bits 0-2 hold the depth of a stack of up to [MAX_FRAMES] open
 * constructs (2 bits each from bit 3: double quote, `$(`, backtick, `${`), so
 * `"$(cd "$(dirname "$0")" && pwd)"` nests fully; bits 11-12 the open parentheses of a `$((`
 * arithmetic frame on top, bits 13-14 an open single-quoted or `$'` string on top of the stack,
 * and bit 15 a trailing `\` that continues the command. Quotes and substitutions stay open
 * across lines, as in the shell. These states fit in 16 bits, which [DockerfileLexer] relies
 * on. Inside a heredoc body, bit 31 is set, bit 30 means `<<-` (leading tabs stripped), bit 29
 * a quoted delimiter, bits 11-28 an 18-bit [LexerDelimiter] hash of the delimiter word, and bits
 * 0-10 the stack to resume after the body. The hash keeps the state an int whatever the
 * delimiter's length; a body line with the same hash as the delimiter would also end it, about
 * one line in a quarter million.
 *
 * Known gaps: only the first heredoc of a line is followed (a second one's body lexes as code);
 * nesting past [MAX_FRAMES] colors a quote to its close on the line and leaves a deeper
 * substitution unpaired; a `(` inside `$( )` is not counted, so `$(case x in a) ...)` closes
 * early; case patterns on their own line color as commands unless followed by `)`; zsh-only
 * syntax (glob qualifiers, `${(flags)}`) is plain. A `$ ` at the start of a top-level line is a
 * terminal prompt (Punctuation), so console snippets color their commands.
 */
internal object ShellLexer : HighlightLexer() {
    private const val DEPTH_MASK = 7
    private const val FRAME_SHIFT = 3
    private const val MAX_FRAMES = 4
    private const val DQUOTE = 0
    private const val SUBST = 1
    private const val BACKTICK = 2
    private const val PARAM = 3
    private const val CODE = -1
    private const val PAREN_SHIFT = 11
    private const val PAREN_MASK = 3 shl PAREN_SHIFT
    private const val LEAF_SHIFT = 13
    private const val LEAF_MASK = 3 shl LEAF_SHIFT
    private const val SINGLE = 1
    private const val ANSI = 2
    private const val CONTINUED = 1 shl 15

    // A heredoc never starts inside `$(( ))`, so its state keeps only the depth and frames.
    private const val STACK_MASK = 0x7FF

    private const val HEREDOC = 1 shl 31
    private const val STRIP = 1 shl 30
    private const val QUOTED = 1 shl 29
    private const val HASH_SHIFT = 11
    private const val HASH_BITS = 18

    /** Every non-heredoc state is below this; [DockerfileLexer] packs its own bits above. */
    const val PLAIN_STATE_LIMIT = 1 shl 16

    /** The bits of a heredoc state that hold the stack to resume after the body. */
    const val HEREDOC_STACK = STACK_MASK

    // Word kinds.
    private const val RESERVED = 1
    private const val STARTS_COMMAND = 2
    private const val LOOP = 4
    private const val CASE = 8
    private const val FUNCTION = 16
    private const val DECLARE = 32

    private val words = LexerWords()

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        if (state and HEREDOC != 0) return heredocLine(text, start, end, state, out)
        var st = state and CONTINUED.inv()
        var index = start
        var command = state and CONTINUED == 0
        var wordStart = true
        var heredoc = 0
        var continued = false
        var declaring = false
        var expectIn = false
        var nameRole = 0
        if (state == 0 && end - start > 1 && text[start] == '$' && text[start + 1] == ' ') {
            // A terminal prompt (`$ npm test`), as in console snippets.
            out.span(start, start + 1, RoleCode.PUNCTUATION)
            index = start + 2
        }
        while (index < end && !out.stopped) {
            val leaf = (st and LEAF_MASK) ushr LEAF_SHIFT
            if (leaf != 0) {
                val close = if (leaf == SINGLE) singleQuoted(text, index, end, out) else ansiQuoted(text, index, end, out)
                if (close < 0) {
                    index = end
                } else {
                    index = close
                    st = st and LEAF_MASK.inv()
                }
                continue
            }
            val depth = st and DEPTH_MASK
            val top = if (depth == 0) CODE else frame(st, depth - 1)
            val char = text[index]
            if (top == DQUOTE) {
                when (char) {
                    '"' -> {
                        out.span(index, index + 1, RoleCode.STRING)
                        st = pop(st)
                        index += 1
                    }
                    '\\' -> {
                        if (index + 1 < end && isDoubleQuoteEscape(text[index + 1])) {
                            out.span(index, index + 2, RoleCode.CONSTANT)
                            index += 2
                        } else {
                            out.span(index, index + 1, RoleCode.STRING)
                            index += 1
                        }
                    }
                    '$' -> {
                        val result = expansion(text, index, end, st, true, out)
                        val next = (result ushr 32).toInt()
                        if (entersCommand(st, next)) {
                            command = next and PAREN_MASK == 0
                            wordStart = true
                        }
                        st = next
                        index = result.toInt()
                    }
                    '`' -> {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        if (depth < MAX_FRAMES) st = push(st, BACKTICK, 0)
                        command = true
                        wordStart = true
                        index += 1
                    }
                    else -> {
                        var after = index + 1
                        while (after < end && !isDoubleQuoteSpecial(text[after])) after += 1
                        out.span(index, after, RoleCode.STRING)
                        index = after
                    }
                }
                continue
            }
            if (top == PARAM) {
                when (char) {
                    '}' -> {
                        out.span(index, index + 1, RoleCode.VARIABLE)
                        st = pop(st)
                        index += 1
                    }
                    '"' -> {
                        if (depth < MAX_FRAMES) {
                            out.span(index, index + 1, RoleCode.STRING)
                            st = push(st, DQUOTE, 0)
                            index += 1
                        } else {
                            index = inlineDoubleQuoted(text, index, end, out)
                        }
                    }
                    '\'' -> {
                        out.span(index, index + 1, RoleCode.STRING)
                        st = st or (SINGLE shl LEAF_SHIFT)
                        index += 1
                    }
                    '$' -> {
                        val result = expansion(text, index, end, st, false, out)
                        val next = (result ushr 32).toInt()
                        if (entersCommand(st, next)) {
                            command = next and PAREN_MASK == 0
                            wordStart = true
                        }
                        st = next
                        index = result.toInt()
                    }
                    '`' -> {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        if (depth < MAX_FRAMES) st = push(st, BACKTICK, 0)
                        command = true
                        wordStart = true
                        index += 1
                    }
                    '\\' -> {
                        val after = if (index + 1 < end) index + 2 else index + 1
                        out.span(index, after, RoleCode.CONSTANT)
                        index = after
                    }
                    else -> {
                        var after = index + 1
                        while (after < end && !isParamSpecial(text[after])) after += 1
                        out.span(index, after, RoleCode.VARIABLE)
                        index = after
                    }
                }
                continue
            }
            // Code: the top level, `$( )`, `$(( ))`, or backticks.
            val arithmetic = top == SUBST && (st and PAREN_MASK) != 0
            when {
                char == ' ' || char == '\t' -> {
                    index += 1
                    wordStart = true
                }
                char == '#' && wordStart && !arithmetic -> {
                    out.span(index, end, RoleCode.COMMENT)
                    index = end
                }
                char == '\'' -> {
                    out.span(index, index + 1, RoleCode.STRING)
                    st = st or (SINGLE shl LEAF_SHIFT)
                    index += 1
                    if (wordStart) command = false
                    wordStart = false
                }
                char == '"' -> {
                    if (depth < MAX_FRAMES) {
                        out.span(index, index + 1, RoleCode.STRING)
                        st = push(st, DQUOTE, 0)
                        index += 1
                    } else {
                        index = inlineDoubleQuoted(text, index, end, out)
                    }
                    if (wordStart) command = false
                    wordStart = false
                }
                char == '`' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    if (top == BACKTICK) {
                        st = pop(st)
                        wordStart = false
                    } else {
                        if (depth < MAX_FRAMES) st = push(st, BACKTICK, 0)
                        command = true
                        wordStart = true
                    }
                    index += 1
                }
                char == '$' -> {
                    val result = expansion(text, index, end, st, false, out)
                    val next = (result ushr 32).toInt()
                    if (entersCommand(st, next)) {
                        // A command substitution starts a command of its own.
                        command = next and PAREN_MASK == 0
                        wordStart = true
                    } else {
                        if (wordStart) command = false
                        wordStart = false
                    }
                    st = next
                    index = result.toInt()
                }
                char == ')' -> {
                    if (top == SUBST) {
                        val parens = (st and PAREN_MASK) ushr PAREN_SHIFT
                        if (parens == 0) {
                            out.span(index, index + 1, RoleCode.PUNCTUATION)
                            st = pop(st)
                            index += 1
                            wordStart = false
                            command = false
                        } else if (parens == 1 && index + 1 < end && text[index + 1] == ')') {
                            out.span(index, index + 2, RoleCode.PUNCTUATION)
                            st = pop(st)
                            index += 2
                            wordStart = false
                            command = false
                        } else {
                            out.span(index, index + 1, RoleCode.PUNCTUATION)
                            st -= 1 shl PAREN_SHIFT
                            index += 1
                        }
                    } else {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                        command = true
                        wordStart = true
                    }
                }
                char == '(' -> {
                    if (arithmetic) {
                        if ((st and PAREN_MASK) != PAREN_MASK) st += 1 shl PAREN_SHIFT
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                    } else if (command && index + 1 < end && text[index + 1] == '(' && depth < MAX_FRAMES) {
                        // `(( expr ))`: an arithmetic command, lexed like `$(( ))`.
                        out.span(index, index + 2, RoleCode.PUNCTUATION)
                        st = push(st, SUBST, 1)
                        index += 2
                        command = false
                    } else {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                        command = true
                    }
                    wordStart = true
                }
                char == '|' || char == '&' || char == ';' -> {
                    var after = index + 1
                    if (char == '&' && after < end && text[after] == '>') {
                        // `&>` and `&>>` redirect both streams.
                        after += 1
                        if (after < end && text[after] == '>') after += 1
                        out.span(index, after, RoleCode.OPERATOR)
                        command = false
                    } else {
                        while (after < end && after < index + 3 && (text[after] == '|' || text[after] == '&' || text[after] == ';')) after += 1
                        out.span(index, after, RoleCode.OPERATOR)
                        command = true
                        declaring = false
                        expectIn = false
                    }
                    index = after
                    wordStart = true
                }
                char == '<' && !arithmetic && index + 1 < end && text[index + 1] == '<' -> {
                    if (index + 2 < end && text[index + 2] == '<') {
                        out.span(index, index + 3, RoleCode.OPERATOR)
                        index += 3
                    } else {
                        val result = heredocStart(text, index, end, out)
                        index = result.toInt()
                        if (heredoc == 0) heredoc = (result ushr 32).toInt()
                    }
                    command = false
                    wordStart = true
                }
                char == '<' || char == '>' -> {
                    var after = index + 1
                    while (after < end && after < index + 3 && (text[after] == '>' || text[after] == '&' || text[after] == '|' || text[after] == '<')) after += 1
                    out.span(index, after, RoleCode.OPERATOR)
                    index = after
                    if (!arithmetic) command = false
                    wordStart = true
                }
                arithmetic -> {
                    index = arithmeticToken(text, index, end, out)
                    wordStart = true
                }
                else -> {
                    // A word: up to a blank, an operator, a quote, or an expansion.
                    var after = index
                    while (after < end) {
                        val part = text[after]
                        if (part == '\\') {
                            if (after + 1 >= end) {
                                continued = true
                                break
                            }
                            after += 2
                            continue
                        }
                        if (isMeta(part)) break
                        after += 1
                    }
                    if (after == index) {
                        // A lone trailing backslash.
                        index += 1
                        continue
                    }
                    // Words run in the state-only pass too: command position decides whether `((` opens arithmetic.
                    if (!wordStart) {
                        // The rest of a word glued to a quote or expansion: plain.
                    } else {
                        val assignment = assignmentEnd(text, index, after)
                        if (assignment > 0 && (command || declaring)) {
                            val operator = if (text[assignment - 2] == '+') assignment - 2 else assignment - 1
                            out.span(index, operator, RoleCode.VARIABLE)
                            out.span(operator, assignment, RoleCode.OPERATOR)
                        } else if (command) {
                            val kind = words.lookup(text, index, after)
                            if (kind and RESERVED != 0) {
                                out.span(index, after, RoleCode.KEYWORD)
                                command = kind and STARTS_COMMAND != 0
                                if (kind and LOOP != 0) nameRole = RoleCode.VARIABLE
                                if (kind and FUNCTION != 0) nameRole = RoleCode.FUNCTION
                                if (kind and (LOOP or CASE) != 0) expectIn = true
                            } else if (isBraceWord(text, index, after)) {
                                out.span(index, after, RoleCode.PUNCTUATION)
                                command = text[index] == '{'
                            } else if (isOperatorWord(text, index, after)) {
                                out.span(index, after, RoleCode.OPERATOR)
                            } else {
                                if (kind and DECLARE != 0) {
                                    out.span(index, after, RoleCode.KEYWORD)
                                    declaring = true
                                } else if (after >= end || text[after] != ')' || top == SUBST) {
                                    // A case pattern (`start)`) is not a command.
                                    out.span(index, after, RoleCode.FUNCTION)
                                }
                                command = false
                            }
                        } else {
                            classifyArgument(text, index, after, nameRole, expectIn, out)
                            if (expectIn && LexerScan.equals(text, index, after, "in")) expectIn = false
                            nameRole = 0
                        }
                    }
                    index = if (continued) end else after
                    wordStart = false
                }
            }
        }
        if (out.stopped) return st
        if (heredoc != 0) return heredoc or (st and STACK_MASK)
        val depth = st and DEPTH_MASK
        val codeOnTop = depth == 0 || frame(st, depth - 1) == SUBST || frame(st, depth - 1) == BACKTICK
        return if (continued && codeOnTop && st and LEAF_MASK == 0) st or CONTINUED else st
    }

    /** Colors an argument word: a pending loop variable or function name, an option, a number, or `in`. */
    private fun classifyArgument(
        text: CharArray,
        from: Int,
        to: Int,
        nameRole: Int,
        expectIn: Boolean,
        out: SpanSink,
    ) {
        val first = text[from]
        when {
            nameRole != 0 -> out.span(from, to, nameRole)
            expectIn && LexerScan.equals(text, from, to, "in") -> out.span(from, to, RoleCode.KEYWORD)
            first == '-' && to - from > 1 && !(to - from == 2 && text[from + 1] == '-') -> {
                var stop = from + 1
                while (stop < to && text[stop] != '=') stop += 1
                out.span(from, stop, RoleCode.CONSTANT)
            }
            isDigit(first) && allDigits(text, from, to) -> out.span(from, to, RoleCode.NUMBER)
            isBraceWord(text, from, to) -> out.span(from, to, if (to - from == 2) RoleCode.KEYWORD else RoleCode.PUNCTUATION)
            isOperatorWord(text, from, to) -> out.span(from, to, RoleCode.OPERATOR)
        }
    }

    /**
     * Lexes a `$` expansion at [dollar]; returns the new state above the offset after the
     * part this call consumed. [inString] is true inside double quotes, where `$'` and `$"`
     * are literal.
     */
    private fun expansion(
        text: CharArray,
        dollar: Int,
        end: Int,
        state: Int,
        inString: Boolean,
        out: SpanSink,
    ): Long {
        val plainRole = if (inString) RoleCode.STRING else 0
        if (dollar + 1 >= end) {
            if (plainRole != 0) out.span(dollar, dollar + 1, plainRole)
            return packed(state, dollar + 1)
        }
        val next = text[dollar + 1]
        val depth = state and DEPTH_MASK
        return when {
            next == '{' -> {
                // A `${...}` that closes on this line with nothing nested colors in one go.
                var index = dollar + 2
                while (index < end && !isParamSpecial(text[index])) index += 1
                if (index < end && text[index] == '}') {
                    out.span(dollar, index + 1, RoleCode.VARIABLE)
                    packed(state, index + 1)
                } else {
                    out.span(dollar, dollar + 2, RoleCode.VARIABLE)
                    packed(if (depth < MAX_FRAMES) push(state, PARAM, 0) else state, dollar + 2)
                }
            }
            next == '(' -> {
                val arithmetic = dollar + 2 < end && text[dollar + 2] == '('
                val after = if (arithmetic) dollar + 3 else dollar + 2
                out.span(dollar, after, RoleCode.PUNCTUATION)
                packed(if (depth < MAX_FRAMES) push(state, SUBST, if (arithmetic) 1 else 0) else state, after)
            }
            next == '\'' && !inString -> {
                out.span(dollar, dollar + 2, RoleCode.STRING)
                packed(state or (ANSI shl LEAF_SHIFT), dollar + 2)
            }
            next == '"' && !inString -> {
                if (depth < MAX_FRAMES) {
                    out.span(dollar, dollar + 2, RoleCode.STRING)
                    packed(push(state, DQUOTE, 0), dollar + 2)
                } else {
                    out.span(dollar, dollar + 1, RoleCode.STRING)
                    packed(state, inlineDoubleQuoted(text, dollar + 1, end, out))
                }
            }
            isNameStart(next) -> {
                var index = dollar + 2
                while (index < end && isNamePart(text[index])) index += 1
                out.span(dollar, index, RoleCode.VARIABLE)
                packed(state, index)
            }
            isDigit(next) || isSpecialParameter(next) -> {
                out.span(dollar, dollar + 2, RoleCode.VARIABLE)
                packed(state, dollar + 2)
            }
            else -> {
                if (plainRole != 0) out.span(dollar, dollar + 1, plainRole)
                packed(state, dollar + 1)
            }
        }
    }

    /**
     * Lexes `<<` or `<<-` and the delimiter word after it; returns the heredoc bits (0 when
     * there is no word) above the offset after the word.
     */
    private fun heredocStart(
        text: CharArray,
        at: Int,
        end: Int,
        out: SpanSink,
    ): Long {
        var index = at + 2
        var strip = false
        if (index < end && text[index] == '-') {
            strip = true
            index += 1
        }
        out.span(at, index, RoleCode.OPERATOR)
        index = LexerScan.skipBlanks(text, index, end)
        val wordStart = index
        var hash = LexerDelimiter.SEED
        var length = 0
        var quoted = false
        var quote = ' '
        while (index < end) {
            val char = text[index]
            if (quote != ' ') {
                if (char == quote) {
                    quote = ' '
                } else {
                    hash = LexerDelimiter.add(hash, char)
                    length += 1
                }
                index += 1
                continue
            }
            if (char == '\'' || char == '"') {
                quote = char
                quoted = true
                index += 1
                continue
            }
            if (char == '\\' && index + 1 < end) {
                quoted = true
                hash = LexerDelimiter.add(hash, text[index + 1])
                length += 1
                index += 2
                continue
            }
            if (char == ' ' || char == '\t' || char == '|' || char == '&' || char == ';' || char == '(' || char == ')' || char == '<' || char == '>' || char == '`') break
            hash = LexerDelimiter.add(hash, char)
            length += 1
            index += 1
        }
        if (index == wordStart || (length == 0 && !quoted)) return packed(0, index)
        out.span(wordStart, index, RoleCode.KEYWORD)
        var bits = HEREDOC or (LexerDelimiter.finish(hash, length, HASH_BITS) shl HASH_SHIFT)
        if (strip) bits = bits or STRIP
        if (quoted) bits = bits or QUOTED
        return packed(bits, index)
    }

    /** A heredoc body line, or the line that ends it. */
    private fun heredocLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        var from = start
        if (state and STRIP != 0) {
            while (from < end && text[from] == '\t') from += 1
        }
        val hash = LexerDelimiter.of(text, from, end, HASH_BITS)
        if (hash == (state ushr HASH_SHIFT) and ((1 shl HASH_BITS) - 1)) {
            out.span(from, end, RoleCode.KEYWORD)
            return state and STACK_MASK
        }
        if (state and QUOTED != 0) {
            out.span(start, end, RoleCode.STRING)
            return state
        }
        var index = start
        var segment = start
        var substitutions = true
        while (index < end && !out.stopped) {
            val char = text[index]
            if (char == '\\' && index + 1 < end && (text[index + 1] == '$' || text[index + 1] == '`' || text[index + 1] == '\\')) {
                out.span(segment, index, RoleCode.STRING)
                out.span(index, index + 2, RoleCode.CONSTANT)
                index += 2
                segment = index
            } else if (char == '$' && substitutions && index + 1 < end && text[index + 1] == '(') {
                val close = matchingParen(text, index + 2, end)
                if (close < 0) {
                    // Unclosed on this line: stop looking, so a line of `$(` stays linear.
                    substitutions = false
                    index += 1
                    continue
                }
                // A command substitution in the body: its command colors as code.
                out.span(segment, index, RoleCode.STRING)
                out.span(index, index + 2, RoleCode.PUNCTUATION)
                lexLine(text, index + 2, close, 0, out)
                out.span(close, close + 1, RoleCode.PUNCTUATION)
                index = close + 1
                segment = index
            } else if (char == '$' && index + 1 < end && text[index + 1] == '{') {
                var close = index + 2
                while (close < end && text[close] != '}') close += 1
                out.span(segment, index, RoleCode.STRING)
                out.span(index, minOf(close + 1, end), RoleCode.VARIABLE)
                index = minOf(close + 1, end)
                segment = index
            } else if (char == '$' && index + 1 < end && (isNameStart(text[index + 1]) || isDigit(text[index + 1]) || isSpecialParameter(text[index + 1]))) {
                var after = index + 2
                if (isNameStart(text[index + 1])) {
                    while (after < end && isNamePart(text[after])) after += 1
                }
                out.span(segment, index, RoleCode.STRING)
                out.span(index, after, RoleCode.VARIABLE)
                index = after
                segment = index
            } else {
                index += 1
            }
        }
        out.span(segment, end, RoleCode.STRING)
        return state
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

    /** A token inside `$(( ))`: numbers, names, and operators. */
    private fun arithmeticToken(
        text: CharArray,
        at: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        val char = text[at]
        if (isDigit(char)) {
            var after = at + 1
            while (after < end && (isNamePart(text[after]) || text[after] == '#')) after += 1
            out.span(at, after, RoleCode.NUMBER)
            return after
        }
        if (isNameStart(char)) {
            var after = at + 1
            while (after < end && isNamePart(text[after])) after += 1
            if (!out.collecting) out.span(at, after, RoleCode.VARIABLE)
            return after
        }
        if (char == '\\') return if (at + 1 < end) at + 2 else at + 1
        if (isArithmeticOperator(char)) out.span(at, at + 1, RoleCode.OPERATOR)
        return at + 1
    }

    /** Single-quoted text from [from]: the offset after the closing quote, or -1 when it runs past the line. */
    private fun singleQuoted(
        text: CharArray,
        from: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        var index = from
        while (index < end && text[index] != '\'') index += 1
        if (index >= end) {
            out.span(from, end, RoleCode.STRING)
            return -1
        }
        out.span(from, index + 1, RoleCode.STRING)
        return index + 1
    }

    /** `$'...'` text from [from], with backslash escapes as Constant; like [singleQuoted]. */
    private fun ansiQuoted(
        text: CharArray,
        from: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        var segment = from
        var index = from
        while (index < end) {
            val char = text[index]
            if (char == '\'') {
                out.span(segment, index + 1, RoleCode.STRING)
                return index + 1
            }
            if (char == '\\' && index + 1 < end) {
                out.span(segment, index, RoleCode.STRING)
                out.span(index, index + 2, RoleCode.CONSTANT)
                index += 2
                segment = index
            } else {
                index += 1
            }
        }
        out.span(segment, end, RoleCode.STRING)
        return -1
    }

    /** A double-quoted string too deep for the stack: String up to its close on this line. */
    private fun inlineDoubleQuoted(
        text: CharArray,
        quote: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        var index = quote + 1
        while (index < end && text[index] != '"') index += if (text[index] == '\\') 2 else 1
        val after = minOf(index + 1, end)
        out.span(quote, after, RoleCode.STRING)
        return after
    }

    /** The offset after `=` or `+=` when `text[from, to)` starts an assignment (`NAME=`, `NAME[i]=`), else 0. */
    private fun assignmentEnd(
        text: CharArray,
        from: Int,
        to: Int,
    ): Int {
        if (!isNameStart(text[from])) return 0
        var index = from + 1
        while (index < to && isNamePart(text[index])) index += 1
        if (index < to && text[index] == '[') {
            while (index < to && text[index] != ']') index += 1
            index += 1
        }
        if (index < to && text[index] == '+') index += 1
        return if (index < to && text[index] == '=') index + 1 else 0
    }

    /** True when [next] pushed a `$( )` or `$(( ))` frame onto [state]. */
    private fun entersCommand(
        state: Int,
        next: Int,
    ): Boolean {
        val depth = state and DEPTH_MASK
        return next and DEPTH_MASK > depth && frame(next, depth) == SUBST
    }

    private fun frame(
        state: Int,
        level: Int,
    ): Int = (state ushr (FRAME_SHIFT + 2 * level)) and 3

    private fun push(
        state: Int,
        kind: Int,
        parens: Int,
    ): Int {
        val depth = state and DEPTH_MASK
        return (state and (DEPTH_MASK or PAREN_MASK).inv()) or (kind shl (FRAME_SHIFT + 2 * depth)) or (depth + 1) or (parens shl PAREN_SHIFT)
    }

    private fun pop(state: Int): Int {
        val depth = state and DEPTH_MASK
        return (state and (DEPTH_MASK or PAREN_MASK or (3 shl (FRAME_SHIFT + 2 * (depth - 1)))).inv()) or (depth - 1)
    }

    private fun packed(
        state: Int,
        offset: Int,
    ): Long = (state.toLong() shl 32) or offset.toLong()

    private fun isMeta(char: Char): Boolean =
        when (char) {
            ' ', '\t', '|', '&', ';', '(', ')', '<', '>', '\'', '"', '`', '$' -> true
            else -> false
        }

    private fun isDoubleQuoteSpecial(char: Char): Boolean = char == '"' || char == '\\' || char == '$' || char == '`'

    private fun isDoubleQuoteEscape(char: Char): Boolean = char == '"' || char == '\\' || char == '$' || char == '`'

    private fun isParamSpecial(char: Char): Boolean = char == '}' || char == '"' || char == '\'' || char == '$' || char == '`' || char == '\\'

    private fun isSpecialParameter(char: Char): Boolean = char == '@' || char == '*' || char == '#' || char == '?' || char == '-' || char == '$' || char == '!'

    private fun isNameStart(char: Char): Boolean = isAsciiLetter(char) || char == '_'

    private fun isNamePart(char: Char): Boolean = isAsciiLetter(char) || isDigit(char) || char == '_'

    private fun isArithmeticOperator(char: Char): Boolean =
        when (char) {
            '+', '-', '*', '/', '%', '=', '!', '~', '^', '?', ':', ',' -> true
            else -> false
        }

    private fun allDigits(
        text: CharArray,
        from: Int,
        to: Int,
    ): Boolean {
        for (index in from until to) if (!isDigit(text[index])) return false
        return true
    }

    /** `{`, `}`, `[`, `]`, `[[`, `]]` as words of their own. */
    private fun isBraceWord(
        text: CharArray,
        from: Int,
        to: Int,
    ): Boolean {
        val length = to - from
        val first = text[from]
        if (length == 1) return first == '{' || first == '}' || first == '[' || first == ']'
        return length == 2 && (first == '[' || first == ']') && text[from + 1] == first
    }

    /** `=`, `==`, `!=`, `=~` and `!` as words of their own, as in `[[ a == b ]]`. */
    private fun isOperatorWord(
        text: CharArray,
        from: Int,
        to: Int,
    ): Boolean {
        val length = to - from
        if (length > 2) return false
        for (index in from until to) {
            val char = text[index]
            if (char != '=' && char != '!' && char != '~') return false
        }
        return true
    }

    init {
        words.register(RESERVED or STARTS_COMMAND, "if", "then", "else", "elif", "do", "while", "until", "time", "!", "coproc")
        words.register(RESERVED, "fi", "done", "esac", "in", "break", "continue", "return", "exit")
        words.register(RESERVED or LOOP, "for", "select")
        words.register(RESERVED or CASE, "case")
        words.register(RESERVED or FUNCTION, "function")
        words.register(RESERVED, "[[", "]]")
        words.register(DECLARE, "export", "local", "declare", "readonly", "typeset")
    }
}
