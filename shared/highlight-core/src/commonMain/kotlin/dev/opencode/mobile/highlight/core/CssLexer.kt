package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * CSS, tolerating the SCSS and Less people paste: nesting, `//` comments, `$` and `@` variables,
 * `#{}` interpolation, `%` placeholders, and `@include`-style at-rules.
 *
 * Roles: in selectors, element names, `*`, and `&` are Tag; a class, id, pseudo-class or
 * placeholder name is Attribute after its `.`, `#`, `:` or `%` Punctuation; an attribute
 * selector's name is Attribute and its value String; combinators are Operator. In declarations
 * the property is Property (a custom property or SCSS variable is Variable), and in values
 * numbers are Number with their unit (or `%`) Keyword, `#rgb` colors Constant, a name before
 * `(` Function (an unquoted `url()` String), `!important` Keyword, and `var()` names Variable.
 * At-rules (`@media`, `@include`) are Keyword, with `and`, `not`, `only`, `or` as Operator and
 * a media feature name Property. Strings are String with escapes Constant; comments Comment;
 * brackets, `:`, `;`, `,` Punctuation. Other value words stay plain.
 *
 * Selector or declaration: a statement starting outside a value is classified once by looking
 * ahead to the first `{`, `;`, or `}` on its line (skipping strings, comments, and `#{}`): `{`
 * makes it a selector, `;` or `}` a declaration when it has a `:`. Without either on the line,
 * a statement with a `:` is a declaration unless the line ends with `,`. So nesting needs no
 * brace depth, a bare list of declarations (a `style` snippet) colors as declarations, and the
 * look-ahead regions never overlap.
 *
 * States: [IN_COMMENT] plus what a line ends inside: a selector, a declaration's value, or an
 * at-rule's prelude (all continue on the next line); 0 is a statement start. A string ends with
 * its line.
 */
internal object CssLexer : HighlightLexer() {
    const val IN_COMMENT = 1
    private const val CONTEXT_SHIFT = 1
    const val STATEMENT = 0
    const val SELECTOR = 1
    const val VALUE = 2
    const val AT_RULE = 3

    // Per line only: a declaration's property, before its `:`.
    const val PROPERTY = 4

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int = CssLine(text, start, end, out).run(state)
}

