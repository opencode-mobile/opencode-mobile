package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit

/**
 * Dockerfile (and Containerfile).
 *
 * Roles: instructions (`FROM`, `RUN`, ..., any case) and `AS`, `CMD`/`NONE` after
 * `HEALTHCHECK` are Keyword; comments are Comment; a parser directive at the top (`# syntax=`,
 * `# escape=`, `# check=`) has its name as Keyword, `=` as Operator, and its value as String.
 * The shell form of `RUN`, `CMD`, and `ENTRYPOINT` colors as [ShellLexer] code, heredocs
 * (`RUN <<EOF`) included; their exec form and other arguments color strings as String, `[` `]`
 * `,` as Punctuation, `$VAR` and `${VAR}` as Variable, `--flags` up to `=` as Constant, and
 * all-digit words as Number. `ENV`, `ARG`, and `LABEL` keys are Variable with `=` as Operator.
 *
 * States: 0 at the top, where parser directives may appear; [HEADER_DONE] once any other line
 * was read. A line ending in the escape character continues the instruction: bits 16-17 hold
 * what continues ([SHELL] with the shell state in bits 0-15, [ARGS], or [KEY_VALUES]), and
 * comment lines inside it are skipped as Docker does. Bit 19 records `# escape=`` ` (backtick
 * escape). Inside a `RUN` heredoc body the state is the shell's heredoc state (bit 31 set); the
 * instruction ends with the body.
 *
 * Known gaps: `COPY <<EOF` bodies lex as instructions; the backtick escape only affects line
 * continuation, not the shell lexing; JSON exec form is not validated.
 */
internal object DockerfileLexer : HighlightLexer() {
    private const val KIND_SHIFT = 16
    private const val KIND_MASK = 3 shl KIND_SHIFT
    private const val SHELL = 1
    private const val ARGS = 2
    private const val KEY_VALUES = 3
    private const val HEADER_DONE = 1 shl 18
    private const val BACKTICK_ESCAPE = 1 shl 19
    private const val SHELL_MASK = ShellLexer.PLAIN_STATE_LIMIT - 1

    // Parser directive outcomes.
    private const val KEEP_ESCAPE = 1
    private const val BACKTICK = 2
    private const val BACKSLASH = 3

    // Instruction kinds.
    private const val INSTRUCTION = 1
    private const val SHELL_FORM = 2
    private const val KEYS = 4
    private const val ONBUILD = 8
    private const val HEALTHCHECK = 16
    private const val FROM = 32

