package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * The shared lexer for C-like languages, configured per language by a [CLikeSyntax]: TypeScript
 * and JavaScript (with JSX), Kotlin, Swift, Rust, Go, Java, C, C++, C#, Dart, Scala, Groovy,
 * Objective-C, Zig, and Apex.
 *
 * Roles: keywords from the syntax's word table (contextual ones only before something a keyword
 * can precede), strings with escapes as Constant and interpolation delimiters as Punctuation,
 * comments, numbers, true/false/null-like Constant, Type for capitalized identifiers and the
 * names after `class`-like keywords, Function for an identifier before `(` or after `fun`-like
 * keywords, Property after `.` (and for object keys where the syntax has them), Attribute for
 * `@` annotations (and Rust `#[…]`, C++ `[[…]]`, C# line-leading `[…]` attributes), Operator and
 * Punctuation. Plain identifiers take the syntax's identifier role. C-family `#` directives are a
 * Punctuation `#` and a Keyword name; Rust macros (`name!`) are Functions and lifetimes Types;
 * printf-style placeholders in Go and C strings are Constant. Dart, Scala, and Groovy strings mark
 * `$name` and `${…}` interpolations; Objective-C `@` directives are Keywords and selector parts
 * in a `[…]` message or a method declaration Functions; Zig `@builtin`s are Functions and `\`
 * lines Strings; Apex keywords match in any case, and a `[SELECT …]` query lexes as SOQL.
 *
 * Everything still open at a line end is packed into the state as a stack of up to four frames,
 * one byte each, innermost in the low byte: a block comment (nesting depth), a string that can
 * span lines or holds an interpolation (its kind, or a Swift raw string's `#` count), the code of
 * an interpolation or JSX expression (its open brace or paren depth), and a JSX element (inside a
 * tag, and how many elements are open), a C or C++ directive continued with a trailing `\`, and
 * an Apex SOQL query (its `[` depth).
 * A C++ raw string is the one frame that uses the whole state: its delimiter's length and a hash
 * of it ride above the frame byte, and it only opens with nothing else open. State 0 is code at
 * the top of a document. Past four frames a construct degrades instead of nesting: a string or
 * comment ends with its line, an interpolation stays string text, and `<` is an operator.
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
    /** Capitalized identifiers of capitals and digits only (`MAX`, `EOF`) are constants too, not types. */
    val allCapsConstants: Boolean = false,
    /** `/* … */` comments exist (all but Zig). */
    val blockComments: Boolean = true,
    /** Words match in any ASCII case (Apex). */
    caseInsensitive: Boolean = false,
    /** `#!` on the first line is a comment. */
    val shebang: Boolean = hash == HASH_PRIVATE,
    /** Words inside a `[SELECT …]` or `[FIND …]` query (Apex SOQL and SOSL); null where there are no queries. */
    soqlWords: Map<String, Int>? = null,
) {
    val words = WordTable(words, caseInsensitive)

    val soqlWords: WordTable? = soqlWords?.let { WordTable(it, caseInsensitive = true) }

    val objc: Boolean = strings == STRINGS_OBJC

    /** `name!` is a macro call (Rust). */
    val macros: Boolean = strings == STRINGS_RUST

    /** C and C++: `_t` names are types, `'` separates digits, and strings continue after a trailing `\`. */
    val cFamily: Boolean = strings == STRINGS_C || strings == STRINGS_CPP || strings == STRINGS_OBJC

    /** `%d`-style placeholders in double-quoted strings are Constant. */
    val placeholders: Boolean = cFamily || strings == STRINGS_GO

    companion object {
        /** `'` and `"` single-line strings, backtick templates with `${}`. */
        const val STRINGS_JS = 0

        /** `"` with `$name` and `${}`, raw `"""` across lines, `'` characters, backtick names. */
        const val STRINGS_KOTLIN = 1

        /** `"` and `"""` with `\()`, raw `#"…"#` strings, backtick names. */
        const val STRINGS_SWIFT = 2

        /** `"` across lines with `{}` format braces, `b`/`c` prefixes, raw `r#"…"#`, `'c'` characters and `'a` lifetimes. */
        const val STRINGS_RUST = 3

        /** `"` on one line, raw backtick strings across lines, `'c'` runes. */
        const val STRINGS_GO = 4

        /** `"` on one line, `"""` text blocks across lines, `'c'` characters. */
        const val STRINGS_JAVA = 5

        /** `"` continued by a trailing `\`, `L`/`u`/`U`/`u8` prefixes, `'c'` characters. */
        const val STRINGS_C = 6

        /** [STRINGS_C] plus raw `R"delimiter(…)delimiter"` strings across lines. */
        const val STRINGS_CPP = 7

        /** `"` on one line, verbatim `@"…"` and raw `"""…"""` across lines, `$` interpolation in any of them. */
        const val STRINGS_CSHARP = 8

        /** `'` and `"` with `$name` and `${}`, `'''` and `"""` across lines, raw `r'…'` strings. */
        const val STRINGS_DART = 9

        /** `"` on one line, raw `"""` across lines, `s"…"`-style interpolators with `$name` and `${}`, `'c'` and `'symbol`. */
        const val STRINGS_SCALA = 10

        /** `'` plain, `"` GStrings with `$name` and `${}`, triple quotes across lines, `/slashy/` and `$/dollar slashy/$`. */
        const val STRINGS_GROOVY = 11

        /** [STRINGS_C] plus `@"…"` strings and `@` literals and directives. */
        const val STRINGS_OBJC = 12

        /** `"` on one line, `'c'` characters, and `\\` multiline string lines. */
        const val STRINGS_ZIG = 13

        /** `'` strings on one line; `"` is not a string. */
        const val STRINGS_APEX = 14

        const val HASH_NONE = 0

        /** `#name` is a private name (JavaScript); `#!` on the first line is a comment. */
        const val HASH_PRIVATE = 1

        /** `#name` is a directive keyword and `#"` opens a raw string (Swift). */
        const val HASH_SWIFT = 2

        /** `#[…]` and `#![…]` are attributes (Rust). */
        const val HASH_RUST = 3

        /** `#` first on a line starts a preprocessor directive (C, C++, C#). */
        const val HASH_DIRECTIVE = 4

        /** `#name` is a symbol literal, a Constant (Dart). */
        const val HASH_SYMBOL = 5

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
    /** Keys match in any ASCII case; they are stored in lower case. */
    private val caseInsensitive: Boolean = false,
) {
    private val size = tableSize(entries.size)
    private val keys = arrayOfNulls<String>(size)
    private val values = IntArray(size)
    private val maxLength = entries.keys.maxOfOrNull { it.length } ?: 0

    init {
        for ((entry, value) in entries) {
            val word = if (caseInsensitive) entry.lowercase() else entry
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
        for (index in from until to) hash = hash * 31 + fold(text[index]).code
        var slot = hash and (size - 1)
        while (true) {
            val key = keys[slot] ?: return 0
            if (matches(text, from, to, key)) return values[slot]
            slot = (slot + 1) and (size - 1)
        }
    }

    private fun matches(
        text: CharArray,
        from: Int,
        to: Int,
        key: String,
    ): Boolean {
        if (!caseInsensitive) return LexerScan.equals(text, from, to, key)
        if (to - from != key.length) return false
        for (index in key.indices) if (fold(text[from + index]) != key[index]) return false
        return true
    }

    private fun fold(char: Char): Char = if (caseInsensitive && char in 'A'..'Z') char + 32 else char

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

    // 1 bits for `[` among the innermost brackets (Objective-C messages), in step with [brackets].
    private var squares = 0L

    // An Objective-C method declaration (`- (void)name:…`) and whether its first name is still ahead.
    private var methodLine = false
    private var methodNamePending = false

    // Where a directive or a C# attribute may start: the first non-blank, on a line begun in code.
    private var firstNonBlank = -1

    fun run(state: Int): Int {
        stack = state
        if (lineStart == 0 && state == 0 && syntax.shebang && LexerScan.startsWith(text, 0, end, "#!")) {
            out.span(0, end, RoleCode.COMMENT)
            return 0
        }
        if (state == 0) {
            firstNonBlank = LexerScan.skipBlanks(text, lineStart, end)
            if (syntax.objc && firstNonBlank < end && (text[firstNonBlank] == '-' || text[firstNonBlank] == '+')) {
                val paren = LexerScan.skipBlanks(text, firstNonBlank + 1, end)
                methodLine = paren < end && text[paren] == '('
                methodNamePending = methodLine
            }
        }
        while (index < end && !out.stopped) {
            val top = stack and 0xFF
            when (top ushr 5) {
                NONE, BRACE, PAREN -> code()
                STRING ->
                    when (val kind = top and 31) {
                        CPP_RAW -> cppRawString()
                        in TEMPLATE..SWIFT_MULTILINE -> string(kind)
                        in DART_SINGLE..GROOVY_DOLLAR_SLASHY -> scriptString(kind)
                        else -> familyString(kind)
                    }
                RAW -> rawString(top and 31, multiline = false)
                RAW_MULTILINE ->
                    when (syntax.strings) {
                        CLikeSyntax.STRINGS_RUST -> rustRawString(top and 31)
                        CLikeSyntax.STRINGS_CSHARP -> cSharpRawString(top and 31)
                        else -> rawString(top and 31, multiline = true)
                    }
                COMMENT -> comment(top and 31)
                JSX -> if (top and IN_TAG != 0) jsxTag(top and DEPTH) else jsxChildren(top and DEPTH)
            }
        }
        // Single-line strings end with their line; an unterminated one leaves nothing open.
        val top = stack and 0xFF
        val kind = top ushr 5
        val value = top and 31
        if (kind == RAW || (kind == STRING && (value == KOTLIN_LINE || value == SWIFT_LINE || value == CS_INTERPOLATED || isLineScriptString(value)))) {
            pop()
        } else if (kind == STRING && value == C_LINE && !escapedLineEnd()) {
            pop()
        }
        // A directive goes on to the next line only after a trailing `\`.
        if ((stack and 0xFF) == DIRECTIVE && (end == lineStart || text[end - 1] != '\\')) pop()
        return stack
    }

    /** True when the line ends in an odd run of backslashes: a string escapes its line end. */
    private fun escapedLineEnd(): Boolean {
        var at = end
        while (at > lineStart && text[at - 1] == '\\') at -= 1
        return (end - at) % 2 == 1
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
            char == '\\' && syntax.strings == CLikeSyntax.STRINGS_ZIG && index + 1 < end && text[index + 1] == '\\' -> {
                // A Zig multiline string line: `\\` to the line end.
                out.span(index, end, RoleCode.STRING)
                index = end
                value()
            }
            isDigit(char) -> number()
            char == '.' -> dot()
            char == '{' || char == '}' || char == '(' || char == ')' || char == '[' || char == ']' -> bracket(char)
            char == ';' || char == ',' -> separator()
            char == '@' -> at()
            char == '#' -> hash()
            char == '$' && syntax.strings == CLikeSyntax.STRINGS_CSHARP -> cSharpDollar()
            char == '$' && syntax.strings == CLikeSyntax.STRINGS_GROOVY && previous == START && index + 1 < end && text[index + 1] == '/' ->
                openScriptString(GROOVY_DOLLAR_SLASHY)
            char == '<' && syntax.jsx && previous == START && jsxStart() -> Unit
            isOperator(char) -> operator()
            isIdentifierStart(char) -> {
                val to = identifierEnd(index + 1)
                if (!prefixedLiteral(index, to)) word(index, to)
            }
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
            next == '*' && syntax.blockComments -> {
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
            syntax.strings == CLikeSyntax.STRINGS_GROOVY && previous == START && regexAllowed && slashy() -> Unit
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

    /** A Groovy `/slashy/` string from the `/` at [index], when one closes on this line; false otherwise. */
    private fun slashy(): Boolean {
        var at = index + 1
        while (at < end) {
            val char = text[at]
            if (char == '\\') {
                at += 2
                continue
            }
            if (char == '/') {
                openScriptString(GROOVY_SLASHY)
                return true
            }
            at += 1
        }
        regexAllowed = false
        return false
    }

    private fun number() {
        var at = digitsEnd(index)
        if (at + 1 < end && text[at] == '.' && isDigit(text[at + 1])) at = digitsEnd(at + 1)
        if (at < end && (text[at] == 'e' || text[at] == 'E')) {
            var exponent = at + 1
            if (exponent < end && (text[exponent] == '+' || text[exponent] == '-')) exponent += 1
            if (exponent < end && isDigit(text[exponent])) at = exponent
        }
        // Prefixes after a leading 0 (0x, 0b, 0o), hex digits, and suffixes (n, L, f, u, i32) ride
        // along, and so do a hex float's fraction and `p` exponent (`0x1.8p-3`) where there are some.
        val hex = text[index] == '0' && index + 1 < end && (text[index + 1] == 'x' || text[index + 1] == 'X')
        val hexFloats =
            hex &&
                (
                    syntax.cFamily || syntax.strings == CLikeSyntax.STRINGS_GO || syntax.strings == CLikeSyntax.STRINGS_JAVA ||
                        syntax.strings == CLikeSyntax.STRINGS_ZIG
                )
        while (at < end) {
            val char = text[at]
            at +=
                when {
                    isAsciiLetter(char) || isDigit(char) || char == '_' ->
                        if (hexFloats && (char == 'p' || char == 'P') && at + 2 < end && (text[at + 1] == '+' || text[at + 1] == '-') && isDigit(text[at + 2])) 3 else 1
                    hexFloats && char == '.' && at + 1 < end && (isHexDigit(text[at + 1]) || text[at + 1] == 'p' || text[at + 1] == 'P') -> 1
                    syntax.cFamily && char == '\'' && at + 1 < end && isHexDigit(text[at + 1]) -> 1
                    else -> break
                }
        }
        out.span(index, at, RoleCode.NUMBER)
        index = at
        value()
    }

    /** The end of a run of digits from [from], with `_` separators, and C's `'` ones between digits. */
    private fun digitsEnd(from: Int): Int {
        var at = from
        while (at < end) {
            val char = text[at]
            if (isDigit(char) || char == '_' || (syntax.cFamily && char == '\'' && at > from && at + 1 < end && isDigit(text[at + 1]))) at += 1 else break
        }
        return at
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
                out.span(index, index + 1, if (syntax.strings == CLikeSyntax.STRINGS_RUST) RoleCode.OPERATOR else RoleCode.PUNCTUATION)
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
                        closeRawInterpolation()
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
                if (attributeBracket() || soqlStart()) return
                if (kind == NONE && top >= SOQL) setTop(NONE, minOf(top + 1, SOQL + SOQL_DEPTH))
                openBracket(1, square = true)
                expressionStart()
            }
            else -> {
                if (kind == NONE && top >= SOQL) {
                    if (top == SOQL) {
                        // Closes an Apex query.
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                        pop()
                        value()
                        return
                    }
                    setTop(NONE, top - 1)
                }
                closeBracket()
                value()
            }
        }
        out.span(index, index + 1, RoleCode.PUNCTUATION)
        index += 1
    }

    /** C++ `[[name` and C# `[Name` first on a line: the bracket and the attribute's name; false when this `[` is neither. */
    private fun attributeBracket(): Boolean {
        val cpp = syntax.strings == CLikeSyntax.STRINGS_CPP && index + 1 < end && text[index + 1] == '['
        val cSharp = syntax.strings == CLikeSyntax.STRINGS_CSHARP && index == firstNonBlank && stack == 0
        if (!cpp && !cSharp) return false
        val brackets = if (cpp) 2 else 1
        out.span(index, index + brackets, RoleCode.PUNCTUATION)
        repeat(brackets) { openBracket(1) }
        index += brackets
        expressionStart()
        val name = LexerScan.skipBlanks(text, index, end)
        if (name < end && isIdentifierStart(text[name])) {
            // `gnu::cold`, `System.Obsolete`: a qualified name.
            var to = identifierEnd(name + 1)
            while (true) {
                val separator = if (cpp && LexerScan.startsWith(text, to, end, "::")) 2 else if (!cpp && to < end && text[to] == '.') 1 else 0
                if (separator == 0 || to + separator >= end || !isIdentifierStart(text[to + separator])) break
                to = identifierEnd(to + separator + 1)
            }
            out.span(name, to, RoleCode.ATTRIBUTE)
            index = to
        }
        return true
    }

    /** After the `}` that closes a C# `$$"""` interpolation: the rest of its closing braces. */
    private fun closeRawInterpolation() {
        val top = stack and 0xFF
        if (syntax.strings != CLikeSyntax.STRINGS_CSHARP || top ushr 5 != RAW_MULTILINE) return
        var extra = ((top and 31) ushr 3) - 1
        var to = index
        while (extra > 0 && to < end && text[to] == '}') {
            to += 1
            extra -= 1
        }
        out.span(index, to, RoleCode.PUNCTUATION)
        index = to
    }

    /** An Apex `[SELECT` or `[FIND` at [index]: the `[` and a query frame; false when this `[` is neither. */
    private fun soqlStart(): Boolean {
        if (syntax.soqlWords == null || !canPush()) return false
        val word = LexerScan.skipBlanks(text, index + 1, end)
        if (word >= end || !isAsciiLetter(text[word])) return false
        var to = word + 1
        while (to < end && to - word <= 6 && isIdentifierPart(text[to])) to += 1
        if (!wordIs(word, to, "select") && !wordIs(word, to, "find")) return false
        out.span(index, index + 1, RoleCode.PUNCTUATION)
        index += 1
        push(NONE, SOQL)
        expressionStart()
        return true
    }

    /** True when `text[from, to)` is [lower] in any ASCII case. */
    private fun wordIs(
        from: Int,
        to: Int,
        lower: String,
    ): Boolean {
        if (to - from != lower.length) return false
        for (offset in lower.indices) {
            val char = text[from + offset]
            if ((if (char in 'A'..'Z') char + 32 else char) != lower[offset]) return false
        }
        return true
    }

    private fun inSoql(): Boolean = syntax.soqlWords != null && (stack and 0xFF) in SOQL..SOQL + SOQL_DEPTH

    private fun openBracket(
        bit: Long,
        square: Boolean = false,
    ) {
        if (bracketDepth < 63) {
            brackets = (brackets shl 1) or bit
            squares = (squares shl 1) or (if (square) 1L else 0L)
        }
        bracketDepth += 1
    }

    private fun closeBracket() {
        if (bracketDepth == 0) return
        bracketDepth -= 1
        if (bracketDepth < 63) {
            brackets = brackets ushr 1
            squares = squares ushr 1
        }
    }

    /** The innermost bracket open on this line is a `[`. */
    private fun inSquare(): Boolean = bracketDepth in 1..63 && (squares and 1L) != 0L

    private fun separator() {
        out.span(index, index + 1, RoleCode.PUNCTUATION)
        index += 1
        expressionStart()
        // A key may follow unless the innermost bracket on this line is `(` or `[`.
        keyAllowed = bracketDepth == 0 || bracketDepth > 63 || (brackets and 1L) == 0L
    }

    private fun operator() {
        if (syntax.strings == CLikeSyntax.STRINGS_CPP && LexerScan.startsWith(text, index, end, "::")) {
            // C++ scope resolution is punctuation, apart from the operators around it.
            out.span(index, index + 2, RoleCode.PUNCTUATION)
            index += 2
            expressionStart()
            return
        }
        var at = index + 1
        while (at < end && isOperator(text[at])) at += 1
        // A lone `:` is punctuation, except in Rust, where Shiki and TextMate both call it an operator.
        val colonOperator = syntax.strings == CLikeSyntax.STRINGS_RUST || syntax.strings == CLikeSyntax.STRINGS_SCALA
        val role = if (at - index == 1 && text[index] == ':' && !colonOperator) RoleCode.PUNCTUATION else RoleCode.OPERATOR
        out.span(index, at, role)
        index = at
        expressionStart()
    }

    /** `@name`, `@a.b`, `@file:Name`: an annotation, decorator, or attribute. A label (`return@x`) stays plain. */
    private fun at() {
        when (syntax.strings) {
            CLikeSyntax.STRINGS_CSHARP -> {
                cSharpAt()
                return
            }
            CLikeSyntax.STRINGS_RUST -> {
                // A pattern binding: `n @ 1..=9`.
                out.span(index, index + 1, RoleCode.OPERATOR)
                index += 1
                expressionStart()
                return
            }
            CLikeSyntax.STRINGS_GO, CLikeSyntax.STRINGS_C, CLikeSyntax.STRINGS_CPP -> {
                index += 1
                return
            }
            CLikeSyntax.STRINGS_OBJC -> {
                objcAt()
                return
            }
            CLikeSyntax.STRINGS_ZIG -> {
                zigAt()
                return
            }
        }
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
            CLikeSyntax.HASH_RUST -> rustAttribute()
            CLikeSyntax.HASH_SYMBOL ->
                if (index + 1 < end && isIdentifierStart(text[index + 1])) {
                    // A Dart symbol, `#name` or `#a.b`.
                    var to = identifierEnd(index + 2)
                    while (to + 1 < end && text[to] == '.' && isIdentifierStart(text[to + 1])) to = identifierEnd(to + 2)
                    out.span(index, to, RoleCode.CONSTANT)
                    index = to
                    value()
                } else {
                    index += 1
                }
            CLikeSyntax.HASH_DIRECTIVE ->
                if (index == firstNonBlank && stack == 0) {
                    directive()
                } else {
                    // `#` and `##` in a macro body.
                    val to = if (index + 1 < end && text[index + 1] == '#') index + 2 else index + 1
                    out.span(index, to, RoleCode.OPERATOR)
                    index = to
                    expressionStart()
                }
            else -> index += 1
        }
    }

    /** `#[name …]` or `#![name …]`: the opening as Punctuation and the attribute's path; its arguments lex as code. */
    private fun rustAttribute() {
        var at = index + 1
        if (at < end && text[at] == '!') at += 1
        if (at >= end || text[at] != '[') {
            index += 1
            return
        }
        out.span(index, at + 1, RoleCode.PUNCTUATION)
        openBracket(1)
        index = at + 1
        expressionStart()
        val name = LexerScan.skipBlanks(text, index, end)
        if (name < end && isIdentifierStart(text[name])) {
            var to = identifierEnd(name + 1)
            while (LexerScan.startsWith(text, to, end, "::") && to + 2 < end && isIdentifierStart(text[to + 2])) to = identifierEnd(to + 3)
            out.span(name, to, RoleCode.ATTRIBUTE)
            index = to
        }
    }

    /**
     * A preprocessor directive at the line's first non-blank: `#` as Punctuation and the name as a
     * Keyword, then by name: an `#include <file>` String, a `#define`d name (Function when a `(`
     * follows at once, else Constant), an `#error` message String, a C# `#region` title as plain
     * text. Anything else lexes as code. In C and C++ the directive frame stays open while lines
     * end in `\`, so a `#` on a continued line is an operator.
     */
    private fun directive() {
        val nameStart = LexerScan.skipBlanks(text, index + 1, end)
        val nameEnd = if (nameStart < end && isAsciiLetter(text[nameStart])) identifierEnd(nameStart + 1) else nameStart
        out.span(index, index + 1, RoleCode.PUNCTUATION)
        out.span(nameStart, nameEnd, RoleCode.KEYWORD)
        index = nameEnd
        expressionStart()
        val cSharp = syntax.strings == CLikeSyntax.STRINGS_CSHARP
        if (!cSharp) stack = DIRECTIVE
        val at = LexerScan.skipBlanks(text, index, end)
        if (at >= end) return
        fun named(word: String) = LexerScan.equals(text, nameStart, nameEnd, word)
        when {
            !cSharp && (named("include") || named("include_next") || named("import")) && text[at] == '<' -> {
                var close = at + 1
                while (close < end && text[close] != '>') close += 1
                val to = minOf(close + 1, end)
                out.span(at, to, RoleCode.STRING)
                index = to
            }
            named("define") && isIdentifierStart(text[at]) -> {
                val to = identifierEnd(at + 1)
                out.span(at, to, if (to < end && text[to] == '(') RoleCode.FUNCTION else RoleCode.CONSTANT)
                index = to
            }
            named("error") || named("warning") -> {
                out.span(at, end, RoleCode.STRING)
                index = end
            }
            cSharp && !named("if") && !named("elif") && !named("define") && !named("undef") -> index = end
        }
    }

    private fun word(
        from: Int,
        to: Int,
    ) {
        if (previous == DOT && syntax.strings == CLikeSyntax.STRINGS_RUST && LexerScan.equals(text, from, to, "await")) {
            out.span(from, to, RoleCode.KEYWORD)
            index = to
            value()
            return
        }
        if (previous != DOT) {
            val soql = syntax.soqlWords
            var entry = if (soql != null && inSoql()) soql.find(text, from, to) else syntax.words.find(text, from, to)
            if (entry == 0 && syntax.strings == CLikeSyntax.STRINGS_ZIG && isZigIntegerType(from, to)) entry = RoleCode.KEYWORD
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

    /** Zig's arbitrary-width integer types: `u7`, `i48`. */
    private fun isZigIntegerType(
        from: Int,
        to: Int,
    ): Boolean {
        if (to - from < 2 || to - from > 6 || (text[from] != 'u' && text[from] != 'i')) return false
        for (at in from + 1 until to) if (!isDigit(text[at])) return false
        return true
    }

    /**
     * An Objective-C selector part: after a value, the name before `:` or the closing `]` of a
     * `[receiver message]`, or on a method declaration line the first name and every `name:`.
     */
    private fun isSelector(
        from: Int,
        to: Int,
    ): Boolean {
        if (!syntax.objc || previous != VALUE) return false
        val at = LexerScan.skipBlanks(text, to, end)
        val char = if (at < end) text[at] else ' '
        val colon = char == ':' && (at + 1 >= end || text[at + 1] != ':')
        return when {
            inSquare() -> colon || (char == ']' && text[from - 1] != ')')
            methodLine && bracketDepth == 0 -> methodNamePending || colon
            else -> false
        }
    }

    private fun identifier(
        from: Int,
        to: Int,
    ) {
        val next = if (to < end) text[to] else ' '
        val selector = isSelector(from, to)
        if (bracketDepth == 0) methodNamePending = false
        if (syntax.macros && next == '!' && (to + 1 >= end || text[to + 1] != '=')) {
            // A Rust macro call, `!` included.
            out.span(from, to + 1, RoleCode.FUNCTION)
            index = to + 1
            expressionStart()
            return
        }
        // Inside an Apex query, capitalized names are fields and objects, not types.
        val capitalized = syntax.capitalizedTypes && text[from] in 'A'..'Z' && !inSoql()
        val role =
            when {
                selector -> RoleCode.FUNCTION
                // In Rust a capitalized name before `(` is a tuple struct or variant: `Some(x)`.
                next == '(' && declaring != CLikeSyntax.DECLARES_TYPE && !(capitalized && syntax.strings == CLikeSyntax.STRINGS_RUST) -> RoleCode.FUNCTION
                capitalized && previous == DOT -> if (isConstantCase(from, to)) RoleCode.CONSTANT else RoleCode.TYPE
                previous == DOT -> RoleCode.PROPERTY
                declaring == CLikeSyntax.DECLARES_FUNCTION && next != '.' -> RoleCode.FUNCTION
                declaring == CLikeSyntax.DECLARES_TYPE -> RoleCode.TYPE
                syntax.objectKeys && keyAllowed && keyColonFollows(to) -> RoleCode.PROPERTY
                capitalized -> if (isConstantCase(from, to)) RoleCode.CONSTANT else RoleCode.TYPE
                syntax.cFamily && to - from > 2 && text[to - 2] == '_' && text[to - 1] == 't' -> RoleCode.TYPE
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

    /** `MAX_SIZE`: capitals, digits, and at least one underscore; or, where all caps are constants, `EOF`. */
    private fun isConstantCase(
        from: Int,
        to: Int,
    ): Boolean {
        var underscore = false
        for (at in from until to) {
            val char = text[at]
            if (char == '_') underscore = true else if (char !in 'A'..'Z' && !isDigit(char)) return false
        }
        return underscore || (syntax.allCapsConstants && to - from > 1 && text[from + 1] in 'A'..'Z')
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
            CLikeSyntax.STRINGS_JS, CLikeSyntax.STRINGS_GO -> quotedString('"')
            CLikeSyntax.STRINGS_KOTLIN -> openString(if (isTripleQuote(index)) KOTLIN_RAW else KOTLIN_LINE)
            CLikeSyntax.STRINGS_SWIFT -> openString(if (isTripleQuote(index)) SWIFT_MULTILINE else SWIFT_LINE)
            CLikeSyntax.STRINGS_RUST -> openString(RUST)
            CLikeSyntax.STRINGS_JAVA -> if (isTripleQuote(index)) openString(JAVA_TEXT_BLOCK) else quotedString('"')
            CLikeSyntax.STRINGS_CSHARP -> cSharpQuote(0)
            CLikeSyntax.STRINGS_DART -> openScriptString(if (tripleAt(index, '"')) DART_TRIPLE_DOUBLE else DART_DOUBLE)
            CLikeSyntax.STRINGS_SCALA -> if (tripleAt(index, '"')) openScriptString(SCALA_RAW) else quotedString('"')
            CLikeSyntax.STRINGS_GROOVY -> openScriptString(if (tripleAt(index, '"')) GROOVY_TRIPLE_DOUBLE else GROOVY_DOUBLE)
            CLikeSyntax.STRINGS_ZIG -> quotedString('"')
            // Apex has no double-quoted strings.
            CLikeSyntax.STRINGS_APEX -> index += 1
            else -> cString()
        }
    }

    private fun singleQuote() {
        when (syntax.strings) {
            // Swift has no single quotes.
            CLikeSyntax.STRINGS_SWIFT -> index += 1
            CLikeSyntax.STRINGS_RUST -> characterOrName(RoleCode.TYPE)
            // A Scala character, or a symbol: `'name`.
            CLikeSyntax.STRINGS_SCALA -> characterOrName(RoleCode.CONSTANT)
            CLikeSyntax.STRINGS_DART -> openScriptString(if (tripleAt(index, '\'')) DART_TRIPLE_SINGLE else DART_SINGLE)
            CLikeSyntax.STRINGS_GROOVY -> if (tripleAt(index, '\'')) openScriptString(GROOVY_TRIPLE_SINGLE) else quotedString('\'')
            // A JavaScript string, or a character everywhere else.
            else -> quotedString('\'')
        }
    }

    private fun backtick() {
        when (syntax.strings) {
            CLikeSyntax.STRINGS_JS -> {
                openString(TEMPLATE)
                return
            }
            CLikeSyntax.STRINGS_GO -> {
                openString(GO_RAW)
                return
            }
            CLikeSyntax.STRINGS_KOTLIN, CLikeSyntax.STRINGS_SWIFT, CLikeSyntax.STRINGS_SCALA -> Unit
            else -> {
                index += 1
                return
            }
        }
        // A backtick-quoted name in Kotlin, Swift, or Scala; a lone backtick stays plain.
        var close = index + 1
        while (close < end && text[close] != '`') close += 1
        if (close >= end) {
            index += 1
        } else {
            identifier(index, close + 1)
        }
    }

    private fun isTripleQuote(at: Int): Boolean = at + 2 < end && text[at + 1] == '"' && text[at + 2] == '"'

    /** Three [quote]s from [at]. */
    private fun tripleAt(
        at: Int,
        quote: Char,
    ): Boolean = at + 2 < end && text[at] == quote && text[at + 1] == quote && text[at + 2] == quote

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
        stringWithEscapes(index, to, placeholders = quote == '"' && syntax.placeholders)
        index = to
        value()
    }

    /** A C string: one line, unless a trailing `\` continues it. */
    private fun cString() {
        var at = index + 1
        var closed = false
        while (at < end) {
            val char = text[at]
            at += 1
            if (char == '\\') {
                at += 1
            } else if (char == '"') {
                closed = true
                break
            }
        }
        val to = minOf(at, end)
        stringWithEscapes(index, to, syntax.placeholders)
        index = to
        if (!closed && canPush() && escapedLineEnd()) push(STRING, C_LINE) else value()
    }

    /**
     * Literals that start with a word: Rust `b"…"`, `b'x'`, `c"…"`, and raw `r#"…"#` strings (and
     * `r#name` raw identifiers); C and C++ `L`, `u`, `U`, `u8` prefixes and C++ raw `R"x(…)x"`
     * strings. False when the word at `[from, to)` is not one.
     */
    private fun prefixedLiteral(
        from: Int,
        to: Int,
    ): Boolean {
        if (to >= end) return false
        if (syntax.strings == CLikeSyntax.STRINGS_DART && to - from == 1 && text[from] == 'r' && (text[to] == '\'' || text[to] == '"')) {
            dartRawString(from, to)
            return true
        }
        if (syntax.strings == CLikeSyntax.STRINGS_SCALA && text[to] == '"' && syntax.words.find(text, from, to) == 0) {
            // An interpolator: `s"…"`, `f"…"`, `raw"…"`, `sql"""…"""`.
            out.span(from, to, RoleCode.KEYWORD)
            index = to
            openScriptString(if (tripleAt(to, '"')) SCALA_TRIPLE else SCALA_LINE)
            return true
        }
        if (to - from > 3) return false
        val next = text[to]
        if (next != '"' && next != '\'' && next != '#') return false
        val first = text[from]
        val length = to - from
        if (syntax.strings == CLikeSyntax.STRINGS_RUST) {
            val raw = text[to - 1] == 'r' && (length == 1 || (length == 2 && (first == 'b' || first == 'c')))
            if (raw && next != '\'') {
                var quote = to
                while (quote < end && text[quote] == '#') quote += 1
                if (quote < end && text[quote] == '"') {
                    openRustRawString(from, quote, quote - to)
                    return true
                }
                if (length == 1 && quote == to + 1 && quote < end && isIdentifierStart(text[quote])) {
                    identifier(from, identifierEnd(quote + 1))
                    return true
                }
                return false
            }
            if (length != 1 || (first != 'b' && first != 'c') || next == '#' || (first == 'c' && next == '\'')) return false
            out.span(from, to, RoleCode.STRING)
            index = to
            if (next == '"') openString(RUST) else characterOrName(RoleCode.TYPE)
            return true
        }
        if (!syntax.cFamily || next == '#') return false
        if (syntax.strings == CLikeSyntax.STRINGS_CPP && next == '"' && text[to - 1] == 'R' && isEncodingPrefix(from, to - 1)) {
            return cppRawStart(from, to)
        }
        if (!isEncodingPrefix(from, to) || length == 0) return false
        out.span(from, to, RoleCode.STRING)
        index = to
        if (next == '"') cString() else quotedString('\'')
        return true
    }

    /** `[from, to)` is empty or one of C's encoding prefixes: `L`, `u`, `U`, `u8`. */
    private fun isEncodingPrefix(
        from: Int,
        to: Int,
    ): Boolean =
        when (to - from) {
            0 -> true
            1 -> text[from] == 'L' || text[from] == 'u' || text[from] == 'U'
            2 -> text[from] == 'u' && text[from + 1] == '8'
            else -> false
        }

    /**
     * A Rust or Scala `'`: a character (`'x'`, `'\n'`, `'😀'`), else a name as [nameRole]: a Rust
     * lifetime or label (`'a`) or a Scala symbol (`'sym`).
     */
    private fun characterOrName(nameRole: Int) {
        val at = index + 1
        if (at >= end) {
            index += 1
            return
        }
        val char = text[at]
        if (char == '\\') {
            quotedString('\'')
            return
        }
        val width = if (char.isHighSurrogate() && at + 1 < end && text[at + 1].isLowSurrogate()) 2 else 1
        if (at + width < end && text[at + width] == '\'') {
            out.span(index, at + width + 1, RoleCode.STRING)
            index = at + width + 1
            value()
        } else if (isIdentifierStart(char)) {
            val to = identifierEnd(at + 1)
            out.span(index, to, nameRole)
            index = to
            value()
        } else {
            index += 1
        }
    }

    /** Rust `r#"`: [hashes] `#` before the quote at [quote]; the string runs to a quote and as many `#`, across lines. */
    private fun openRustRawString(
        from: Int,
        quote: Int,
        hashes: Int,
    ) {
        if (!canPush()) {
            index = from
            flatString(quote + 1, '"', 1, hashes, escapes = false)
            return
        }
        out.span(from, quote + 1, RoleCode.STRING)
        index = quote + 1
        push(RAW_MULTILINE, minOf(hashes, 31))
    }

    private fun rustRawString(hashes: Int) {
        var at = index
        while (at < end) {
            if (text[at] == '"' && hashesAt(at + 1, hashes)) {
                val close = at + 1 + hashes
                out.span(index, close, RoleCode.STRING)
                index = close
                pop()
                value()
                return
            }
            at += 1
        }
        out.span(index, end, RoleCode.STRING)
        index = end
    }

    /**
     * C++ `R"delimiter(`: the raw string runs to `)delimiter"`, across lines. It opens only with
     * nothing else open, since it keeps the delimiter's length and hash in the whole state; with
     * frames open, or when nothing past the delimiter is on this line, it ends with its line.
     * False when there is no valid delimiter and `(`, so the word and the quote lex on their own.
     */
    private fun cppRawStart(
        from: Int,
        quote: Int,
    ): Boolean {
        var paren = quote + 1
        while (paren < end && paren - quote <= MAX_DELIMITER + 1) {
            val char = text[paren]
            if (char == '(') break
            if (char == ')' || char == '\\' || char == '"' || char == ' ' || char == '\t' || char.code < 0x21 || char.code > 0x7E) return false
            paren += 1
        }
        if (paren >= end || text[paren] != '(' || paren - quote - 1 > MAX_DELIMITER) return false
        val length = paren - quote - 1
        val hash = delimiterHash(quote + 1, paren)
        out.span(from, paren + 1, RoleCode.STRING)
        index = paren + 1
        if (stack == 0) {
            stack = (length shl 26) or (hash shl 8) or (STRING shl 5) or CPP_RAW
            return true
        }
        val close = cppRawEnd(index, length, hash)
        val to = if (close < 0) end else close
        out.span(index, to, RoleCode.STRING)
        index = to
        value()
        return true
    }

    private fun cppRawString() {
        val close = cppRawEnd(index, stack ushr 26, (stack ushr 8) and DELIMITER_HASH_MASK)
        if (close < 0) {
            out.span(index, end, RoleCode.STRING)
            index = end
            return
        }
        out.span(index, close, RoleCode.STRING)
        index = close
        stack = 0
        value()
    }

    /** The offset past the first `)delimiter"` at or after [from] whose delimiter has [length] and [hash]; -1 if none. */
    private fun cppRawEnd(
        from: Int,
        length: Int,
        hash: Int,
    ): Int {
        var at = from
        while (at < end) {
            if (text[at] == ')') {
                val quote = at + 1 + length
                if (quote < end && text[quote] == '"' && delimiterHash(at + 1, quote) == hash) return quote + 1
            }
            at += 1
        }
        return -1
    }

    private fun delimiterHash(
        from: Int,
        to: Int,
    ): Int {
        var hash = 0
        for (at in from until to) hash = hash * 31 + text[at].code
        return hash and DELIMITER_HASH_MASK
    }

    /** C# `"`: a one-line string, or a raw one of three or more quotes. [dollars] `$` came before it. */
    private fun cSharpQuote(dollars: Int) {
        var quotes = index
        while (quotes < end && text[quotes] == '"') quotes += 1
        val count = quotes - index
        when {
            count >= 3 -> {
                if (!canPush()) {
                    flatString(quotes, '"', 3, 0, escapes = false)
                    return
                }
                out.span(index, quotes, RoleCode.STRING)
                index = quotes
                push(RAW_MULTILINE, minOf(count - 3, 7) or (minOf(dollars, 3) shl 3))
            }
            dollars > 0 -> openString(CS_INTERPOLATED)
            else -> quotedString('"')
        }
    }

    /** C# `$"`, `$@"`, `$$"""`: an interpolated string, its `$`s as String; a lone `$` stays plain. */
    private fun cSharpDollar() {
        var at = index
        while (at < end && text[at] == '$') at += 1
        val dollars = at - index
        val verbatim = dollars == 1 && at + 1 < end && text[at] == '@' && text[at + 1] == '"'
        if (!verbatim && (at >= end || text[at] != '"')) {
            index = at
            return
        }
        val quote = if (verbatim) at + 1 else at
        out.span(index, quote, RoleCode.STRING)
        index = quote
        if (verbatim) openString(CS_VERBATIM_INTERPOLATED) else cSharpQuote(dollars)
    }

    /** C# `@"…"` and `@$"…"` verbatim strings and `@name` identifiers. */
    private fun cSharpAt() {
        val next = if (index + 1 < end) text[index + 1] else ' '
        val interpolated = next == '$' && index + 2 < end && text[index + 2] == '"'
        when {
            next == '"' || interpolated -> {
                val quote = if (interpolated) index + 2 else index + 1
                out.span(index, quote, RoleCode.STRING)
                index = quote
                openString(if (interpolated) CS_VERBATIM_INTERPOLATED else CS_VERBATIM)
            }
            isIdentifierStart(next) -> identifier(index, identifierEnd(index + 2))
            else -> index += 1
        }
    }

    /** A C# raw string's body: [value] packs its quote count (minus three) and, above it, its `$` count. */
    private fun cSharpRawString(value: Int) {
        val quotes = (value and 7) + 3
        val dollars = value ushr 3
        var segment = index
        var at = index
        while (at < end) {
            val char = text[at]
            if (char == '"' || (char == '{' && dollars > 0)) {
                var run = at + 1
                while (run < end && text[run] == char) run += 1
                if (char == '"' && run - at >= quotes) {
                    out.span(segment, run, RoleCode.STRING)
                    index = run
                    pop()
                    value()
                    return
                }
                if (char == '{' && run - at >= dollars && canPush()) {
                    // The last `dollars` braces open the interpolation; any before them are text.
                    out.span(segment, run - dollars, RoleCode.STRING)
                    openInterpolation(run - dollars, run, BRACE)
                    return
                }
                at = run
                continue
            }
            at += 1
        }
        out.span(segment, end, RoleCode.STRING)
        index = end
    }

    private fun openString(kind: Int) {
        val delimiter = if (kind == KOTLIN_RAW || kind == SWIFT_MULTILINE || kind == JAVA_TEXT_BLOCK) 3 else 1
        if (!canPush()) {
            val escapes = kind != KOTLIN_RAW && kind != GO_RAW && kind != CS_VERBATIM && kind != CS_VERBATIM_INTERPOLATED
            flatString(index + delimiter, if (kind == TEMPLATE || kind == GO_RAW) '`' else '"', delimiter, 0, escapes)
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

    /**
     * The body of a Rust, Go raw, Java text block, continued C, or C# string frame: text, escapes
     * (`""` in a verbatim string), Rust format braces, C placeholders, and C# `{…}` interpolations
     * (`{{` and `}}` are escapes), up to the closing delimiter.
     */
    private fun familyString(kind: Int) {
        val escapes = kind == RUST || kind == JAVA_TEXT_BLOCK || kind == C_LINE || kind == CS_INTERPOLATED
        val verbatim = kind == CS_VERBATIM || kind == CS_VERBATIM_INTERPOLATED
        val interpolates = kind == CS_INTERPOLATED || kind == CS_VERBATIM_INTERPOLATED
        val quote = if (kind == GO_RAW) '`' else '"'
        var segment = index
        var at = index
        while (at < end) {
            val char = text[at]
            val next = if (at + 1 < end) text[at + 1] else ' '
            val escapeEnd =
                when {
                    char == '\\' && escapes -> escapeEnd(at)
                    verbatim && char == '"' && next == '"' -> at + 2
                    interpolates && (char == '{' || char == '}') && next == char -> at + 2
                    else -> -1
                }
            if (escapeEnd >= 0) {
                if (out.collecting) {
                    out.span(segment, at, RoleCode.STRING)
                    out.span(at, escapeEnd, RoleCode.CONSTANT)
                    segment = escapeEnd
                }
                at = escapeEnd
                continue
            }
            if (char == quote && (kind != JAVA_TEXT_BLOCK || isTripleQuote(at))) {
                val close = at + if (kind == JAVA_TEXT_BLOCK) 3 else 1
                out.span(segment, close, RoleCode.STRING)
                index = close
                pop()
                value()
                return
            }
            if (interpolates && char == '{' && canPush()) {
                out.span(segment, at, RoleCode.STRING)
                openInterpolation(at, at + 1, BRACE)
                return
            }
            if (out.collecting) {
                val special =
                    when {
                        kind == RUST && (char == '{' || char == '}') -> if (next == char) -1 else at + 1
                        kind == C_LINE && char == '%' -> placeholderEnd(at)
                        else -> -1
                    }
                if (special > at) {
                    out.span(segment, at, RoleCode.STRING)
                    out.span(at, special, if (kind == RUST) RoleCode.PUNCTUATION else RoleCode.CONSTANT)
                    segment = special
                    at = special
                    continue
                }
                // `{{` in a Rust format string is text: step over both.
                if (kind == RUST && (char == '{' || char == '}')) at += 1
            }
            at += 1
        }
        out.span(segment, end, RoleCode.STRING)
        index = end
    }

    /** The end of a printf-style placeholder at the `%` [at] (`%d`, `%-8.3f`, `%%`), or [at] when there is none. */
    private fun placeholderEnd(at: Int): Int {
        var to = at + 1
        if (to < end && text[to] == '%') return to + 1
        while (to < end && (text[to] == '-' || text[to] == '+' || text[to] == ' ' || text[to] == '#' || text[to] == '0')) to += 1
        if (to < end && text[to] == '[') {
            // Go's argument index: `%[1]d`.
            var close = to + 1
            while (close < end && isDigit(text[close])) close += 1
            if (close < end && text[close] == ']') to = close + 1
        }
        while (to < end && (isDigit(text[to]) || text[to] == '*')) to += 1
        if (to < end && text[to] == '.') {
            to += 1
            while (to < end && (isDigit(text[to]) || text[to] == '*')) to += 1
        }
        while (to < end && text[to] in PLACEHOLDER_LENGTHS) to += 1
        return if (to < end && (text[to] in PLACEHOLDER_VERBS || (syntax.objc && text[to] == '@'))) to + 1 else at
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
            if (char == quote && (quotes == 1 || tripleAt(at, quote)) && hashesAt(at + quotes, hashes)) {
                to = at + quotes + hashes
                break
            }
            at += 1
        }
        out.span(index, minOf(to, end), RoleCode.STRING)
        index = minOf(to, end)
        value()
    }

    /**
     * A string's text with escapes (and, with [placeholders], printf placeholders) as Constant;
     * they are only split out when spans are collected.
     */
    private fun stringWithEscapes(
        from: Int,
        to: Int,
        placeholders: Boolean = false,
    ) {
        if (!out.collecting) {
            out.span(from, to, RoleCode.STRING)
            return
        }
        var segment = from
        var at = from + 1
        while (at < to) {
            val special =
                when {
                    text[at] == '\\' -> minOf(to, escapeEnd(at))
                    placeholders && text[at] == '%' && at + 1 < to -> minOf(to, placeholderEnd(at))
                    else -> at
                }
            if (special > at) {
                out.span(segment, at, RoleCode.STRING)
                out.span(at, special, RoleCode.CONSTANT)
                segment = special
                at = special
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
            char == 'x' && syntax.strings != CLikeSyntax.STRINGS_KOTLIN && syntax.strings != CLikeSyntax.STRINGS_SWIFT ->
                if (hexDigits(at + 2, 2)) at + 4 else at + 2
            char == 'U' && (syntax.placeholders || syntax.strings == CLikeSyntax.STRINGS_CSHARP) -> if (hexDigits(at + 2, 8)) at + 10 else at + 2
            char in '0'..'7' && syntax.placeholders -> {
                // C and Go octal escapes: up to three digits.
                var close = at + 2
                while (close < end && close < at + 4 && text[close] in '0'..'7') close += 1
                close
            }
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

    // region Dart, Scala, and Groovy strings

    /** A Dart `r'…'` or `r"""…"""` raw string from the `r` at [from]; the quote is at [quote]. */
    private fun dartRawString(
        from: Int,
        quote: Int,
    ) {
        val char = text[quote]
        out.span(from, quote, RoleCode.STRING)
        index = quote
        if (tripleAt(quote, char)) {
            openScriptString(if (char == '\'') DART_RAW_TRIPLE_SINGLE else DART_RAW_TRIPLE_DOUBLE)
            return
        }
        var close = quote + 1
        while (close < end && text[close] != char) close += 1
        val to = minOf(close + 1, end)
        out.span(quote, to, RoleCode.STRING)
        index = to
        value()
    }

    /** Opens a Dart, Scala, or Groovy string frame at its opening delimiter; flat when no frame fits. */
    private fun openScriptString(kind: Int) {
        val delimiter = if (isTripleScriptString(kind)) 3 else if (kind == GROOVY_DOLLAR_SLASHY) 2 else 1
        if (!canPush()) {
            if (kind == GROOVY_DOLLAR_SLASHY) {
                var close = index + 2
                while (close + 1 < end && !(text[close] == '/' && text[close + 1] == '$')) close += 1
                val to = if (close + 1 < end) close + 2 else end
                out.span(index, to, RoleCode.STRING)
                index = to
                value()
            } else {
                flatString(index + delimiter, scriptQuote(kind), if (delimiter == 3) 3 else 1, 0, scriptEscapes(kind))
            }
            return
        }
        out.span(index, index + delimiter, RoleCode.STRING)
        index += delimiter
        push(STRING, kind)
    }

    private fun isTripleScriptString(kind: Int): Boolean =
        kind == DART_TRIPLE_SINGLE || kind == DART_TRIPLE_DOUBLE || kind == DART_RAW_TRIPLE_SINGLE || kind == DART_RAW_TRIPLE_DOUBLE ||
            kind == SCALA_RAW || kind == SCALA_TRIPLE || kind == GROOVY_TRIPLE_SINGLE || kind == GROOVY_TRIPLE_DOUBLE

    /** Script strings that end with their line. */
    private fun isLineScriptString(kind: Int): Boolean =
        kind == DART_SINGLE || kind == DART_DOUBLE || kind == SCALA_LINE || kind == GROOVY_DOUBLE || kind == GROOVY_SLASHY

    private fun scriptQuote(kind: Int): Char =
        when (kind) {
            DART_SINGLE, DART_TRIPLE_SINGLE, DART_RAW_TRIPLE_SINGLE, GROOVY_TRIPLE_SINGLE -> '\''
            GROOVY_SLASHY, GROOVY_DOLLAR_SLASHY -> '/'
            else -> '"'
        }

    /** Backslash escapes count (a slashy string only escapes `/`, handled on its own). */
    private fun scriptEscapes(kind: Int): Boolean =
        kind != DART_RAW_TRIPLE_SINGLE && kind != DART_RAW_TRIPLE_DOUBLE && kind != SCALA_RAW && kind != GROOVY_SLASHY &&
            kind != GROOVY_DOLLAR_SLASHY

    private fun scriptInterpolates(kind: Int): Boolean =
        kind == DART_SINGLE || kind == DART_DOUBLE || kind == DART_TRIPLE_SINGLE || kind == DART_TRIPLE_DOUBLE || kind == SCALA_LINE ||
            kind == SCALA_TRIPLE || kind == GROOVY_DOUBLE || kind == GROOVY_TRIPLE_DOUBLE || kind == GROOVY_SLASHY ||
            kind == GROOVY_DOLLAR_SLASHY

    /**
     * The body of a Dart, Scala, or Groovy string frame: text, escapes, `$name` (Groovy `$a.b`)
     * and `${…}` interpolations, up to the closing delimiter. `$name` is a Variable; Dart keeps
     * the `$` in the String and Scala makes it Punctuation, as Shiki does. Scala's `$$`, a
     * slashy string's `\/`, and a dollar-slashy string's `$$` and `$/` are escapes.
     */
    private fun scriptString(kind: Int) {
        val quote = scriptQuote(kind)
        val triple = isTripleScriptString(kind)
        val escapes = scriptEscapes(kind)
        val interpolates = scriptInterpolates(kind)
        val dollarSlashy = kind == GROOVY_DOLLAR_SLASHY
        var segment = index
        var at = index
        while (at < end) {
            val char = text[at]
            val next = if (at + 1 < end) text[at + 1] else ' '
            val escapeEnd =
                when {
                    char == '\\' && escapes -> escapeEnd(at)
                    char == '\\' && kind == GROOVY_SLASHY && next == '/' -> at + 2
                    char == '$' && dollarSlashy && (next == '$' || next == '/') -> at + 2
                    char == '$' && next == '$' && (kind == SCALA_LINE || kind == SCALA_TRIPLE) -> at + 2
                    else -> -1
                }
            if (escapeEnd >= 0) {
                if (out.collecting) {
                    out.span(segment, at, RoleCode.STRING)
                    out.span(at, escapeEnd, RoleCode.CONSTANT)
                    segment = escapeEnd
                }
                at = escapeEnd
                continue
            }
            if (char == '$' && interpolates) {
                if (next == '{' && canPush()) {
                    out.span(segment, at, RoleCode.STRING)
                    openInterpolation(at, at + 2, BRACE)
                    return
                }
                if (isAsciiLetter(next) || next == '_' || (next.code >= 0x80 && next.isLetter())) {
                    var nameEnd = scriptNameEnd(at + 2)
                    if (syntax.strings == CLikeSyntax.STRINGS_GROOVY) {
                        while (nameEnd + 1 < end && text[nameEnd] == '.' && (isAsciiLetter(text[nameEnd + 1]) || text[nameEnd + 1] == '_')) {
                            nameEnd = scriptNameEnd(nameEnd + 2)
                        }
                    }
                    if (out.collecting) {
                        when (syntax.strings) {
                            CLikeSyntax.STRINGS_DART -> out.span(segment, at + 1, RoleCode.STRING)
                            CLikeSyntax.STRINGS_SCALA -> {
                                out.span(segment, at, RoleCode.STRING)
                                out.span(at, at + 1, RoleCode.PUNCTUATION)
                            }
                            else -> out.span(segment, at, RoleCode.STRING)
                        }
                        out.span(if (syntax.strings == CLikeSyntax.STRINGS_GROOVY) at else at + 1, nameEnd, RoleCode.VARIABLE)
                        segment = nameEnd
                    }
                    at = nameEnd
                    continue
                }
            }
            val close =
                when {
                    dollarSlashy -> if (char == '/' && next == '$') at + 2 else -1
                    char != quote -> -1
                    !triple -> at + 1
                    tripleAt(at, quote) -> {
                        var to = at + 3
                        // Scala: in `""""`, the last three quotes close the string.
                        if (kind == SCALA_RAW || kind == SCALA_TRIPLE) while (to < end && text[to] == '"') to += 1
                        to
                    }
                    else -> -1
                }
            if (close >= 0) {
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

    /** The end of a `$name` in a string: ASCII letters, digits, `_`, and other letters; never `$`. */
    private fun scriptNameEnd(from: Int): Int {
        var at = from
        while (at < end) {
            val char = text[at]
            if (isAsciiLetter(char) || isDigit(char) || char == '_' || (char.code >= 0x80 && char.isLetterOrDigit())) at += 1 else break
        }
        return at
    }

    // endregion

    // region Objective-C and Zig

    /**
     * Objective-C `@`: `@"…"` strings, `@[`, `@{`, and `@(` literals (the `@` as Punctuation),
     * `@42` numbers, `@YES`, and directives (`@interface Name`, `@end`, `@property`) as Keywords.
     */
    private fun objcAt() {
        val next = if (index + 1 < end) text[index + 1] else ' '
        when {
            next == '"' -> {
                out.span(index, index + 1, RoleCode.STRING)
                index += 1
                cString()
            }
            next == '[' || next == '{' || next == '(' -> {
                out.span(index, index + 1, RoleCode.PUNCTUATION)
                index += 1
                expressionStart()
            }
            isDigit(next) -> {
                out.span(index, index + 1, RoleCode.NUMBER)
                index += 1
                number()
            }
            isIdentifierStart(next) -> {
                val to = identifierEnd(index + 2)
                val from = index + 1
                val constant = wordAt(from, to, "YES") || wordAt(from, to, "NO") || wordAt(from, to, "true") || wordAt(from, to, "false")
                out.span(index, to, if (constant) RoleCode.CONSTANT else RoleCode.KEYWORD)
                index = to
                if (constant) {
                    value()
                } else {
                    expressionStart()
                    if (wordAt(from, to, "interface") || wordAt(from, to, "implementation") || wordAt(from, to, "protocol") || wordAt(from, to, "class")) {
                        declaring = CLikeSyntax.DECLARES_TYPE
                    }
                }
            }
            else -> index += 1
        }
    }

    private fun wordAt(
        from: Int,
        to: Int,
        word: String,
    ): Boolean = LexerScan.equals(text, from, to, word)

    /** Zig `@`: a builtin call (`@import`) as a Function, or an `@"quoted"` identifier. */
    private fun zigAt() {
        val next = if (index + 1 < end) text[index + 1] else ' '
        when {
            next == '"' -> {
                var at = index + 2
                while (at < end && text[at] != '"') at += if (text[at] == '\\') 2 else 1
                val to = minOf(at + 1, end)
                emit(index, to, syntax.identifierRole)
                index = to
                value()
            }
            isIdentifierStart(next) -> {
                val to = identifierEnd(index + 2)
                out.span(index, to, RoleCode.FUNCTION)
                index = to
                value()
            }
            else -> index += 1
        }
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

        // A NONE frame with a value: a C or C++ directive continued from the line above.
        const val DIRECTIVE = 1

        // STRING frame values.
        const val TEMPLATE = 1
        const val KOTLIN_LINE = 2
        const val KOTLIN_RAW = 3
        const val SWIFT_LINE = 4
        const val SWIFT_MULTILINE = 5
        const val RUST = 6
        const val GO_RAW = 7
        const val JAVA_TEXT_BLOCK = 8
        const val C_LINE = 9
        const val CS_INTERPOLATED = 10
        const val CS_VERBATIM = 11
        const val CS_VERBATIM_INTERPOLATED = 12
        const val CPP_RAW = 13

        // Dart, Scala, and Groovy strings, read by [scriptString]; keep them one contiguous range.
        const val DART_SINGLE = 14
        const val DART_DOUBLE = 15
        const val DART_TRIPLE_SINGLE = 16
        const val DART_TRIPLE_DOUBLE = 17
        const val DART_RAW_TRIPLE_SINGLE = 18
        const val DART_RAW_TRIPLE_DOUBLE = 19
        const val SCALA_RAW = 20
        const val SCALA_LINE = 21
        const val SCALA_TRIPLE = 22
        const val GROOVY_TRIPLE_SINGLE = 23
        const val GROOVY_DOUBLE = 24
        const val GROOVY_TRIPLE_DOUBLE = 25
        const val GROOVY_SLASHY = 26
        const val GROOVY_DOLLAR_SLASHY = 27

        // A NONE frame value from SOQL up: an Apex query, plus how many `[` it has open inside.
        const val SOQL = 16
        const val SOQL_DEPTH = 15

        // A C++ raw string's delimiter: up to 16 characters, kept as its length and an 18-bit hash.
        const val MAX_DELIMITER = 16
        const val DELIMITER_HASH_MASK = (1 shl 18) - 1

        const val PLACEHOLDER_LENGTHS = "hlLqjzt"
        const val PLACEHOLDER_VERBS = "diouxXeEfFgGaAcspnvTtbqUwO"

        // JSX frame value: inside a tag, and the number of open elements.
        const val IN_TAG = 16
        const val DEPTH = 15

        // Previous token classes.
        const val START = 0
        const val VALUE = 1
        const val DOT = 2
    }
}
