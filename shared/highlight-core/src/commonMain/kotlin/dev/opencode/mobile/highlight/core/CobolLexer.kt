package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.ClassicScan.isLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit

/**
 * COBOL in fixed and free reference format, told apart line by line.
 *
 * Fixed format applies to a line whose columns 1-6 hold only digits and blanks and whose column 7
 * is an indicator (blank, `*`, `/`, `-`, `$`, or `D` before a blank): the sequence area's digits
 * are Number; a `*` or `/` indicator makes the rest of the line a Comment; `-` (a continuation
 * line) is Punctuation and `D` (a debugging line) Keyword; code starts in column 8. Once a line has
 * six sequence digits (or a `>>SOURCE FIXED` directive), columns 73 and on are a Comment too. Any
 * other line, and every line after `>>SOURCE FREE`, is free format: code from column 1.
 *
 * Roles: reserved words, hyphenated ones included (`PROGRAM-ID`, `END-IF`, `WORKING-STORAGE`), are
 * Keyword in any case, as are `>>` directives; figurative constants (`ZERO`, `SPACES`,
 * `HIGH-VALUES`, `TRUE`, ...) are Constant. A paragraph name (a name first on its line, in area A
 * in fixed format, followed by `.` where no sentence is open), a section name (before `SECTION`),
 * and the name after `PROGRAM-ID.`, `PERFORM`, `GO TO`, `THRU`, or `FUNCTION` are Function. A
 * `PIC` or `PICTURE` string (`9(5)V99`, `X(10)`, `ZZ,ZZ9.99-`) is Type. Literals `"..."` and
 * `'...'` (also prefixed: `X"FF"`, `N'x'`) are String with a doubled quote as Constant; numbers
 * and level numbers are Number; `*>` comments are Comment. Operators (`=`, `>=`, `**`, `+`, `&`,
 * ...) are Operator; `. , ; : ( )` are Punctuation.
 *
 * States: [FIXED] or [FREE] when the format is known from sequence numbers or a directive, and
 * [SENTENCE_OPEN] while a sentence has not yet ended with `.`. A literal never continues past its
 * line: a continuation line reopens it with a quote of its own, which lexes the same way.
 */
internal object CobolLexer : HighlightLexer() {
    private const val FIXED = 1
    private const val FREE = 2
    private const val FORMAT_MASK = 3
    private const val SENTENCE_OPEN = 4

    private const val SEQUENCE_COLUMNS = 6
    private const val CODE_COLUMN = 7
    private const val AREA_B_COLUMN = 11
    private const val LAST_COLUMN = 72

    // Word kinds.
    private const val KEYWORD = 1
    private const val CONSTANT = 2
    private const val KIND_MASK = 3
    private const val NAMES_FUNCTION = 4
    private const val PICTURE = 8
    private const val NAMES_AFTER_PERIOD = 16
    private const val SECTION = 32
    private const val GO = 64