/** Lexes one line of CSS; a new one per line keeps [CssLexer] stateless. */
private class CssLine(
    private val text: CharArray,
    start: Int,
    private val end: Int,
    private val out: SpanSink,
) {
    private var index = start
    private var context = CssLexer.STATEMENT
    private var inComment = false

    fun run(state: Int): Int {
        inComment = state and CssLexer.IN_COMMENT != 0
        context = state ushr 1
        while (index < end && !out.stopped) {
            if (inComment) {
                comment(index)
                continue
            }
            val char = text[index]
            when {
                char == ' ' || char == '\t' || char == '\r' || char == '\u000C' -> index += 1
                char == '/' && next(1) == '*' -> comment(index + 2, from = index)
                char == '/' && next(1) == '/' && context != CssLexer.VALUE -> lineComment()
                char == '/' && next(1) == '/' && index > 0 && isBlank(text[index - 1]) -> lineComment()
                else ->
                    when (context) {
                        CssLexer.STATEMENT -> statement()
                        CssLexer.SELECTOR -> selector()
                        CssLexer.PROPERTY -> property()
                        CssLexer.VALUE -> value()
                        else -> atRule()
                    }
            }
        }
        if (context == CssLexer.PROPERTY) context = CssLexer.STATEMENT
        return (context shl 1) or (if (inComment) CssLexer.IN_COMMENT else 0)
    }

    private fun next(offset: Int): Char = if (index + offset < end) text[index + offset] else '\u0000'

    private fun isBlank(char: Char): Boolean = char == ' ' || char == '\t'

    private fun lineComment() {
        out.span(index, end, RoleCode.COMMENT)
        index = end
    }

    /** A block comment from [from] whose body starts at [bodyFrom]. */
    private fun comment(
        bodyFrom: Int,
        from: Int = bodyFrom,
    ) {
        val close = LexerScan.blockCommentEnd(text, bodyFrom, end)
        if (close < 0) {
            out.span(from, end, RoleCode.COMMENT)
            index = end
            inComment = true
        } else {
            out.span(from, close, RoleCode.COMMENT)
            index = close
            inComment = false
        }
    }

    private fun statement() {
        when (text[index]) {
            '@' -> {
                val nameEnd = identEnd(index + 1)
                out.span(index, nameEnd, RoleCode.KEYWORD)
                index = nameEnd
                context = CssLexer.AT_RULE
            }
            '{', '}', ';' -> punctuation()
            else -> context = classify(index)
        }
    }

    /** Selector or declaration for a statement starting at [from]; see [CssLexer]. */
    private fun classify(from: Int): Int {
        var at = from
        var colon = false
        var lastSolid = ' '
        while (at < end) {
            val char = text[at]
            when {
                char == '"' || char == '\'' -> at = stringEnd(at)
                char == '/' && at + 1 < end && text[at + 1] == '*' -> {
                    val close = LexerScan.blockCommentEnd(text, at + 2, end)
                    if (close < 0) break
                    at = close
                }
                char == '#' && at + 1 < end && text[at + 1] == '{' -> {
                    at += 2
                    while (at < end && text[at] != '}') at += 1
                    at += 1
                }
                char == '{' -> return CssLexer.SELECTOR
                char == ';' || char == '}' -> return if (colon) CssLexer.PROPERTY else CssLexer.SELECTOR
                else -> {
                    if (char == ':') colon = true
                    at += 1
                }
            }
            if (char != ' ' && char != '\t' && char != '\r') lastSolid = char
        }
        return if (colon && lastSolid != ',') CssLexer.PROPERTY else CssLexer.SELECTOR
    }

    private fun punctuation() {
        val char = text[index]
        out.span(index, index + 1, RoleCode.PUNCTUATION)
        index += 1
        if (char == '{' || char == '}' || char == ';') context = CssLexer.STATEMENT
    }

    private fun selector() {
        val char = text[index]
        when {
            char == '{' || char == '}' || char == ';' -> punctuation()
            char == '.' || char == '%' -> prefixed(RoleCode.ATTRIBUTE)
            char == '#' && next(1) == '{' -> interpolation()
            char == '#' -> prefixed(RoleCode.ATTRIBUTE)
            char == ':' -> {
                val nameStart = if (next(1) == ':') index + 2 else index + 1
                out.span(index, nameStart, RoleCode.PUNCTUATION)
                val nameEnd = identEnd(nameStart)
                out.span(nameStart, nameEnd, RoleCode.ATTRIBUTE)
                index = nameEnd
            }
            char == '[' -> attributeSelector()
            char == '*' || char == '&' -> tagSpan(index + 1)
            char == '>' || char == '+' || char == '~' -> operator(index + 1)
            char == '(' || char == ')' || char == ',' -> {
                out.span(index, index + 1, RoleCode.PUNCTUATION)
                index += 1
            }
            char == '"' || char == '\'' -> string()
            isDigit(char) || (char == '.' && isDigit(next(1))) -> number()
            char == '$' || (char == '-' && next(1) == '-') -> variable()
            isIdentStart(index) -> tagSpan(identEnd(index))
            else -> index += 1
        }
    }

    private fun tagSpan(to: Int) {
        out.span(index, to, RoleCode.TAG)
        index = to
    }

    private fun operator(to: Int) {
        out.span(index, to, RoleCode.OPERATOR)
        index = to
    }

    /** `.name`, `#name`, `%name`: the sign Punctuation and the name [role]. */
    private fun prefixed(role: Int) {
        val nameEnd = identEnd(index + 1)
        out.span(index, index + 1, RoleCode.PUNCTUATION)
        out.span(index + 1, nameEnd, role)
        index = nameEnd
    }

    /** `[name op value flags]` on this line. */
    private fun attributeSelector() {
        out.span(index, index + 1, RoleCode.PUNCTUATION)
        index += 1
        while (index < end && text[index] != ']' && text[index] != '{' && !out.stopped) {
            val char = text[index]
            when {
                char == '"' || char == '\'' -> string()
                char == '=' || ((char == '~' || char == '|' || char == '^' || char == '$' || char == '*') && next(1) == '=') -> {
                    val to = if (char == '=') index + 1 else index + 2
                    operator(to)
                    val valueStart = LexerScan.skipBlanks(text, index, end)
                    if (valueStart < end && isIdentStart(valueStart)) {
                        val valueEnd = identEnd(valueStart)
                        out.span(valueStart, valueEnd, RoleCode.STRING)
                        index = valueEnd
                    }
                }
                isIdentStart(index) -> {
                    val nameEnd = identEnd(index)
                    out.span(index, nameEnd, RoleCode.ATTRIBUTE)
                    index = nameEnd
                }
                else -> index += 1
            }
        }
        if (index < end && text[index] == ']') {
            out.span(index, index + 1, RoleCode.PUNCTUATION)
            index += 1
        }
    }

    /** `#{ … }` (SCSS), to its `}` on this line. */
    private fun interpolation() {
        out.span(index, index + 2, RoleCode.PUNCTUATION)
        index += 2
        while (index < end && text[index] != '}') {
            if (text[index] == '$') variable() else index += 1
        }
        if (index < end) {
            out.span(index, index + 1, RoleCode.PUNCTUATION)
            index += 1
        }
    }

    private fun property() {
        val char = text[index]
        when {
            char == ':' -> {
                out.span(index, index + 1, RoleCode.PUNCTUATION)
                index += 1
                context = CssLexer.VALUE
            }
            char == ';' || char == '{' || char == '}' -> punctuation()
            char == '#' && next(1) == '{' -> interpolation()
            char == '$' || (char == '-' && next(1) == '-') || char == '@' -> variable()
            isIdentStart(index) -> {
                val nameEnd = identEnd(index)
                out.span(index, nameEnd, RoleCode.PROPERTY)
                index = nameEnd
            }
            else -> index += 1
        }
    }

    private fun value() {
        val char = text[index]
        when {
            char == ';' || char == '{' || char == '}' -> punctuation()
            char == '"' || char == '\'' -> string()
            char == '#' && next(1) == '{' -> interpolation()
            char == '#' -> color()
            char == '!' -> important()
            char == '$' || char == '@' || (char == '-' && next(1) == '-') -> variable()
            isDigit(char) || (char == '.' && isDigit(next(1))) -> number()
            (char == '-' || char == '+') && (isDigit(next(1)) || (next(1) == '.' && isDigit(next(2)))) -> number()
            isIdentStart(index) -> word()
            char == ',' || char == '(' || char == ')' || char == ':' -> {
                out.span(index, index + 1, RoleCode.PUNCTUATION)
                index += 1
            }
            char == '+' || char == '-' || char == '*' || char == '/' || char == '=' || char == '<' || char == '>' -> operator(index + 1)
            else -> index += 1
        }
    }

    private fun atRule() {
        val char = text[index]
        when {
            char == '{' || char == ';' || char == '}' -> punctuation()
            char == '"' || char == '\'' -> string()
            char == '#' && next(1) == '{' -> interpolation()
            char == '$' || char == '@' || (char == '-' && next(1) == '-') -> variable()
            isDigit(char) || (char == '.' && isDigit(next(1))) -> number()
            (char == '-' || char == '+') && isDigit(next(1)) -> number()
            isIdentStart(index) -> {
                val wordEnd = identEnd(index)
                val after = LexerScan.skipBlanks(text, wordEnd, end)
                val role =
                    when {
                        wordEnd < end && text[wordEnd] == '(' -> RoleCode.FUNCTION
                        after < end && text[after] == ':' -> RoleCode.PROPERTY
                        isLogical(index, wordEnd) -> RoleCode.OPERATOR
                        else -> 0
                    }
                if (wordEnd < end && text[wordEnd] == '(' && isUrl(index, wordEnd)) {
                    url(wordEnd)
                } else {
                    if (role != 0) out.span(index, wordEnd, role)
                    index = wordEnd
                }
            }
            char == ',' || char == '(' || char == ')' || char == ':' -> {
                out.span(index, index + 1, RoleCode.PUNCTUATION)
                index += 1
            }
            char == '=' || char == '<' || char == '>' -> operator(index + 1)
            else -> index += 1
        }
    }

    private fun isLogical(
        from: Int,
        to: Int,
    ): Boolean =
        LexerScan.equals(text, from, to, "and") ||
            LexerScan.equals(text, from, to, "not") ||
            LexerScan.equals(text, from, to, "only") ||
            LexerScan.equals(text, from, to, "or")

    private fun isUrl(
        from: Int,
        to: Int,
    ): Boolean = to - from == 3 && (text[from] == 'u' || text[from] == 'U') && (text[from + 1] == 'r' || text[from + 1] == 'R') && (text[from + 2] == 'l' || text[from + 2] == 'L')

    /** A value word: a function name before `(`, or plain. */
    private fun word() {
        val wordEnd = identEnd(index)
        if (wordEnd < end && text[wordEnd] == '(') {
            if (isUrl(index, wordEnd)) {
                url(wordEnd)
                return
            }
            out.span(index, wordEnd, RoleCode.FUNCTION)
        }
        index = wordEnd
    }

    /** `url(` at [open]: the name Function, an unquoted address String up to `)`. */
    private fun url(open: Int) {
        out.span(index, open, RoleCode.FUNCTION)
        out.span(open, open + 1, RoleCode.PUNCTUATION)
        index = LexerScan.skipBlanks(text, open + 1, end)
        if (index < end && (text[index] == '"' || text[index] == '\'')) return
        var close = index
        while (close < end && text[close] != ')') close += 1
        var contentEnd = close
        while (contentEnd > index && isBlank(text[contentEnd - 1])) contentEnd -= 1
        out.span(index, contentEnd, RoleCode.STRING)
        index = close
    }

    private fun color() {
        var at = index + 1
        while (at < end && isHexDigit(text[at])) at += 1
        val digits = at - index - 1
        if ((digits == 3 || digits == 4 || digits == 6 || digits == 8) && !(at < end && isIdentPart(text[at]))) {
            out.span(index, at, RoleCode.CONSTANT)
            index = at
        } else {
            index = identEnd(index + 1)
        }
    }

    private fun important() {
        val wordStart = LexerScan.skipBlanks(text, index + 1, end)
        val wordEnd = identEnd(wordStart)
        if (wordEnd > wordStart) {
            out.span(index, wordEnd, RoleCode.KEYWORD)
            index = wordEnd
        } else {
            index += 1
        }
    }

    private fun variable() {
        val nameEnd = identEnd(index + 1)
        out.span(index, nameEnd, RoleCode.VARIABLE)
        index = nameEnd
    }

    private fun number() {
        var at = index
        if (text[at] == '+' || text[at] == '-') at += 1
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
        out.span(index, at, RoleCode.NUMBER)
        index = at
        val unitEnd =
            when {
                at < end && text[at] == '%' -> at + 1
                at < end && isIdentStart(at) -> identEnd(at)
                else -> at
            }
        if (unitEnd > at) out.span(at, unitEnd, RoleCode.KEYWORD)
        index = unitEnd
    }

    private fun string() {
        val close = stringEnd(index)
        if (out.collecting) {
            var segment = index
            var at = index + 1
            while (at < close) {
                if (text[at] == '\\' && at + 1 < close) {
                    // `\` and one character, or up to six hex digits and one optional space.
                    var escapeEnd = at + 1
                    while (escapeEnd < close && escapeEnd - at <= 6 && isHexDigit(text[escapeEnd])) escapeEnd += 1
                    if (escapeEnd == at + 1) {
                        escapeEnd = at + 2
                    } else if (escapeEnd < close && text[escapeEnd] == ' ') {
                        escapeEnd += 1
                    }
                    out.span(segment, at, RoleCode.STRING)
                    out.span(at, escapeEnd, RoleCode.CONSTANT)
                    at = escapeEnd
                    segment = at
                } else {
                    at += 1
                }
            }
            out.span(segment, close, RoleCode.STRING)
        } else {
            out.span(index, close, RoleCode.STRING)
        }
        index = close
    }

    /** The offset after the string opening at [open], or the line end. */
    private fun stringEnd(open: Int): Int {
        val quote = text[open]
        var at = open + 1
        while (at < end) {
            val char = text[at]
            if (char == '\\') {
                at += 2
            } else {
                at += 1
                if (char == quote) return at
            }
        }
        return end
    }

    private fun isIdentPart(char: Char): Boolean = isAsciiLetter(char) || isDigit(char) || char == '-' || char == '_' || char.code >= 0x80

    private fun isIdentStart(at: Int): Boolean {
        val char = text[at]
        if (isAsciiLetter(char) || char == '_' || char.code >= 0x80 || char == '\\') return true
        if (char != '-' || at + 1 >= end) return false
        val after = text[at + 1]
        return isAsciiLetter(after) || after == '_' || after == '-' || after.code >= 0x80
    }

    /** The end of the identifier starting at [from] (escapes included); [from] when none starts there. */
    private fun identEnd(from: Int): Int {
        var at = from
        while (at < end) {
            val char = text[at]
            if (char == '\\') {
                at = minOf(at + 2, end)
            } else if (isIdentPart(char)) {
                at += 1
            } else {
                break
            }
        }
        return at
    }
}
