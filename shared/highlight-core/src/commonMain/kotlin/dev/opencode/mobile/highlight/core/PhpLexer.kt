package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * PHP 8: a PHP file is HTML (lexed by [HtmlLexer]) with PHP islands from `<?php`, `<?=`, or a
 * short `<?` to `?>`. Islands are found before HTML sees the text, so they work anywhere,
 * inside attribute values and `<script>` too, as in PHP itself. A text whose first non-blank
 * character is not `<` starts in PHP, so a snippet without `<?php` still colors as PHP.
 *
 * Roles: `<?`, `<?=`, `?>` are Punctuation and `php` Keyword. Inside PHP, variables (`$x`,
 * `$this`) are Variable; keywords and type names Keyword, with `and`, `or`, `xor` as Operator;
 * `true`, `false`, `null`, magic constants, and ALL_CAPS names Constant; a name after `function`
 * or before `(` Function; after `class`-like keywords, `new`, and `instanceof`, or capitalized,
 * Type; after `->` or `::` Property (Function when called). Strings are String with escapes
 * Constant; in `"…"`, backticks, and heredocs, `$name`, `$name->prop`, `$name[key]`, and
 * `{$…}` / `${…}` interpolate (braces Punctuation, the expression colored as code). Comments
 * (`#`, `//`, `/* */`) are Comment; `#[` and its `]` Punctuation; numbers Number; operators
 * Operator; brackets, `,`, `;` Punctuation.
 *
 * States: the file mode (not yet known, HTML, or PHP) with the PHP state, joined through
 * [SpanSink.embedded] with the HTML state (kept frozen during an island, so a `<script>` with
 * an island inside resumes as JavaScript). The PHP state holds an open block comment, a string
 * of any quote (PHP strings span lines), or a heredoc or nowdoc with a 24-bit hash of its label,
 * so a different label of the same hash also closes it. Keywords match case-insensitively up to
 * [MAX_WORD] characters.
 */
internal object PhpLexer : HighlightLexer() {
    const val UNDECIDED = 0
    const val HTML = 1
    const val PHP = 2
    const val FILE_MODE_MASK = 3
    const val PHP_SHIFT = 2

    const val CODE = 0
    const val BLOCK_COMMENT = 1
    const val DOUBLE = 2
    const val SINGLE = 3
    const val HEREDOC = 4
    const val NOWDOC = 5
    const val BACKTICK = 6
    const val MODE_MASK = 7
    const val LABEL_SHIFT = 3
    const val LABEL_MASK = 0xFFFFFF

    const val MAX_WORD = 16

    // Word kinds: a role code in the low byte plus flags.
    const val DECLARES_FUNCTION = 1 shl 8
    const val DECLARES_TYPE = 1 shl 9

    val words: WordTable =
        WordTable(
            buildMap {
                fun add(
                    value: Int,
                    names: String,
                ) = names.split(' ').forEach { put(it, value) }
                add(
                    RoleCode.KEYWORD,
                    "abstract as break callable case catch clone const continue declare default do echo else elseif empty " +
                        "enddeclare endfor endforeach endif endswitch endwhile eval exit die final finally for foreach global " +
                        "goto if include include_once insteadof isset list match namespace or print private protected public " +
                        "readonly require require_once return static switch throw try unset use var while yield from " +
                        "int float bool string array object mixed void never iterable self parent",
                )
                add(RoleCode.KEYWORD or DECLARES_FUNCTION, "function fn")
                add(RoleCode.KEYWORD or DECLARES_TYPE, "class interface trait enum extends implements new instanceof")
                add(RoleCode.OPERATOR, "and or xor")
                add(
                    RoleCode.CONSTANT,
                    "true false null __class__ __dir__ __file__ __function__ __line__ __method__ __namespace__ __trait__ " +
                        "__compiler_halt_offset__",
                )
            },
        )

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int = PhpLine(text, start, end, out).run(state)