    private val words = LexerWords(caseInsensitive = true)

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        var format = state and FORMAT_MASK
        val sentence = state and SENTENCE_OPEN
        if (format == FREE || !isFixedLayout(text, start, end)) {
            return code(text, start, end, end, -1, format or sentence, out)
        }
        var digits = 0
        val sequenceEnd = minOf(start + SEQUENCE_COLUMNS, end)
        var index = start
        while (index < sequenceEnd) {
            if (isDigit(text[index])) {
                var after = index + 1
                while (after < sequenceEnd && isDigit(text[after])) after += 1
                out.span(index, after, RoleCode.NUMBER)
                digits += after - index
                index = after
            } else {
                index += 1
            }
        }
        if (digits == SEQUENCE_COLUMNS) format = FIXED
        val indicator = start + SEQUENCE_COLUMNS
        if (indicator >= end) return format or sentence
        val limit = if (format == FIXED && end - start > LAST_COLUMN) start + LAST_COLUMN else end
        when (text[indicator]) {
            '*', '/' -> {
                out.span(indicator, end, RoleCode.COMMENT)
                return format or sentence
            }
            '-' -> out.span(indicator, indicator + 1, RoleCode.PUNCTUATION)
            'D', 'd' -> out.span(indicator, indicator + 1, RoleCode.KEYWORD)
            '$' -> return code(text, indicator, limit, end, -1, format or sentence, out)
        }
        return code(text, start + CODE_COLUMN, limit, end, start + AREA_B_COLUMN, format or sentence, out)
    }

    /** Columns 1-6 digits and blanks, and an indicator in column 7. */
    private fun isFixedLayout(
        text: CharArray,
        start: Int,
        end: Int,
    ): Boolean {
        val sequenceEnd = minOf(start + SEQUENCE_COLUMNS, end)
        for (index in start until sequenceEnd) {
            if (text[index] != ' ' && !isDigit(text[index])) return false
        }
        val indicator = start + SEQUENCE_COLUMNS
        if (indicator >= end) return true
        return when (text[indicator]) {
            ' ', '*', '/', '-', '$' -> true
            'D', 'd' -> indicator + 1 >= end || text[indicator + 1] == ' '
            else -> false
        }
    }

    /**
     * Code in `text[from, limit)`, then Comment up to [end] (the identification area). [areaB]
     * is where area B starts in fixed format (a paragraph name must start before it), or -1.
     */
    private fun code(
        text: CharArray,
        from: Int,
        limit: Int,
        end: Int,
        areaB: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        var format = state and FORMAT_MASK
        var open = state and SENTENCE_OPEN != 0
        var index = from
        var first = true
        var functionNext = false
        // After `PROGRAM-ID`: the name after its `.` is a Function.
        var namesAfterPeriod = false
        var afterGo = false
        while (index < limit && !out.stopped) {
            val char = text[index]
            val next = if (index + 1 < limit) text[index + 1] else ' '
            val wasFirst = first
            if (char != ' ' && char != '\t') first = false
            when {
                char == ' ' || char == '\t' -> index += 1
                char == '*' && next == '>' -> {
                    out.span(index, end, RoleCode.COMMENT)
                    return format or (if (open) SENTENCE_OPEN else 0)
                }
                wasFirst && (char == '>' && next == '>' || char == '$' && isLetter(next)) -> {
                    // A compiler directive: its name is Keyword and the rest of the line plain.
                    val after = wordEnd(text, if (char == '$') index + 1 else index + 2, limit)
                    out.span(index, after, RoleCode.KEYWORD)
                    format = directiveFormat(text, after, limit, format)
                    if (end > limit) out.span(limit, end, RoleCode.COMMENT)
                    return format or (state and SENTENCE_OPEN)
                }
                char == '"' || char == '\'' -> {
                    index = literal(text, index, index, limit, out)
                    open = true
                    functionNext = false
                }
                isLetter(char) || isDigit(char) -> {
                    var after = wordEnd(text, index, limit)
                    // A literal prefix: `X"FF"`, `NX'..'`.
                    if (after < limit && (text[after] == '"' || text[after] == '\'') && after - index <= 2 && isLiteralPrefix(text, index, after)) {
                        index = literal(text, index, after, limit, out)
                        open = true
                        continue
                    }
                    if (isNumber(text, index, after)) {
                        // `3.14`: a decimal point between digits.
                        if (after + 1 < limit && text[after] == '.' && isDigit(text[after + 1])) after = numberEnd(text, after + 1, limit)
                        out.span(index, after, RoleCode.NUMBER)
                        index = after
                        open = true
                        functionNext = false
                        continue
                    }
                    val kind = words.lookup(text, index, after)
                    when {
                        kind != 0 && kind and KIND_MASK == KEYWORD -> {
                            out.span(index, after, RoleCode.KEYWORD)
                            functionNext = kind and (NAMES_FUNCTION or NAMES_AFTER_PERIOD) != 0 || (afterGo && ClassicScan.equalsIgnoringCase(text, index, after, "to"))
                            afterGo = kind and GO != 0
                            if (kind and NAMES_AFTER_PERIOD != 0) namesAfterPeriod = true
                            if (kind and PICTURE != 0) {
                                index = picture(text, after, limit, out)
                                open = true
                                continue
                            }
                        }
                        kind != 0 && kind and KIND_MASK == CONSTANT -> {
                            out.span(index, after, RoleCode.CONSTANT)
                            functionNext = false
                        }
                        else -> {
                            val paragraph = wasFirst && !open && (areaB < 0 || index < areaB) && ClassicScan.nextIs(text, after, limit, '.')
                            if (functionNext || paragraph || isSectionName(text, after, limit)) out.span(index, after, RoleCode.FUNCTION)
                            // `PERFORM A THRU B`: THRU names the next one.
                            functionNext = false
                            namesAfterPeriod = false
                            afterGo = false
                        }
                    }
                    index = after
                    open = true
                }
                char == '.' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    open = false
                    functionNext = namesAfterPeriod
                    namesAfterPeriod = false
                }
                char == ',' || char == ';' || char == ':' || char == '(' || char == ')' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    open = true
                }
                isOperator(char) -> {
                    val two = (char == '*' && next == '*') || ((char == '>' || char == '<') && next == '=') || (char == '<' && next == '>')
                    val after = if (two) index + 2 else index + 1
                    out.span(index, after, RoleCode.OPERATOR)
                    index = after
                    open = true
                }
                else -> {
                    index += 1
                    open = true
                }
            }
        }
        if (end > limit) out.span(limit, end, RoleCode.COMMENT)
        return format or (if (open) SENTENCE_OPEN else 0)
    }

    /** A literal whose prefix starts at [from] and quote is at [quote]; the offset after it. */
    private fun literal(
        text: CharArray,
        from: Int,
        quote: Int,
        limit: Int,
        out: SpanSink,
    ): Int {
        val close = ClassicScan.doubledQuoteString(text, from, quote + 1, limit, text[quote], out)
        return if (close < 0) limit else close
    }

    /**
     * A PICTURE string after `PIC` (and an optional `IS`): the characters up to a blank, without a
     * final `.` or `,` that ends the clause. Returns the offset after it.
     */
    private fun picture(
        text: CharArray,
        from: Int,
        limit: Int,
        out: SpanSink,
    ): Int {
        var at = LexerScan.skipBlanks(text, from, limit)
        val isEnd = ClassicScan.wordEnd(text, at, limit)
        if (ClassicScan.equalsIgnoringCase(text, at, isEnd, "is") && (isEnd >= limit || text[isEnd] == ' ')) {
            out.span(at, isEnd, RoleCode.KEYWORD)
            at = LexerScan.skipBlanks(text, isEnd, limit)
        }
        var after = at
        while (after < limit && text[after] != ' ' && text[after] != '\t') after += 1
        if (after > at && (text[after - 1] == '.' || text[after - 1] == ',' || text[after - 1] == ';')) after -= 1
        out.span(at, after, RoleCode.TYPE)
        return after
    }

    /** A name followed by `SECTION` names a section. */
    private fun isSectionName(
        text: CharArray,
        after: Int,
        limit: Int,
    ): Boolean {
        val at = LexerScan.skipBlanks(text, after, limit)
        if (at == after) return false
        val wordEnd = ClassicScan.wordEnd(text, at, limit)
        return words.lookup(text, at, wordEnd) and SECTION != 0
    }

    /** `>>SOURCE FORMAT IS FREE`, `$SET SOURCEFORMAT"FIXED"`: the format the rest of the line names. */
    private fun directiveFormat(
        text: CharArray,
        from: Int,
        limit: Int,
        format: Int,
    ): Int {
        var index = from
        while (index < limit) {
            if (isLetter(text[index])) {
                val after = ClassicScan.wordEnd(text, index, limit)
                if (ClassicScan.equalsIgnoringCase(text, index, after, "free")) return FREE
                if (ClassicScan.equalsIgnoringCase(text, index, after, "fixed")) return FIXED
                index = after
            } else {
                index += 1
            }
        }
        return format
    }

    /** A COBOL word: letters, digits, `-`, and `_`, never ending in `-`. */
    private fun wordEnd(
        text: CharArray,
        from: Int,
        limit: Int,
    ): Int {
        var index = from
        while (index < limit && (isLetter(text[index]) || isDigit(text[index]) || text[index] == '_' || text[index] == '-')) index += 1
        while (index > from + 1 && text[index - 1] == '-') index -= 1
        return index
    }

    private fun numberEnd(
        text: CharArray,
        from: Int,
        limit: Int,
    ): Int {
        var index = from
        while (index < limit && isDigit(text[index])) index += 1
        return index
    }

    private fun isNumber(
        text: CharArray,
        from: Int,
        to: Int,
    ): Boolean {
        for (index in from until to) {
            if (!isDigit(text[index])) return false
        }
        return true
    }

    private fun isLiteralPrefix(
        text: CharArray,
        from: Int,
        to: Int,
    ): Boolean {
        for (index in from until to) {
            when (text[index]) {
                'X', 'x', 'N', 'n', 'Z', 'z', 'G', 'g', 'B', 'b', 'U', 'u', 'H', 'h' -> {}
                else -> return false
            }
        }
        return true
    }

    private fun isOperator(char: Char): Boolean =
        when (char) {
            '=', '<', '>', '+', '-', '*', '/', '&' -> true
            else -> false
        }

    init {
        words.register(
            KEYWORD,
            "identification", "id", "division", "author", "installation", "date-written", "date-compiled",
            "security", "remarks", "environment", "configuration", "source-computer", "object-computer",
            "special-names", "input-output", "file-control", "i-o-control", "data", "file", "working-storage",
            "local-storage", "linkage", "screen", "report", "procedure", "fd", "sd", "rd", "value",
            "values", "occurs", "times", "indexed", "redefines", "renames", "usage", "comp", "comp-1", "comp-2",
            "comp-3", "comp-4", "comp-5", "computational", "computational-3", "binary", "packed-decimal", "display",
            "accept", "move", "to", "add", "subtract", "multiply", "divide", "compute", "giving", "from", "by", "into",
            "remainder", "rounded", "if", "then", "else", "end-if", "evaluate", "when", "other", "also", "end-evaluate",
            "until", "varying", "after", "before", "end-perform", "goback", "stop", "run", "exit", "call", "using",
            "returning", "end-call", "open", "close", "read", "write", "rewrite", "delete", "start", "input",
            "output", "i-o", "extend", "at", "end", "not", "and", "or", "is", "equal", "equals", "greater", "less",
            "than", "initialize", "string", "end-string", "unstring", "end-unstring", "delimited", "size", "inspect",
            "tallying", "replacing", "converting", "characters", "leading", "trailing", "first", "initial", "set",
            "up", "down", "search", "all", "varying", "continue", "select", "assign", "organization", "sequential",
            "relative", "access", "mode", "random", "dynamic", "record", "records", "key", "status", "copy",
            "replace", "of", "in", "on", "exception", "overflow", "error", "invalid", "with", "no", "advancing",
            "line", "lines", "page", "sort", "merge", "ascending", "descending", "justified", "just", "sync",
            "synchronized", "blank", "sign", "separate", "character", "depending", "index", "pointer", "reference",
            "content", "exec", "end-exec", "sql", "cics", "global", "external", "filler", "label", "block",
            "contains", "standard", "omitted", "next", "sentence", "corresponding", "corr", "release", "return",
            "end-return", "end-read", "end-write", "end-rewrite", "end-delete", "end-start", "end-search",
            "end-compute", "end-add", "end-subtract", "end-multiply", "end-divide", "end-accept", "end-display",
            "positive", "negative", "numeric", "alphabetic", "alphabetic-lower", "alphabetic-upper", "class",
            "decimal-point", "comma", "currency", "console", "entry", "use", "declaratives", "end-declaratives",
            "local", "typedef", "based", "allocate", "free", "raise", "resume", "invoke", "method", "object",
            "factory", "repository", "self", "super", "property", "override", "end-program", "end-method",
            "end-class", "end-function", "program", "common", "recursive", "optional", "random", "test", "sum",
            "redefines", "temporary", "validate", "json", "xml", "generate", "parse",
        )
        words.register(KEYWORD or NAMES_FUNCTION, "perform", "thru", "through", "function")
        words.register(KEYWORD or GO, "go")
        words.register(KEYWORD or NAMES_AFTER_PERIOD, "program-id", "function-id", "class-id", "method-id")
        words.register(KEYWORD or PICTURE, "pic", "picture")
        words.register(KEYWORD or SECTION, "section")
        words.register(
            CONSTANT,
            "zero", "zeros", "zeroes", "space", "spaces", "high-value", "high-values", "low-value", "low-values",
            "quote", "quotes", "null", "nulls", "true", "false",
        )
    }
}