    private val instructions = LexerWords(caseInsensitive = true)

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        if (state < 0) {
            // A `RUN <<EOF` body: the instruction ends with it.
            val after = ShellLexer.lexLine(text, start, end, state, out)
            return if (after < 0 || out.stopped) after else HEADER_DONE
        }
        val escape = if (state and BACKTICK_ESCAPE != 0) '`' else '\\'
        val base = (state and BACKTICK_ESCAPE) or HEADER_DONE
        val first = LexerScan.skipBlanks(text, start, end)
        val kind = (state and KIND_MASK) ushr KIND_SHIFT
        val continues = continues(text, first, end, escape)
        if (kind != 0) {
            if (first >= end) return state
            if (text[first] == '#') {
                out.span(first, end, RoleCode.COMMENT)
                return state
            }
            return when (kind) {
                SHELL -> shell(text, first, end, state and SHELL_MASK, continues, base, out)
                KEY_VALUES -> {
                    keyValues(text, first, end, out)
                    if (continues) base or (KEY_VALUES shl KIND_SHIFT) else base
                }
                else -> {
                    arguments(text, first, end, false, out)
                    if (continues) base or (ARGS shl KIND_SHIFT) else base
                }
            }
        }
        if (first >= end) return base
        if (text[first] == '#') {
            if (state and HEADER_DONE == 0) {
                when (directive(text, first, end, out)) {
                    KEEP_ESCAPE -> return state
                    BACKTICK -> return BACKTICK_ESCAPE
                    BACKSLASH -> return 0
                }
            }
            out.span(first, end, RoleCode.COMMENT)
            return base
        }
        var index = first
        var onbuild = true
        while (index < end && !out.stopped) {
            var wordEnd = index
            while (wordEnd < end && isAsciiLetter(text[wordEnd])) wordEnd += 1
            val instruction = instructions.lookup(text, index, wordEnd)
            if (instruction == 0 || (wordEnd < end && text[wordEnd] != ' ' && text[wordEnd] != '\t')) {
                arguments(text, index, end, false, out)
                return if (continues) base or (ARGS shl KIND_SHIFT) else base
            }
            out.span(index, wordEnd, RoleCode.KEYWORD)
            index = LexerScan.skipBlanks(text, wordEnd, end)
            if (instruction and ONBUILD != 0 && onbuild) {
                // `ONBUILD RUN ...`: one more instruction follows.
                onbuild = false
                continue
            }
            if (instruction and HEALTHCHECK != 0) {
                index = flags(text, index, end, out)
                var next = index
                while (next < end && isAsciiLetter(text[next])) next += 1
                if (equalsIgnoringCase(text, index, next, "cmd")) {
                    out.span(index, next, RoleCode.KEYWORD)
                    return shellOrExec(text, LexerScan.skipBlanks(text, next, end), end, continues, base, out)
                }
                if (equalsIgnoringCase(text, index, next, "none")) out.span(index, next, RoleCode.KEYWORD)
                arguments(text, next, end, false, out)
                return if (continues) base or (ARGS shl KIND_SHIFT) else base
            }
            if (instruction and SHELL_FORM != 0) {
                return shellOrExec(text, flags(text, index, end, out), end, continues, base, out)
            }
            if (instruction and KEYS != 0) {
                keyValues(text, index, end, out)
                return if (continues) base or (KEY_VALUES shl KIND_SHIFT) else base
            }
            arguments(text, index, end, instruction and FROM != 0, out)
            return if (continues) base or (ARGS shl KIND_SHIFT) else base
        }
        return base
    }

    /** `RUN`/`CMD`/`ENTRYPOINT` arguments from [from]: JSON exec form after `[`, else shell. */
    private fun shellOrExec(
        text: CharArray,
        from: Int,
        end: Int,
        continues: Boolean,
        base: Int,
        out: SpanSink,
    ): Int {
        if (from < end && text[from] == '[') {
            arguments(text, from, end, false, out)
            return if (continues) base or (ARGS shl KIND_SHIFT) else base
        }
        return shell(text, from, end, 0, continues, base, out)
    }

    private fun shell(
        text: CharArray,
        from: Int,
        end: Int,
        shellState: Int,
        continues: Boolean,
        base: Int,
        out: SpanSink,
    ): Int {
        val after = ShellLexer.lexLine(text, from, end, shellState, out)
        if (out.stopped) return after
        if (after < 0) return after and ShellLexer.HEREDOC_STACK.inv()
        return if (continues) base or (SHELL shl KIND_SHIFT) or (after and SHELL_MASK) else base
    }

    /** Skips `--flag=value` options, coloring each name as Constant; returns the offset after them. */
    private fun flags(
        text: CharArray,
        from: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        var index = from
        while (index + 1 < end && text[index] == '-' && text[index + 1] == '-') {
            var name = index + 2
            while (name < end && text[name] != '=' && text[name] != ' ' && text[name] != '\t') name += 1
            out.span(index, name, RoleCode.CONSTANT)
            var after = name
            while (after < end && text[after] != ' ' && text[after] != '\t') after += 1
            index = LexerScan.skipBlanks(text, after, end)
        }
        return index
    }

    /** Generic arguments: strings, `$` variables, flags, numbers, and JSON punctuation; `AS` after [from]. */
    private fun arguments(
        text: CharArray,
        from: Int,
        end: Int,
        afterFrom: Boolean,
        out: SpanSink,
    ) {
        var index = from
        var wordStart = true
        while (index < end && !out.stopped) {
            val char = text[index]
            when {
                char == ' ' || char == '\t' -> {
                    index += 1
                    wordStart = true
                }
                char == '"' || char == '\'' -> {
                    index = quoted(text, index, end, out)
                    wordStart = false
                }
                char == '[' || char == ']' || char == ',' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    wordStart = true
                }
                char == '$' -> {
                    index = variable(text, index, end, out)
                    wordStart = false
                }
                char == '\\' -> index += 2
                else -> {
                    var after = index + 1
                    while (after < end && !isArgumentStop(text[after])) after += 1
                    if (wordStart) {
                        when {
                            char == '-' && after - index > 2 && text[index + 1] == '-' -> {
                                var name = index
                                while (name < after && text[name] != '=') name += 1
                                out.span(index, name, RoleCode.CONSTANT)
                            }
                            afterFrom && after - index == 2 && (text[index] == 'A' || text[index] == 'a') && (text[index + 1] == 'S' || text[index + 1] == 's') ->
                                out.span(index, after, RoleCode.KEYWORD)
                            isDigit(char) && allDigits(text, index, after) -> out.span(index, after, RoleCode.NUMBER)
                        }
                    }
                    index = after
                    wordStart = false
                }
            }
        }
    }

    /** `ENV`, `ARG`, `LABEL`: `key=value` pairs, or the legacy `ENV key value`. */
    private fun keyValues(
        text: CharArray,
        from: Int,
        end: Int,
        out: SpanSink,
    ) {
        var index = from
        while (index < end && !out.stopped) {
            val char = text[index]
            when {
                char == ' ' || char == '\t' -> index += 1
                char == '"' || char == '\'' -> index = quoted(text, index, end, out)
                char == '$' -> index = variable(text, index, end, out)
                char == '\\' -> index += 2
                else -> {
                    var after = index + 1
                    while (after < end && !isArgumentStop(text[after]) && text[after] != '=') after += 1
                    if (after < end && text[after] == '=') {
                        out.span(index, after, RoleCode.VARIABLE)
                        out.span(after, after + 1, RoleCode.OPERATOR)
                        index = after + 1
                        if (index < end && text[index] != ' ' && text[index] != '\t') index = value(text, index, end, out)
                    } else if (index == from) {
                        // Legacy `ENV key value`: the rest of the line is the value.
                        out.span(index, after, RoleCode.VARIABLE)
                        arguments(text, after, end, false, out)
                        return
                    } else {
                        index = after
                    }
                }
            }
        }
    }

    /** One value after `key=`: quoted parts and variables up to a blank. */
    private fun value(
        text: CharArray,
        from: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        var index = from
        while (index < end) {
            val char = text[index]
            when {
                char == ' ' || char == '\t' -> return index
                char == '"' || char == '\'' -> index = quoted(text, index, end, out)
                char == '$' -> index = variable(text, index, end, out)
                char == '\\' -> index += 2
                else -> index += 1
            }
        }
        return end
    }

    /** A quoted string to its close on this line, with `$` variables inside double quotes. */
    private fun quoted(
        text: CharArray,
        open: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        val quote = text[open]
        var segment = open
        var index = open + 1
        while (index < end) {
            val char = text[index]
            if (char == quote) {
                out.span(segment, index + 1, RoleCode.STRING)
                return index + 1
            }
            if (char == '\\') {
                index += 2
            } else if (char == '$' && quote == '"' && index + 1 < end && (text[index + 1] == '{' || isNameStart(text[index + 1]))) {
                out.span(segment, index, RoleCode.STRING)
                index = variable(text, index, end, out)
                segment = index
            } else {
                index += 1
            }
        }
        out.span(segment, end, RoleCode.STRING)
        return end
    }

    /** `$NAME` or `${...}` as Variable; a lone `$` stays plain. */
    private fun variable(
        text: CharArray,
        dollar: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        if (dollar + 1 >= end) return end
        if (text[dollar + 1] == '{') {
            var close = dollar + 2
            while (close < end && text[close] != '}') close += 1
            val after = minOf(close + 1, end)
            out.span(dollar, after, RoleCode.VARIABLE)
            return after
        }
        if (!isNameStart(text[dollar + 1])) return dollar + 1
        var after = dollar + 2
        while (after < end && (isNameStart(text[after]) || isDigit(text[after]))) after += 1
        out.span(dollar, after, RoleCode.VARIABLE)
        return after
    }

    /**
     * A parser directive (`# name=value`): [KEEP_ESCAPE], or [BACKTICK] or [BACKSLASH] for an
     * `escape` directive; 0 when the line is a comment.
     */
    private fun directive(
        text: CharArray,
        hash: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        val name = LexerScan.skipBlanks(text, hash + 1, end)
        var nameEnd = name
        while (nameEnd < end && isAsciiLetter(text[nameEnd])) nameEnd += 1
        val equals = LexerScan.skipBlanks(text, nameEnd, end)
        if (equals >= end || text[equals] != '=') return 0
        val known = equalsIgnoringCase(text, name, nameEnd, "syntax") || equalsIgnoringCase(text, name, nameEnd, "escape") ||
            equalsIgnoringCase(text, name, nameEnd, "check")
        if (!known) return 0
        val value = LexerScan.skipBlanks(text, equals + 1, end)
        var valueEnd = end
        while (valueEnd > value && (text[valueEnd - 1] == ' ' || text[valueEnd - 1] == '\t')) valueEnd -= 1
        out.span(hash, hash + 1, RoleCode.COMMENT)
        out.span(name, nameEnd, RoleCode.KEYWORD)
        out.span(equals, equals + 1, RoleCode.OPERATOR)
        out.span(value, valueEnd, RoleCode.STRING)
        if (!equalsIgnoringCase(text, name, nameEnd, "escape")) return KEEP_ESCAPE
        return if (valueEnd - value == 1 && text[value] == '`') BACKTICK else BACKSLASH
    }

    /** True when the line's last non-blank character is the escape character. */
    private fun continues(
        text: CharArray,
        from: Int,
        end: Int,
        escape: Char,
    ): Boolean {
        var last = end
        while (last > from && (text[last - 1] == ' ' || text[last - 1] == '\t')) last -= 1
        return last > from && text[last - 1] == escape
    }

    private fun isArgumentStop(char: Char): Boolean = char == ' ' || char == '\t' || char == '"' || char == '\'' || char == '$' || char == ',' || char == ']'

    private fun isNameStart(char: Char): Boolean = isAsciiLetter(char) || char == '_'

    private fun allDigits(
        text: CharArray,
        from: Int,
        to: Int,
    ): Boolean {
        for (index in from until to) if (!isDigit(text[index])) return false
        return true
    }

    private fun equalsIgnoringCase(
        text: CharArray,
        from: Int,
        to: Int,
        word: String,
    ): Boolean {
        if (to - from != word.length) return false
        for (offset in word.indices) {
            if (text[from + offset].lowercaseChar() != word[offset]) return false
        }
        return true
    }

    init {
        instructions.register(INSTRUCTION, "ADD", "COPY", "EXPOSE", "MAINTAINER", "STOPSIGNAL", "USER", "VOLUME", "WORKDIR")
        instructions.register(INSTRUCTION or SHELL_FORM, "RUN", "CMD", "ENTRYPOINT", "SHELL")
        instructions.register(INSTRUCTION or KEYS, "ENV", "ARG", "LABEL")
        instructions.register(INSTRUCTION or FROM, "FROM")
        instructions.register(INSTRUCTION or ONBUILD, "ONBUILD")
        instructions.register(INSTRUCTION or HEALTHCHECK, "HEALTHCHECK")
    }
}
