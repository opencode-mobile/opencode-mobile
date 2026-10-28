package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * The shared lexer for C-like languages, configured per language by a [CLikeSyntax]: TypeScript
 * and JavaScript (with JSX), Kotlin, and Swift so far.
 *
 * Roles: keywords from the syntax's word table (contextual ones only before something a keyword
 * can precede), strings with escapes as Constant and interpolation delimiters as Punctuation,
 * comments, numbers, true/false/null-like Constant, Type for capitalized identifiers and the
 * names after `class`-like keywords, Function for an identifier before `(` or after `fun`-like
 * keywords, Property after `.` (and for object keys where the syntax has them), Attribute for
 * `@` annotations, Operator and Punctuation. Plain identifiers take the syntax's identifier role.
 *
 * Everything still open at a line end is packed into the state as a stack of up to four frames,
 * one byte each, innermost in the low byte: a block comment (nesting depth), a string that can
 * span lines or holds an interpolation (its kind, or a Swift raw string's `#` count), the code of
 * an interpolation or JSX expression (its open brace or paren depth), and a JSX element (inside a
 * tag, and how many elements are open). State 0 is code at the top of a document. Past four
 * frames a construct degrades instead of nesting: a string or comment ends with its line, an
 * interpolation stays string text, and `<` is an operator.
 *
 * Within a line the lexer also tracks the previous token (whether a `/` starts a regex and a `<`
 * a JSX element), pending `class`/`fun` names, and which bracket is innermost (object keys);
 * these reset at every line start, so they never reach the state. Lookahead is bounded: a
 * regex is scanned to its closing `/` once and, if the line has none, regexes are off for the
 * rest of the line; everything else looks at most past one word and its blanks.
 */
internal class CLikeLexer(
    private val syntax: CLikeSyntax,
) : HighlightLexer() {
    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int = CLikeLine(syntax, text, start, end, out).run(state)
}

/** One language's settings for [CLikeLexer]. Immutable and shared by every session. */
internal class CLikeSyntax(
    /** Words with special roles: a [RoleCode] or'ed with the `CLikeSyntax` word flags. */
    words: Map<String, Int>,
    /** The role of an identifier no other rule colors; 0 leaves it plain. */
    val identifierRole: Int,
    /** One of the `STRINGS_*` string families. */
    val strings: Int,
    val nestedComments: Boolean,
    val regexLiterals: Boolean,
    val jsx: Boolean,
    /** `name:` after `{`, `,`, or `;` outside parentheses is a Property (JavaScript object keys). */
    val objectKeys: Boolean,
    /** Capitalized identifiers are types, and capitalized ones with an underscore are constants. */
    val capitalizedTypes: Boolean,
    /** What `#` starts: one of the `HASH_*` modes. */
    val hash: Int,
) {
    val words = WordTable(words)

    companion object {
        /** `'` and `"` single-line strings, backtick templates with `${}`. */
        const val STRINGS_JS = 0

        /** `"` with `$name` and `${}`, raw `"""` across lines, `'` characters, backtick names. */
        const val STRINGS_KOTLIN = 1

        /** `"` and `"""` with `\()`, raw `#"…"#` strings, backtick names. */
        const val STRINGS_SWIFT = 2

        const val HASH_NONE = 0

        /** `#name` is a private name (JavaScript); `#!` on the first line is a comment. */
        const val HASH_PRIVATE = 1

        /** `#name` is a directive keyword and `#"` opens a raw string (Swift). */
        const val HASH_SWIFT = 2

        // Word flags, above the role code.
        const val ROLE_MASK = 0xFF

        /** A keyword only before a word, string, `{`, `[`, `*`, `#`, or `@`; otherwise an identifier. */
        const val CONTEXTUAL = 1 shl 8

        /** Ends an expression like an identifier does (`this`), so a `/` after it divides. */
        const val VALUE = 1 shl 9

        /** The next identifier names a function. */
        const val DECLARES_FUNCTION = 1 shl 10

        /** The next identifier names a type. */
        const val DECLARES_TYPE = 1 shl 11
    }
}

/** An immutable open-addressing table from words to ints, looked up without allocating. */
internal class WordTable(
    entries: Map<String, Int>,
) {
    private val size = tableSize(entries.size)
    private val keys = arrayOfNulls<String>(size)
    private val values = IntArray(size)
    private val maxLength = entries.keys.maxOfOrNull { it.length } ?: 0

    init {
        for ((word, value) in entries) {
            var slot = hash(word) and (size - 1)
            while (keys[slot] != null) slot = (slot + 1) and (size - 1)
            keys[slot] = word
            values[slot] = value
        }
    }

    /** The value for `text[from, to)`, or 0 when it is not in the table. */
    fun find(
        text: CharArray,
        from: Int,
        to: Int,
    ): Int {
        val length = to - from
        if (length > maxLength) return 0
        var hash = length
        for (index in from until to) hash = hash * 31 + text[index].code
        var slot = hash and (size - 1)
        while (true) {
            val key = keys[slot] ?: return 0
            if (LexerScan.equals(text, from, to, key)) return values[slot]
            slot = (slot + 1) and (size - 1)
        }
    }

    private fun hash(word: String): Int {
        var hash = word.length
        for (char in word) hash = hash * 31 + char.code
        return hash
    }

    private fun tableSize(count: Int): Int {
        var size = 16
        while (size < count * 4) size *= 2
        return size
    }
}

/** Lexes one line; a new one per line keeps [CLikeLexer] stateless and the scan allocation-free. */
private class CLikeLine(
    private val syntax: CLikeSyntax,
    private val text: CharArray,
    private val lineStart: Int,
    private val end: Int,
    private val out: SpanSink,
) {
    private var index = lineStart
    private var stack = 0

    // Per line only: the previous token's class, regexes still possible, a pending declaration,
    // object keys allowed here, and the innermost brackets (1 bits for `(` or `[`).
    private var previous = START
    private var regexAllowed = true
    private var declaring = 0
    private var keyAllowed = true
    private var brackets = 0L
    private var bracketDepth = 0

    fun run(state: Int): Int {
        stack = state
        if (lineStart == 0 && state == 0 && syntax.hash == CLikeSyntax.HASH_PRIVATE && LexerScan.startsWith(text, 0, end, "#!")) {
            out.span(0, end, RoleCode.COMMENT)
            return 0
        }
        while (index < end && !out.stopped) {
            val top = stack and 0xFF
            when (top ushr 5) {
                NONE, BRACE, PAREN -> code()
                STRING -> string(top and 31)
                RAW -> rawString(top and 31, multiline = false)
                RAW_MULTILINE -> rawString(top and 31, multiline = true)
                COMMENT -> comment(top and 31)
                JSX -> if (top and IN_TAG != 0) jsxTag(top and DEPTH) else jsxChildren(top and DEPTH)
            }
        }
        // Single-line strings end with their line; an unterminated one leaves nothing open.
        val top = stack and 0xFF
        val kind = top ushr 5
        if (kind == RAW || (kind == STRING && ((top and 31) == KOTLIN_LINE || (top and 31) == SWIFT_LINE))) stack = stack ushr 8
        return stack
    }

    // region Frames

    private fun canPush(): Boolean = (stack ushr 24) == 0

    private fun push(
        kind: Int,
        value: Int,
    ) {
        stack = (stack shl 8) or (kind shl 5) or value
    }

    private fun pop() {
        stack = stack ushr 8
    }

    private fun setTop(
        kind: Int,
        value: Int,
    ) {
        stack = (stack and 0xFF.inv()) or (kind shl 5) or value
    }

    // endregion

    // region Code

    private fun code() {
        val char = text[index]
        when {
            char == ' ' || char == '\t' || char == '\r' || char == '\u000C' -> index += 1
            char == '/' -> slash()
            char == '"' -> doubleQuote()
            char == '\'' -> singleQuote()
            char == '`' -> backtick()
            isDigit(char) -> number()
            char == '.' -> dot()
            char == '{' || char == '}' || char == '(' || char == ')' || char == '[' || char == ']' -> bracket(char)
            char == ';' || char == ',' -> separator()
            char == '@' -> at()
            char == '#' -> hash()
            char == '<' && syntax.jsx && previous == START && jsxStart() -> Unit
            isOperator(char) -> operator()
            isIdentifierStart(char) -> word(index, identifierEnd(index + 1))
            else -> index += 1
        }
    }

    private fun emit(
        from: Int,
        to: Int,
        role: Int,
    ) {
        if (role != 0) out.span(from, to, role)
    }

    private fun slash() {
        val next = if (index + 1 < end) text[index + 1] else ' '
        when {
            next == '/' -> {
                out.span(index, end, RoleCode.COMMENT)
                index = end
            }
            next == '*' -> {
                if (canPush()) {
                    out.span(index, index + 2, RoleCode.COMMENT)
                    index += 2
                    push(COMMENT, 1)
                } else {
                    val close = LexerScan.blockCommentEnd(text, index + 2, end)
                    val to = if (close < 0) end else close
                    out.span(index, to, RoleCode.COMMENT)
                    index = to
                }
            }
            syntax.regexLiterals && previous == START && regexAllowed && regex() -> Unit
            else -> {
                val to = if (next == '=') index + 2 else index + 1
                out.span(index, to, RoleCode.OPERATOR)
                index = to
                expressionStart()
            }
        }
    }

    /** A regex literal from the `/` at [index] to its closing `/` and flags on this line; false when there is none. */
    private fun regex(): Boolean {
        var at = index + 1
        var inClass = false
        while (at < end) {
            val char = text[at]
            when {
                char == '\\' -> at += 1
                char == '[' -> inClass = true
                char == ']' -> inClass = false
                char == '/' && !inClass -> {
                    at += 1
                    while (at < end && isAsciiLetter(text[at])) at += 1
                    out.span(index, at, RoleCode.STRING)
                    index = at
                    value()
                    return true
                }
            }
            at += 1
        }
        regexAllowed = false
        return false
    }

    private fun number() {
        var at = index
        while (at < end && (isDigit(text[at]) || text[at] == '_')) at += 1
        if (at + 1 < end && text[at] == '.' && isDigit(text[at + 1])) {
            at += 1
            while (at < end && (isDigit(text[at]) || text[at] == '_')) at += 1
        }
        if (at < end && (text[at] == 'e' || text[at] == 'E')) {
            var exponent = at + 1
            if (exponent < end && (text[exponent] == '+' || text[exponent] == '-')) exponent += 1
            if (exponent < end && isDigit(text[exponent])) at = exponent
        }
        // Prefixes after a leading 0 (0x, 0b, 0o), hex digits, and suffixes (n, L, f, u) ride along.
        while (at < end && (isAsciiLetter(text[at]) || isDigit(text[at]) || text[at] == '_')) at += 1
        out.span(index, at, RoleCode.NUMBER)
        index = at
        value()
    }

    private fun dot() {
        val next = if (index + 1 < end) text[index + 1] else ' '
        when {
            isDigit(next) && previous != VALUE -> number()
            next == '.' -> {
                // `..`, `...`, `..<`: ranges and spreads.
                var at = index + 1
                while (at < end && text[at] == '.') at += 1
                if (at < end && text[at] == '<') at += 1
                out.span(index, at, RoleCode.OPERATOR)
                index = at
                expressionStart()
            }
            else -> {
                out.span(index, index + 1, RoleCode.PUNCTUATION)
                index += 1
                previous = DOT
                keyAllowed = false
            }
        }
    }

    private fun bracket(char: Char) {
        val top = stack and 0xFF
        val kind = top ushr 5
        val depth = top and 31
        when (char) {
            '{' -> {
                if (kind == BRACE) setTop(BRACE, minOf(depth + 1, 31))
                openBracket(0)
                expressionStart()
                keyAllowed = true
            }
            '}' -> {
                if (kind == BRACE) {
                    if (depth == 0) {
                        // Closes an interpolation or a JSX expression.
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                        pop()
                        value()
                        return
                    }
                    setTop(BRACE, depth - 1)
                }
                closeBracket()
                expressionStart()
            }
            '(' -> {
                if (kind == PAREN) setTop(PAREN, minOf(depth + 1, 31))
                openBracket(1)
                expressionStart()
            }
            ')' -> {
                if (kind == PAREN) {
                    if (depth == 0) {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                        pop()
                        value()
                        return
                    }
                    setTop(PAREN, depth - 1)
                }
                closeBracket()
                value()
            }
            '[' -> {
                openBracket(1)
                expressionStart()
            }
            else -> {
                closeBracket()
                value()
            }
        }
        out.span(index, index + 1, RoleCode.PUNCTUATION)
        index += 1
    }

    private fun openBracket(bit: Long) {
        if (bracketDepth < 63) brackets = (brackets shl 1) or bit
        bracketDepth += 1
    }

    private fun closeBracket() {
        if (bracketDepth == 0) return
        bracketDepth -= 1
        if (bracketDepth < 63) brackets = brackets ushr 1
    }

    private fun separator() {
        out.span(index, index + 1, RoleCode.PUNCTUATION)
        index += 1
        expressionStart()
        // A key may follow unless the innermost bracket on this line is `(` or `[`.
        keyAllowed = bracketDepth == 0 || bracketDepth > 63 || (brackets and 1L) == 0L
    }

    private fun operator() {
        var at = index + 1
        while (at < end && isOperator(text[at])) at += 1
        val role = if (at - index == 1 && text[index] == ':') RoleCode.PUNCTUATION else RoleCode.OPERATOR
        out.span(index, at, role)
        index = at
        expressionStart()
    }

    /** `@name`, `@a.b`, `@file:Name`: an annotation, decorator, or attribute. A label (`return@x`) stays plain. */
    private fun at() {
        val from = index
        index += 1
        if (index >= end || !isIdentifierStart(text[index])) return
        if (from > lineStart && isIdentifierPart(text[from - 1])) return
        var to = identifierEnd(index + 1)
        while (to + 1 < end && (text[to] == '.' || text[to] == ':') && isIdentifierStart(text[to + 1])) to = identifierEnd(to + 2)
        out.span(from, to, RoleCode.ATTRIBUTE)
        index = to
        expressionStart()
    }

    private fun hash() {
        when (syntax.hash) {
            CLikeSyntax.HASH_PRIVATE ->
                if (index + 1 < end && isIdentifierStart(text[index + 1])) {
                    identifier(index, identifierEnd(index + 2))
                } else {
                    index += 1
                }
            CLikeSyntax.HASH_SWIFT -> {
                var at = index
                while (at < end && text[at] == '#') at += 1
                when {
                    at < end && text[at] == '"' -> openRawString(at - index, at)
                    at == index + 1 && at < end && isIdentifierStart(text[at]) -> {
                        // #if, #else, #endif, #available, #selector, ...
                        val to = identifierEnd(at + 1)
                        out.span(index, to, RoleCode.KEYWORD)
                        index = to
                        expressionStart()
                    }
                    else -> index = at
                }
            }
            else -> index += 1
        }
    }

    private fun word(
        from: Int,
        to: Int,
    ) {
        if (previous != DOT) {
            val entry = syntax.words.find(text, from, to)
            if (entry != 0 && (entry and CLikeSyntax.CONTEXTUAL == 0 || keywordFollows(to))) {
                val role = entry and CLikeSyntax.ROLE_MASK
                out.span(from, to, role)
                index = to
                if (entry and CLikeSyntax.VALUE != 0 || role == RoleCode.CONSTANT || role == RoleCode.TYPE) value() else expressionStart()
                declaring = entry and (CLikeSyntax.DECLARES_FUNCTION or CLikeSyntax.DECLARES_TYPE)
                return
            }
        }
        identifier(from, to)
    }

    private fun identifier(
        from: Int,
        to: Int,
    ) {
        val next = if (to < end) text[to] else ' '
        val capitalized = syntax.capitalizedTypes && text[from] in 'A'..'Z'
        val role =
            when {
                next == '(' && declaring != CLikeSyntax.DECLARES_TYPE -> RoleCode.FUNCTION
                capitalized && previous == DOT -> if (isConstantCase(from, to)) RoleCode.CONSTANT else RoleCode.TYPE
                previous == DOT -> RoleCode.PROPERTY
                declaring == CLikeSyntax.DECLARES_FUNCTION && next != '.' -> RoleCode.FUNCTION
                declaring == CLikeSyntax.DECLARES_TYPE -> RoleCode.TYPE
                syntax.objectKeys && keyAllowed && keyColonFollows(to) -> RoleCode.PROPERTY
                capitalized -> if (isConstantCase(from, to)) RoleCode.CONSTANT else RoleCode.TYPE
                else -> syntax.identifierRole
            }
        emit(from, to, role)
        index = to
        value()
    }

    /** Whether a contextual keyword ending at [after] is followed by something a keyword precedes. */
    private fun keywordFollows(after: Int): Boolean {
        val at = LexerScan.skipBlanks(text, after, end)
        if (at >= end) return false
        val char = text[at]
        return isIdentifierStart(char) || char == '"' || char == '\'' || char == '`' || char == '{' || char == '[' ||
            char == '*' || char == '#' || char == '@'
    }

    private fun keyColonFollows(after: Int): Boolean {
        var at = LexerScan.skipBlanks(text, after, end)
        if (at < end && text[at] == '?') at += 1
        return at < end && text[at] == ':' && (at + 1 >= end || text[at + 1] != ':')
    }

    /** `MAX_SIZE`: capitals, digits, and at least one underscore. */
    private fun isConstantCase(
        from: Int,
        to: Int,
    ): Boolean {
        var underscore = false
        for (at in from until to) {
            val char = text[at]
            if (char == '_') underscore = true else if (char !in 'A'..'Z' && !isDigit(char)) return false
        }
        return underscore
    }

    private fun expressionStart() {
        previous = START
        declaring = 0
        keyAllowed = false
    }

    private fun value() {
        previous = VALUE
        declaring = 0
        keyAllowed = false
    }

    // endregion

    // region Strings

    private fun doubleQuote() {
        when (syntax.strings) {
            CLikeSyntax.STRINGS_JS -> quotedString('"')
            CLikeSyntax.STRINGS_KOTLIN -> openString(if (isTripleQuote(index)) KOTLIN_RAW else KOTLIN_LINE)
            else -> openString(if (isTripleQuote(index)) SWIFT_MULTILINE else SWIFT_LINE)
        }
    }

    private fun singleQuote() {
        // A JavaScript string or a Kotlin character; Swift has no single quotes.
        if (syntax.strings == CLikeSyntax.STRINGS_SWIFT) index += 1 else quotedString('\'')
    }

    private fun backtick() {
        if (syntax.strings == CLikeSyntax.STRINGS_JS) {
            openString(TEMPLATE)
            return
        }
        // A backtick-quoted name in Kotlin or Swift; a lone backtick stays plain.
        var close = index + 1
        while (close < end && text[close] != '`') close += 1
        if (close >= end) {
            index += 1
        } else {
            identifier(index, close + 1)
        }
    }

    private fun isTripleQuote(at: Int): Boolean = at + 2 < end && text[at + 1] == '"' && text[at + 2] == '"'

    /** A string that never holds frames: JavaScript quotes and Kotlin characters. Ends at its line. */
    private fun quotedString(quote: Char) {
        var at = index + 1
        while (at < end) {
            val char = text[at]
            at += 1
            if (char == '\\') {
                at += 1
            } else if (char == quote) {
                break
            }
        }
        val to = minOf(at, end)
        stringWithEscapes(index, to)
        index = to
        value()
    }

    private fun openString(kind: Int) {
        val delimiter = if (kind == KOTLIN_RAW || kind == SWIFT_MULTILINE) 3 else 1
        if (!canPush()) {
            flatString(index + delimiter, if (kind == TEMPLATE) '`' else '"', delimiter, 0, escapes = kind != KOTLIN_RAW)
            return
        }
        out.span(index, index + delimiter, RoleCode.STRING)
        index += delimiter
        push(STRING, kind)
    }

    /** The body of a string frame: text, escapes, and interpolations up to its closing delimiter. */
    private fun string(kind: Int) {
        val escapes = kind != KOTLIN_RAW
        val swift = kind == SWIFT_LINE || kind == SWIFT_MULTILINE
        val triple = kind == KOTLIN_RAW || kind == SWIFT_MULTILINE
        val quote = if (kind == TEMPLATE) '`' else '"'
        var segment = index
        var at = index
        while (at < end) {
            val char = text[at]
            if (char == '\\' && escapes) {
                if (swift && at + 1 < end && text[at + 1] == '(' && canPush()) {
                    out.span(segment, at, RoleCode.STRING)
                    openInterpolation(at, at + 2, PAREN)
                    return
                }
                val escapeEnd = escapeEnd(at)
                if (out.collecting) {
                    out.span(segment, at, RoleCode.STRING)
                    out.span(at, escapeEnd, RoleCode.CONSTANT)
                    segment = escapeEnd
                }
                at = escapeEnd
                continue
            }
            if (char == '$' && !swift && at + 1 < end) {
                val next = text[at + 1]
                if (next == '{' && canPush()) {
                    out.span(segment, at, RoleCode.STRING)
                    openInterpolation(at, at + 2, BRACE)
                    return
                }
                if (kind != TEMPLATE && (isAsciiLetter(next) || next == '_')) {
                    // Kotlin's `$name` template.
                    val nameEnd = identifierEnd(at + 2)
                    out.span(segment, at, RoleCode.STRING)
                    out.span(at, nameEnd, RoleCode.VARIABLE)
                    segment = nameEnd
                    at = nameEnd
                    continue
                }
            }
            if (char == quote && (!triple || isTripleQuote(at))) {
                var close = at + if (triple) 3 else 1
                // Kotlin: in `""""`, the last three quotes close the string.
                if (kind == KOTLIN_RAW) while (close < end && text[close] == '"') close += 1
                out.span(segment, close, RoleCode.STRING)
                index = close
                pop()
                value()
                return
            }
            at += 1
        }
        out.span(segment, end, RoleCode.STRING)
        index = end
    }

    private fun openInterpolation(
        from: Int,
        to: Int,
        kind: Int,
    ) {
        out.span(from, to, RoleCode.PUNCTUATION)
        index = to
        push(kind, 0)
        expressionStart()
    }

    /** Swift `#"`, `##"""`, ...: [hashes] `#` before the quote at [quote]. */
    private fun openRawString(
        hashes: Int,
        quote: Int,
    ) {
        val multiline = isTripleQuote(quote)
        val to = quote + if (multiline) 3 else 1
        if (!canPush()) {
            flatString(to, '"', if (multiline) 3 else 1, hashes, escapes = false)
            return
        }
        out.span(index, to, RoleCode.STRING)
        index = to
        push(if (multiline) RAW_MULTILINE else RAW, minOf(hashes, 31))
    }

    /** The body of a Swift raw string: only `\#(` interpolates and `\#n` escapes, with the same `#` count. */
    private fun rawString(
        hashes: Int,
        multiline: Boolean,
    ) {
        var segment = index
        var at = index
        while (at < end) {
            val char = text[at]
            if (char == '\\' && hashesAt(at + 1, hashes)) {
                val after = at + 1 + hashes
                if (after < end && text[after] == '(' && canPush()) {
                    out.span(segment, at, RoleCode.STRING)
                    openInterpolation(at, after + 1, PAREN)
                    return
                }
                val escapeEnd = minOf(end, after + 1)
                out.span(segment, at, RoleCode.STRING)
                out.span(at, escapeEnd, RoleCode.CONSTANT)
                segment = escapeEnd
                at = escapeEnd
                continue
            }
            if (char == '"' && (!multiline || isTripleQuote(at))) {
                val quotesEnd = at + if (multiline) 3 else 1
                if (hashesAt(quotesEnd, hashes)) {
                    val close = quotesEnd + hashes
                    out.span(segment, close, RoleCode.STRING)
                    index = close
                    pop()
                    value()
                    return
                }
            }
            at += 1
        }
        out.span(segment, end, RoleCode.STRING)
        index = end
    }

    private fun hashesAt(
        from: Int,
        count: Int,
    ): Boolean {
        if (from + count > end) return false
        for (at in from until from + count) if (text[at] != '#') return false
        return true
    }

    /**
     * A string opened while four frames are open: one String span up to its closing delimiter on
     * this line (no interpolation), or to the line end.
     */
    private fun flatString(
        bodyStart: Int,
        quote: Char,
        quotes: Int,
        hashes: Int,
        escapes: Boolean,
    ) {
        var at = bodyStart
        var to = end
        while (at < end) {
            val char = text[at]
            if (char == '\\' && escapes) {
                at += 2
                continue
            }
            if (char == quote && (quotes == 1 || isTripleQuote(at)) && hashesAt(at + quotes, hashes)) {
                to = at + quotes + hashes
                break
            }
            at += 1
        }
        out.span(index, minOf(to, end), RoleCode.STRING)
        index = minOf(to, end)
        value()
    }

    /** A string's text with escapes as Constant; escapes are only split out when spans are collected. */
    private fun stringWithEscapes(
        from: Int,
        to: Int,
    ) {
        if (!out.collecting) {
            out.span(from, to, RoleCode.STRING)
            return
        }
        var segment = from
        var at = from + 1
        while (at < to) {
            if (text[at] == '\\') {
                val escapeEnd = minOf(to, escapeEnd(at))
                out.span(segment, at, RoleCode.STRING)
                out.span(at, escapeEnd, RoleCode.CONSTANT)
                segment = escapeEnd
                at = escapeEnd
            } else {
                at += 1
            }
        }
        out.span(segment, to, RoleCode.STRING)
    }

    /** The end of the escape at the backslash [at]: `\n`, `\xHH`, `\uHHHH`, `\u{…}`, or a whole surrogate pair. */
    private fun escapeEnd(at: Int): Int {
        if (at + 1 >= end) return end
        val char = text[at + 1]
        return when {
            char == 'u' && at + 2 < end && text[at + 2] == '{' -> {
                var close = at + 3
                while (close < end && close < at + 14 && isHexDigit(text[close])) close += 1
                if (close < end && text[close] == '}') close + 1 else at + 2
            }
            char == 'u' -> if (hexDigits(at + 2, 4)) at + 6 else at + 2
            char == 'x' && syntax.strings == CLikeSyntax.STRINGS_JS -> if (hexDigits(at + 2, 2)) at + 4 else at + 2
            char.isHighSurrogate() && at + 2 < end && text[at + 2].isLowSurrogate() -> at + 3
            else -> at + 2
        }
    }

    private fun hexDigits(
        from: Int,
        count: Int,
    ): Boolean {
        if (from + count > end) return false
        for (at in from until from + count) if (!isHexDigit(text[at])) return false
        return true
    }

    // endregion

    // region Comments

    private fun comment(depth: Int) {
        var level = depth
        var at = index
        while (at + 1 < end) {
            val char = text[at]
            if (char == '*' && text[at + 1] == '/') {
                at += 2
                level -= 1
                if (level == 0) {
                    out.span(index, at, RoleCode.COMMENT)
                    index = at
                    pop()
                    return
                }
            } else if (char == '/' && text[at + 1] == '*' && syntax.nestedComments) {
                at += 2
                level = minOf(level + 1, 31)
            } else {
                at += 1
            }
        }
        out.span(index, end, RoleCode.COMMENT)
        index = end
        setTop(COMMENT, level)
    }

    // endregion

    // region JSX

    /** A JSX element at [index] (`<` after an expression start), or false when this `<` is not one. */
    private fun jsxStart(): Boolean {
        if (index + 1 >= end || !canPush()) return false
        val next = text[index + 1]
        if (next == '>') {
            out.span(index, index + 2, RoleCode.PUNCTUATION)
            index += 2
            push(JSX, 1)
            return true
        }
        if (!isAsciiLetter(next)) return false
        val nameEnd = tagNameEnd(index + 1)
        val after = if (nameEnd < end) text[nameEnd] else ' '
        // TypeScript type parameters, not elements: `<T,>`, `<T>(x) =>`, `<T extends U>`.
        if (after == ',') return false
        if (after == '>' && nameEnd + 1 < end && text[nameEnd + 1] == '(') return false
        val word = LexerScan.skipBlanks(text, nameEnd, end)
        if (LexerScan.startsWith(text, word, end, "extends") && (word + 7 >= end || !isIdentifierPart(text[word + 7]))) return false
        out.span(index, index + 1, RoleCode.PUNCTUATION)
        out.span(index + 1, nameEnd, RoleCode.TAG)
        index = nameEnd
        push(JSX, IN_TAG)
        return true
    }

    /** Inside `<name …>`: attributes, `{…}` values, and the `>` or `/>` that ends the tag. */
    private fun jsxTag(depth: Int) {
        val char = text[index]
        when {
            char == '>' -> {
                out.span(index, index + 1, RoleCode.PUNCTUATION)
                index += 1
                setTop(JSX, minOf(depth + 1, DEPTH))
            }
            char == '/' && index + 1 < end && text[index + 1] == '>' -> {
                out.span(index, index + 2, RoleCode.PUNCTUATION)
                index += 2
                closeJsxElement(depth + 1)
            }
            char == '{' -> jsxExpression()
            char == '"' || char == '\'' -> {
                quotedString(char)
                previous = START
            }
            char == '=' -> {
                out.span(index, index + 1, RoleCode.OPERATOR)
                index += 1
            }
            isIdentifierStart(char) -> {
                var to = index + 1
                while (to < end && (isIdentifierPart(text[to]) || text[to] == '-' || text[to] == ':')) to += 1
                out.span(index, to, RoleCode.ATTRIBUTE)
                index = to
            }
            else -> index += 1
        }
    }

    /** Between elements' tags: plain text, `{…}` expressions, child tags, and closing tags. */
    private fun jsxChildren(depth: Int) {
        val char = text[index]
        val next = if (index + 1 < end) text[index + 1] else ' '
        when {
            char == '<' && next == '/' -> {
                out.span(index, index + 2, RoleCode.PUNCTUATION)
                var at = LexerScan.skipBlanks(text, index + 2, end)
                val nameEnd = tagNameEnd(at)
                out.span(at, nameEnd, RoleCode.TAG)
                at = LexerScan.skipBlanks(text, nameEnd, end)
                if (at < end && text[at] == '>') {
                    out.span(at, at + 1, RoleCode.PUNCTUATION)
                    at += 1
                }
                index = at
                closeJsxElement(depth)
            }
            char == '<' && next == '>' -> {
                out.span(index, index + 2, RoleCode.PUNCTUATION)
                index += 2
                setTop(JSX, minOf(depth + 1, DEPTH))
            }
            char == '<' && isAsciiLetter(next) -> {
                val nameEnd = tagNameEnd(index + 1)
                out.span(index, index + 1, RoleCode.PUNCTUATION)
                out.span(index + 1, nameEnd, RoleCode.TAG)
                index = nameEnd
                setTop(JSX, IN_TAG or depth)
            }
            char == '{' -> jsxExpression()
            else -> {
                var at = index + 1
                while (at < end && text[at] != '<' && text[at] != '{') at += 1
                index = at
            }
        }
    }

    /** One element closed where [depth] were open; the outermost returns to code. */
    private fun closeJsxElement(depth: Int) {
        if (depth <= 1) {
            pop()
            value()
        } else {
            setTop(JSX, depth - 1)
        }
    }

    private fun jsxExpression() {
        out.span(index, index + 1, RoleCode.PUNCTUATION)
        index += 1
        if (canPush()) {
            push(BRACE, 0)
            expressionStart()
        }
    }

    private fun tagNameEnd(from: Int): Int {
        var at = from
        while (at < end && (isIdentifierPart(text[at]) || text[at] == '.' || text[at] == '-' || text[at] == ':')) at += 1
        return at
    }

    // endregion

    private fun identifierEnd(from: Int): Int {
        var at = from
        while (at < end && isIdentifierPart(text[at])) at += 1
        return at
    }

    private fun isIdentifierStart(char: Char): Boolean = LexerScan.isWordStart(char) || (char.code >= 0x80 && char.isLetter())

    private fun isIdentifierPart(char: Char): Boolean = LexerScan.isWordPart(char) || (char.code >= 0x80 && char.isLetterOrDigit())

    private fun isOperator(char: Char): Boolean =
        char == '=' || char == '+' || char == '-' || char == '*' || char == '%' || char == '<' || char == '>' || char == '!' ||
            char == '&' || char == '|' || char == '^' || char == '~' || char == '?' || char == ':'

    private companion object {
        // Frame kinds (bits 5..7 of a frame byte); 0 is code outside any frame.
        const val NONE = 0
        const val BRACE = 1
        const val PAREN = 2
        const val STRING = 3
        const val RAW = 4
        const val RAW_MULTILINE = 5
        const val COMMENT = 6
        const val JSX = 7

        // STRING frame values.
        const val TEMPLATE = 1
        const val KOTLIN_LINE = 2
        const val KOTLIN_RAW = 3
        const val SWIFT_LINE = 4
        const val SWIFT_MULTILINE = 5

        // JSX frame value: inside a tag, and the number of open elements.
        const val IN_TAG = 16
        const val DEPTH = 15

        // Previous token classes.
        const val START = 0
        const val VALUE = 1
        const val DOT = 2
    }
}