    /** A hash of a heredoc label, [LABEL_MASK] bits. */
    fun labelHash(
        text: CharArray,
        from: Int,
        to: Int,
    ): Int {
        var hash = to - from
        for (index in from until to) hash = hash * 31 + text[index].code
        return (hash xor (hash ushr 24)) and LABEL_MASK
    }

    fun isNameStart(char: Char): Boolean = isAsciiLetter(char) || char == '_' || char.code >= 0x80

    fun isNamePart(char: Char): Boolean = isNameStart(char) || isDigit(char)
}

/** One line of a PHP file; a new one per line keeps [PhpLexer] stateless. */
private class PhpLine(
    private val text: CharArray,
    private val lineStart: Int,
    private val end: Int,
    private val out: SpanSink,
) {
    private var index = lineStart
    private var fileMode = PhpLexer.UNDECIDED
    private var html = 0
    private var mode = PhpLexer.CODE
    private var label = 0

    // Per line only: what the previous code token makes of the next name, and whether code after
    // a heredoc's label is being lexed (where another `<<<` is an operator, so nothing recurses).
    private var pending = NONE
    private var openingHeredoc = false
    private val folded = CharArray(PhpLexer.MAX_WORD)

    fun run(state: Int): Int {
        val host = out.embedded.host(state)
        html = out.embedded.inner(state)
        fileMode = host and PhpLexer.FILE_MODE_MASK
        val php = host ushr PhpLexer.PHP_SHIFT
        mode = php and PhpLexer.MODE_MASK
        label = (php ushr PhpLexer.LABEL_SHIFT) and PhpLexer.LABEL_MASK
        if (fileMode == PhpLexer.UNDECIDED) {
            val first = LexerScan.skipBlanks(text, index, end)
            if (first >= end) return state
            fileMode = if (text[first] == '<') PhpLexer.HTML else PhpLexer.PHP
        }
        while (index < end && !out.stopped) {
            if (fileMode == PhpLexer.HTML) htmlStretch() else php()
        }
        val phpState = mode or (label shl PhpLexer.LABEL_SHIFT)
        return out.embedded.join(fileMode or (phpState shl PhpLexer.PHP_SHIFT), html)
    }

    /** HTML up to the next island opener, which it enters. */
    private fun htmlStretch() {
        var at = index
        var openerEnd = -1
        while (at + 1 < end) {
            if (text[at] == '<' && text[at + 1] == '?') {
                openerEnd = openerEnd(at)
                if (openerEnd > 0) break
            }
            at += 1
        }
        val stretchEnd = if (openerEnd > 0) at else end
        if (stretchEnd > index) html = HtmlLexer.lexLine(text, index, stretchEnd, html, out)
        if (out.stopped) return
        index = stretchEnd
        if (openerEnd < 0) return
        out.span(at, at + 2, RoleCode.PUNCTUATION)
        if (openerEnd - at == 5) out.span(at + 2, openerEnd, RoleCode.KEYWORD) else out.span(at + 2, openerEnd, RoleCode.PUNCTUATION)
        index = openerEnd
        fileMode = PhpLexer.PHP
        mode = PhpLexer.CODE
        label = 0
        pending = NONE
    }

    /** The end of a `<?php`, `<?=`, or short `<?` opener at [at], or -1 when `<?` starts something else (`<?xml`). */
    private fun openerEnd(at: Int): Int {
        val after = at + 2
        if (after >= end) return after
        val char = text[after]
        if (char == '=') return after + 1
        if (char == ' ' || char == '\t' || char == '\r') return after
        if (after + 3 <= end && MarkupLexer.equalsIgnoreCase(text, after, after + 3, "php") &&
            (after + 3 == end || !PhpLexer.isNamePart(text[after + 3]))
        ) {
            return after + 3
        }
        return -1
    }

    private fun php() {
        when (mode) {
            PhpLexer.BLOCK_COMMENT -> blockComment(index, index)
            PhpLexer.SINGLE -> singleQuoted(index, index)
            PhpLexer.DOUBLE -> interpolated(index, index, '"')
            PhpLexer.BACKTICK -> interpolated(index, index, '`')
            PhpLexer.HEREDOC, PhpLexer.NOWDOC -> heredoc()
            else -> code()
        }
    }

    private fun next(offset: Int): Char = if (index + offset < end) text[index + offset] else '\u0000'

    private fun code() {
        val char = text[index]
        when {
            char == ' ' || char == '\t' || char == '\r' -> index += 1
            char == '?' && next(1) == '>' -> closeIsland(index)
            char == '#' && next(1) == '[' -> {
                out.span(index, index + 2, RoleCode.PUNCTUATION)
                index += 2
            }
            char == '#' || (char == '/' && next(1) == '/') -> lineComment()
            char == '/' && next(1) == '*' -> blockComment(index + 2, index)
            char == '$' -> {
                variable(index)
                pending = NONE
            }
            char == '\'' -> singleQuoted(index + 1, index)
            char == '"' -> interpolated(index + 1, index, '"')
            char == '`' -> interpolated(index + 1, index, '`')
            char == '<' && next(1) == '<' && next(2) == '<' && !openingHeredoc -> heredocOpen()
            isDigit(char) || (char == '.' && isDigit(next(1))) -> {
                number()
                pending = NONE
            }
            PhpLexer.isNameStart(char) -> name()
            char == '\\' -> {
                out.span(index, index + 1, RoleCode.PUNCTUATION)
                index += 1
            }
            else -> operator(char)
        }
    }

    private fun closeIsland(at: Int) {
        out.span(at, at + 2, RoleCode.PUNCTUATION)
        index = at + 2
        fileMode = PhpLexer.HTML
        mode = PhpLexer.CODE
        label = 0
    }

    /** A `#` or `//` comment, which `?>` ends. */
    private fun lineComment() {
        var at = index
        while (at + 1 < end && !(text[at] == '?' && text[at + 1] == '>')) at += 1
        val close = if (at + 1 < end) at else end
        out.span(index, close, RoleCode.COMMENT)
        index = close
        if (close < end) closeIsland(close)
    }

    private fun blockComment(
        bodyFrom: Int,
        from: Int,
    ) {
        val close = LexerScan.blockCommentEnd(text, bodyFrom, end)
        out.span(from, if (close < 0) end else close, RoleCode.COMMENT)
        if (close < 0) {
            index = end
            mode = PhpLexer.BLOCK_COMMENT
        } else {
            index = close
            mode = PhpLexer.CODE
        }
    }

    private fun variable(at: Int) {
        var nameEnd = at + 1
        while (nameEnd < end && text[nameEnd] == '$') nameEnd += 1
        while (nameEnd < end && PhpLexer.isNamePart(text[nameEnd])) nameEnd += 1
        if (nameEnd == at + 1) {
            out.span(at, at + 1, RoleCode.OPERATOR)
        } else {
            out.span(at, nameEnd, RoleCode.VARIABLE)
        }
        index = nameEnd
    }

    private fun name() {
        var nameEnd = index + 1
        while (nameEnd < end && PhpLexer.isNamePart(text[nameEnd])) nameEnd += 1
        val after = LexerScan.skipBlanks(text, nameEnd, end)
        val called = after < end && text[after] == '('
        val kind = if (pending == MEMBER) 0 else wordKind(index, nameEnd)
        val role =
            when {
                pending == MEMBER && MarkupLexer.equalsIgnoreCase(text, index, nameEnd, "class") -> RoleCode.KEYWORD
                pending == MEMBER -> if (called) RoleCode.FUNCTION else if (isAllCaps(index, nameEnd)) RoleCode.CONSTANT else RoleCode.PROPERTY
                kind != 0 -> kind and 0xFF
                pending == FUNCTION_NAME -> RoleCode.FUNCTION
                pending == TYPE_NAME -> RoleCode.TYPE
                called -> RoleCode.FUNCTION
                isAllCaps(index, nameEnd) -> RoleCode.CONSTANT
                text[index] in 'A'..'Z' -> RoleCode.TYPE
                else -> 0
            }
        if (role != 0) out.span(index, nameEnd, role)
        pending =
            when {
                kind and PhpLexer.DECLARES_FUNCTION != 0 -> FUNCTION_NAME
                kind and PhpLexer.DECLARES_TYPE != 0 -> TYPE_NAME
                // `Foo\Bar`: the next part keeps what this one was.
                nameEnd < end && text[nameEnd] == '\\' && (pending == TYPE_NAME) -> TYPE_NAME
                else -> NONE
            }
        index = nameEnd
    }

    /** The word table's kind for `text[from, to)` in any case, or 0. */
    private fun wordKind(
        from: Int,
        to: Int,
    ): Int {
        val length = to - from
        if (length > PhpLexer.MAX_WORD) return 0
        for (offset in 0 until length) folded[offset] = MarkupLexer.lower(text[from + offset])
        return PhpLexer.words.find(folded, 0, length)
    }

    private fun isAllCaps(
        from: Int,
        to: Int,
    ): Boolean {
        if (to - from < 2) return false
        var letters = false
        for (at in from until to) {
            val char = text[at]
            if (char in 'a'..'z' || char.code >= 0x80) return false
            if (char in 'A'..'Z') letters = true
        }
        return letters
    }

    private fun number() {
        var at = index
        if (text[at] == '0' && at + 1 < end && (text[at + 1] == 'x' || text[at + 1] == 'X')) {
            at += 2
            while (at < end && (isHexDigit(text[at]) || text[at] == '_')) at += 1
        } else if (text[at] == '0' && at + 1 < end && (text[at + 1] == 'b' || text[at + 1] == 'B' || text[at + 1] == 'o' || text[at + 1] == 'O')) {
            at += 2
            while (at < end && (isDigit(text[at]) || text[at] == '_')) at += 1
        } else {
            while (at < end && (isDigit(text[at]) || text[at] == '_')) at += 1
            if (at + 1 < end && text[at] == '.' && isDigit(text[at + 1])) {
                at += 1
                while (at < end && (isDigit(text[at]) || text[at] == '_')) at += 1
            } else if (at < end && text[at] == '.' && at > index && !(at + 1 < end && text[at + 1] == '.')) {
                at += 1
            }
            if (at < end && (text[at] == 'e' || text[at] == 'E')) {
                var exponent = at + 1
                if (exponent < end && (text[exponent] == '+' || text[exponent] == '-')) exponent += 1
                if (exponent < end && isDigit(text[exponent])) {
                    at = exponent
                    while (at < end && isDigit(text[at])) at += 1
                }
            }
        }
        out.span(index, at, RoleCode.NUMBER)
        index = at
    }

    private fun operator(char: Char) {
        val next = next(1)
        when {
            (char == '-' && next == '>') || (char == ':' && next == ':') -> {
                out.span(index, index + 2, RoleCode.OPERATOR)
                index += 2
                pending = MEMBER
                return
            }
            char == '?' && next == '-' && next(2) == '>' -> {
                out.span(index, index + 3, RoleCode.OPERATOR)
                index += 3
                pending = MEMBER
                return
            }
            char == '(' || char == ')' || char == '[' || char == ']' || char == '{' || char == '}' || char == ',' || char == ';' -> {
                out.span(index, index + 1, RoleCode.PUNCTUATION)
                index += 1
            }
            char == '=' || char == '+' || char == '-' || char == '*' || char == '/' || char == '%' || char == '<' || char == '>' ||
                char == '!' || char == '&' || char == '|' || char == '^' || char == '~' || char == '?' || char == ':' || char == '.' ||
                char == '@' -> {
                var to = index + 1
                while (to < end && to - index < 3 && isOperatorPart(text[to]) && !(text[to] == '?' && to + 1 < end && text[to + 1] == '>')) to += 1
                out.span(index, to, RoleCode.OPERATOR)
                index = to
            }
            else -> index += 1
        }
        pending = NONE
    }

    private fun isOperatorPart(char: Char): Boolean =
        char == '=' || char == '+' || char == '-' || char == '*' || char == '/' || char == '%' || char == '<' || char == '>' ||
            char == '!' || char == '&' || char == '|' || char == '^' || char == '?' || char == ':' || char == '.'

    /** A single-quoted string whose text continues at [from]; [open] is where its span starts. */
    private fun singleQuoted(
        from: Int,
        open: Int,
    ) {
        var at = from
        var segment = open
        while (at < end) {
            val char = text[at]
            if (char == '\\' && at + 1 < end && (text[at + 1] == '\'' || text[at + 1] == '\\')) {
                out.span(segment, at, RoleCode.STRING)
                out.span(at, at + 2, RoleCode.CONSTANT)
                at += 2
                segment = at
            } else {
                at += 1
                if (char == '\'') {
                    out.span(segment, at, RoleCode.STRING)
                    index = at
                    mode = PhpLexer.CODE
                    pending = NONE
                    return
                }
            }
        }
        out.span(segment, end, RoleCode.STRING)
        index = end
        mode = PhpLexer.SINGLE
    }

    /**
     * A `"…"` or backtick string, or a heredoc line when [quote] is [NO_QUOTE], whose text continues at
     * [from]; [open] is where its span starts. Returns with [mode] the string's when the line ends inside it.
     */
    private fun interpolated(
        from: Int,
        open: Int,
        quote: Char,
    ) {
        var at = from
        var segment = open
        while (at < end && !out.stopped) {
            val char = text[at]
            when {
                char == quote && quote != NO_QUOTE -> {
                    out.span(segment, at + 1, RoleCode.STRING)
                    index = at + 1
                    mode = PhpLexer.CODE
                    pending = NONE
                    return
                }
                char == '\\' && at + 1 < end -> {
                    out.span(segment, at, RoleCode.STRING)
                    out.span(at, at + 2, RoleCode.CONSTANT)
                    at += 2
                    segment = at
                }
                char == '$' && at + 1 < end && PhpLexer.isNameStart(text[at + 1]) -> {
                    out.span(segment, at, RoleCode.STRING)
                    at = simpleInterpolation(at)
                    segment = at
                }
                (char == '{' && at + 1 < end && text[at + 1] == '$') || (char == '$' && at + 1 < end && text[at + 1] == '{') -> {
                    out.span(segment, at, RoleCode.STRING)
                    at = complexInterpolation(at)
                    segment = at
                }
                else -> at += 1
            }
        }
        out.span(segment, end, RoleCode.STRING)
        index = end
        mode =
            when (quote) {
                '"' -> PhpLexer.DOUBLE
                '`' -> PhpLexer.BACKTICK
                else -> PhpLexer.HEREDOC
            }
    }

    /** `$name`, then one `->prop` or `[key]`; returns the offset after it. */
    private fun simpleInterpolation(at: Int): Int {
        var cursor = at + 1
        while (cursor < end && PhpLexer.isNamePart(text[cursor])) cursor += 1
        out.span(at, cursor, RoleCode.VARIABLE)
        if (cursor + 2 < end && text[cursor] == '-' && text[cursor + 1] == '>' && PhpLexer.isNameStart(text[cursor + 2])) {
            out.span(cursor, cursor + 2, RoleCode.OPERATOR)
            var nameEnd = cursor + 3
            while (nameEnd < end && PhpLexer.isNamePart(text[nameEnd])) nameEnd += 1
            out.span(cursor + 2, nameEnd, RoleCode.PROPERTY)
            return nameEnd
        }
        if (cursor < end && text[cursor] == '[') {
            var close = cursor + 1
            while (close < end && close - cursor < 64 && text[close] != ']' && text[close] != '"' && text[close] != ' ') close += 1
            if (close < end && text[close] == ']') {
                out.span(cursor, cursor + 1, RoleCode.PUNCTUATION)
                val key = cursor + 1
                when {
                    key < close && text[key] == '$' -> out.span(key, close, RoleCode.VARIABLE)
                    key < close && (isDigit(text[key]) || text[key] == '-') -> out.span(key, close, RoleCode.NUMBER)
                    else -> out.span(key, close, RoleCode.STRING)
                }
                out.span(close, close + 1, RoleCode.PUNCTUATION)
                return close + 1
            }
        }
        return cursor
    }

    /** `{$…}` or `${…}` at [at]: braces Punctuation, the expression as code up to the matching `}` on this line. */
    private fun complexInterpolation(at: Int): Int {
        val bodyFrom = if (text[at] == '{') at + 1 else at + 2
        out.span(at, bodyFrom, RoleCode.PUNCTUATION)
        val savedMode = mode
        mode = PhpLexer.CODE
        pending = NONE
        index = bodyFrom
        var depth = 0
        while (index < end && !out.stopped) {
            val char = text[index]
            if (char == '}' && depth == 0) {
                out.span(index, index + 1, RoleCode.PUNCTUATION)
                index += 1
                break
            }
            if (char == '{') depth += 1
            if (char == '}') depth -= 1
            if (char == '"' || char == '`' || (char == '?' && next(1) == '>') || char == '#' || (char == '/' && (next(1) == '/' || next(1) == '*'))) {
                // A quote would end the string; leave it to the string.
                break
            }
            if (char == '\'') {
                var close = index + 1
                while (close < end && text[close] != '\'') close += 1
                val to = minOf(close + 1, end)
                out.span(index, to, RoleCode.STRING)
                index = to
                continue
            }
            if (char == '<' && next(1) == '<' && next(2) == '<') {
                index += 3
                continue
            }
            code()
        }
        mode = savedMode
        return index
    }

    /** `<<<ID`, `<<<"ID"`, or `<<<'ID'` at [index]; the body starts on the next line. */
    private fun heredocOpen() {
        var at = LexerScan.skipBlanks(text, index + 3, end)
        val quote = if (at < end && (text[at] == '"' || text[at] == '\'')) text[at] else '\u0000'
        if (quote != '\u0000') at += 1
        val labelStart = at
        if (at >= end || !PhpLexer.isNameStart(text[at])) {
            operator('<')
            return
        }
        while (at < end && PhpLexer.isNamePart(text[at])) at += 1
        val labelEnd = at
        if (quote != '\u0000') {
            if (at >= end || text[at] != quote) {
                operator('<')
                return
            }
            at += 1
        }
        out.span(index, at, RoleCode.STRING)
        label = PhpLexer.labelHash(text, labelStart, labelEnd)
        mode = if (quote == '\'') PhpLexer.NOWDOC else PhpLexer.HEREDOC
        index = at
        // PHP needs the line to end after the label; anything left lexes as code.
        if (LexerScan.skipBlanks(text, index, end) < end) {
            val bodyMode = mode
            mode = PhpLexer.CODE
            openingHeredoc = true
            while (index < end && !out.stopped && fileMode == PhpLexer.PHP && mode == PhpLexer.CODE) code()
            openingHeredoc = false
            if (mode == PhpLexer.CODE && fileMode == PhpLexer.PHP) mode = bodyMode else label = 0
        }
    }

    /** A heredoc or nowdoc line: its closing label (indented or not), or body text. */
    private fun heredoc() {
        if (index == lineStart) {
            val labelStart = LexerScan.skipBlanks(text, index, end)
            var labelEnd = labelStart
            while (labelEnd < end && PhpLexer.isNamePart(text[labelEnd])) labelEnd += 1
            if (labelEnd > labelStart && PhpLexer.isNameStart(text[labelStart]) &&
                PhpLexer.labelHash(text, labelStart, labelEnd) == label
            ) {
                out.span(labelStart, labelEnd, RoleCode.STRING)
                index = labelEnd
                mode = PhpLexer.CODE
                label = 0
                pending = NONE
                return
            }
        }
        if (mode == PhpLexer.NOWDOC) {
            out.span(index, end, RoleCode.STRING)
            index = end
        } else {
            interpolated(index, index, NO_QUOTE)
        }
    }

    private companion object {
        const val NONE = 0
        const val FUNCTION_NAME = 1
        const val TYPE_NAME = 2
        const val MEMBER = 3
        const val NO_QUOTE = '\u0000'
    }
}
