package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit

/**
 * Clojure (and ClojureScript and EDN, which read the same).
 *
 * Roles: `;` comments are Comment, and so is a `#_` discard with the form after it when that
 * form ends on its line (`#_ #_ a b` discards two). Strings and `#"regex"` literals are String
 * with escapes as Constant, and run across lines; character literals (`\a`, `\newline`,
 * `\u00e9`) are String. Keywords
 * (`:kw`, `::kw`, `:ns/kw`), `true`, `false`, and `nil` are Constant. A symbol right after `(` is
 * Keyword when it is a special form or core macro (`defn`, `let`, `if`, `->`, ...) and Function
 * otherwise; the name a `defn`, `defmacro`, `defmulti`, or `defmethod` defines is Function, a
 * `def` or `defonce` name is Variable, and a `defrecord`, `deftype`, or `defprotocol` name is
 * Type, as are other capitalized symbols (`String`, `java.util.Date`). Metadata (`^:private`,
 * `^String`) and tagged literals (`#inst`) are Attribute; `%`, `%1`, and `%&` are Variable.
 * Numbers (integers, `0x1F`, `2r1010`, ratios `1/3`, decimals, `42N`, `1.5M`, `##Inf`) are
 * Number. Reader macros (`'`, `` ` ``, `~`, `~@`, `@`, `#'`, `#?`) are Operator; brackets and
 * the `#{`, `#(` openers Punctuation, as are commas.
 *
 * States: 0 in code, [STRING] inside a string or regex literal.
 *
 * Known gaps: a `#_` discard whose form spans lines only dims the `#_`; syntax-quoted forms
 * color like code.
 */
internal object ClojureLexer : HighlightLexer() {
    private const val STRING = 1
    private const val MAX_DISCARD = 1_000

    // Word kinds.
    private const val SPECIAL = 1
    private const val CONSTANT = 2
    private const val DEFINES_FUNCTION = 3
    private const val DEFINES_VARIABLE = 4
    private const val DEFINES_TYPE = 5

    private val words = LexerWords()

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        var index = start
        if (state == STRING) {
            val close = SymbolicScan.escapedEnd(text, start, end, '"', out)
            if (close < 0) return STRING
            index = close
        }
        var call = false
        var defines = 0
        while (index < end && !out.stopped) {
            val char = text[index]
            val next = if (index + 1 < end) text[index + 1] else ' '
            if (char == ' ' || char == '\t') {
                index += 1
                continue
            }
            val wasCall = call
            call = false
            val wasDefines = defines
            defines = 0
            when {
                char == ';' -> {
                    out.span(index, end, RoleCode.COMMENT)
                    return 0
                }
                char == '"' || (char == '#' && next == '"') -> {
                    val quote = if (char == '"') index else index + 1
                    out.span(index, quote + 1, RoleCode.STRING)
                    val close = SymbolicScan.escapedEnd(text, quote + 1, end, '"', out)
                    if (close < 0) return STRING
                    index = close
                }
                char == '#' && next == '_' -> {
                    // `#_ #_ a b` discards two forms: each `#_` waits for one more.
                    var after = index + 2
                    var pending = 1
                    while (pending > 0) {
                        val at = LexerScan.skipBlanks(text, after, end)
                        if (at + 1 < end && text[at] == '#' && text[at + 1] == '_') {
                            pending += 1
                            after = at + 2
                            continue
                        }
                        val formEnd = discardedEnd(text, at, end)
                        if (formEnd < 0) break
                        after = formEnd
                        pending -= 1
                    }
                    out.span(index, after, RoleCode.COMMENT)
                    index = after
                }
                char == '#' -> index = dispatch(text, index, end, out).also { call = next == '(' }
                char == '\\' -> {
                    val after = charLiteralEnd(text, index, end)
                    out.span(index, after, RoleCode.STRING)
                    index = after
                }
                char == ':' && index + 1 < end && isSymbolPart(next) -> {
                    var after = index + 1
                    while (after < end && isSymbolPart(text[after])) after += 1
                    out.span(index, after, RoleCode.CONSTANT)
                    index = after
                }
                char == '^' -> {
                    var after = index + 1
                    if (after < end && text[after] == ':') after += 1
                    while (after < end && isSymbolPart(text[after])) after += 1
                    out.span(index, after, RoleCode.ATTRIBUTE)
                    index = after
                }
                char == '(' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    call = true
                }
                char == ')' || char == '[' || char == ']' || char == '{' || char == '}' || char == ',' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                }
                char == '\'' || char == '`' || char == '@' -> {
                    out.span(index, index + 1, RoleCode.OPERATOR)
                    index += 1
                }
                char == '~' -> {
                    val after = if (next == '@') index + 2 else index + 1
                    out.span(index, after, RoleCode.OPERATOR)
                    index = after
                }
                isDigit(char) || ((char == '+' || char == '-') && isDigit(next)) -> {
                    var after = numberEnd(text, index, end)
                    // `1abc` is no number; it reads as one symbol-ish token.
                    while (after < end && isSymbolPart(text[after])) after += 1
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                }
                isSymbolStart(char) -> {
                    var after = index + 1
                    while (after < end && isSymbolPart(text[after])) after += 1
                    if (out.collecting) {
                        val kind = words.lookup(text, index, after)
                        val role =
                            when {
                                kind == CONSTANT -> RoleCode.CONSTANT
                                wasCall -> if (kind == 0) RoleCode.FUNCTION else RoleCode.KEYWORD
                                wasDefines == DEFINES_FUNCTION -> RoleCode.FUNCTION
                                wasDefines == DEFINES_VARIABLE -> RoleCode.VARIABLE
                                wasDefines == DEFINES_TYPE -> RoleCode.TYPE
                                char == '%' || char == '&' && after == index + 1 -> RoleCode.VARIABLE
                                char in 'A'..'Z' -> RoleCode.TYPE
                                else -> 0
                            }
                        if (role != 0) out.span(index, after, role)
                        if (wasCall && kind >= DEFINES_FUNCTION) defines = kind
                    }
                    index = after
                }
                else -> index += SymbolicScan.charLength(text, index, end)
            }
        }
        return 0
    }

    /** A `#` dispatch at [at] other than `#"` and `#_`; returns the offset after it. */
    private fun dispatch(
        text: CharArray,
        at: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        val next = if (at + 1 < end) text[at + 1] else ' '
        return when {
            next == '{' || next == '(' -> {
                out.span(at, at + 2, RoleCode.PUNCTUATION)
                at + 2
            }
            next == '\'' -> {
                out.span(at, at + 2, RoleCode.OPERATOR)
                at + 2
            }
            next == '?' -> {
                val after = if (at + 2 < end && text[at + 2] == '@') at + 3 else at + 2
                out.span(at, after, RoleCode.OPERATOR)
                after
            }
            next == '#' -> {
                var after = at + 2
                while (after < end && isSymbolPart(text[after])) after += 1
                out.span(at, after, RoleCode.NUMBER)
                after
            }
            next == ':' -> {
                var after = at + 2
                if (after < end && text[after] == ':') after += 1
                while (after < end && isSymbolPart(text[after])) after += 1
                out.span(at, after, RoleCode.CONSTANT)
                after
            }
            isAsciiLetter(next) -> {
                var after = at + 1
                while (after < end && isSymbolPart(text[after])) after += 1
                out.span(at, after, RoleCode.ATTRIBUTE)
                after
            }
            else -> {
                out.span(at, at + 1, RoleCode.PUNCTUATION)
                at + 1
            }
        }
    }

    /**
     * The end of the form a `#_` discards, starting at [at]: a bracketed form or string that
     * closes within [MAX_DISCARD] characters on this line, or one atom; -1 otherwise.
     */
    private fun discardedEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at
        while (index < end && (text[index] == '\'' || text[index] == '`' || text[index] == '~' || text[index] == '@')) index += 1
        if (index >= end) return -1
        val first = text[index]
        val limit = minOf(end, at + MAX_DISCARD)
        if (first == '"') return quotedEnd(text, index + 1, limit)
        if (first == '\\') return charLiteralEnd(text, index, end)
        if (first == '#' && index + 1 < end && (text[index + 1] == '(' || text[index + 1] == '{')) index += 1
        val open = text[index]
        if (open != '(' && open != '[' && open != '{') {
            val from = index
            while (index < end && isAtomPart(text[index])) index += 1
            return if (index > from) index else -1
        }
        var depth = 0
        while (index < limit) {
            when (text[index]) {
                '"' -> {
                    index = quotedEnd(text, index + 1, limit)
                    if (index < 0) return -1
                }
                '\\' -> index = charLiteralEnd(text, index, limit)
                ';' -> return -1
                '(', '[', '{' -> {
                    depth += 1
                    index += 1
                }
                ')', ']', '}' -> {
                    depth -= 1
                    index += 1
                    if (depth == 0) return index
                }
                else -> index += 1
            }
        }
        return -1
    }

    /** The offset after the `"` closing a string whose text starts at [from], or -1 before [limit]. */
    private fun quotedEnd(
        text: CharArray,
        from: Int,
        limit: Int,
    ): Int {
        var index = from
        while (index < limit) {
            when (text[index]) {
                '"' -> return index + 1
                '\\' -> index += 2
                else -> index += 1
            }
        }
        return -1
    }

    private fun isAtomPart(char: Char): Boolean =
        when (char) {
            ' ', '\t', ',', '(', ')', '[', ']', '{', '}', '"', ';' -> false
            else -> true
        }

    /** The end of a character literal at the backslash [at]: one character, or a name like `\newline`. */
    private fun charLiteralEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        if (at + 1 >= end) return end
        var after = at + 1 + SymbolicScan.charLength(text, at + 1, end)
        if (isAsciiLetter(text[at + 1])) {
            while (after < end && (isAsciiLetter(text[after]) || isDigit(text[after]))) after += 1
        }
        return after
    }

    /** Integers, `0x1F`, `2r1010`, ratios, decimals with exponents, and the `N` and `M` suffixes. */
    private fun numberEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at
        if (text[index] == '+' || text[index] == '-') index += 1
        if (index + 2 < end && text[index] == '0' && (text[index + 1] == 'x' || text[index + 1] == 'X') && LexerScan.isHexDigit(text[index + 2])) {
            index += 2
            while (index < end && LexerScan.isHexDigit(text[index])) index += 1
            return if (index < end && text[index] == 'N') index + 1 else index
        }
        while (index < end && isDigit(text[index])) index += 1
        if (index + 1 < end && (text[index] == 'r' || text[index] == 'R') && (isDigit(text[index + 1]) || isAsciiLetter(text[index + 1]))) {
            index += 1
            while (index < end && (isDigit(text[index]) || isAsciiLetter(text[index]))) index += 1
            return index
        }
        if (index + 1 < end && text[index] == '/' && isDigit(text[index + 1])) {
            index += 1
            while (index < end && isDigit(text[index])) index += 1
            return index
        }
        if (index < end && text[index] == '.') {
            index += 1
            while (index < end && isDigit(text[index])) index += 1
        }
        index = SymbolicScan.exponentEnd(text, index, end)
        if (index < end && (text[index] == 'N' || text[index] == 'M')) index += 1
        return index
    }

    private fun isSymbolStart(char: Char): Boolean =
        isAsciiLetter(char) ||
            (char.code >= 0x80 && !char.isWhitespace()) ||
            when (char) {
                '*', '+', '!', '-', '_', '?', '<', '>', '=', '/', '.', '&', '%', '$', '|' -> true
                else -> false
            }

    private fun isSymbolPart(char: Char): Boolean = isSymbolStart(char) || isDigit(char) || char == ':' || char == '#' || char == '\''

    init {
        words.register(
            SPECIAL,
            "fn", "fn*", "let", "let*", "letfn", "loop", "recur", "if", "if-not", "if-let", "if-some", "when", "when-not",
            "when-let", "when-some", "when-first", "cond", "condp", "case", "do", "doto", "doseq", "dotimes", "for", "while", "ns",
            "require", "import", "use", "quote", "var", "throw", "try", "catch", "finally", "new", "set!", "monitor-enter",
            "monitor-exit", "and", "or", "binding", "with-open", "with-redefs", "->", "->>", "as->", "some->", "some->>", "cond->",
            "cond->>", "comment", "declare", "delay", "future", "reify", "proxy", "extend", "extend-type", "extend-protocol",
            "gen-class", "in-ns", "lazy-seq", "assert", "go", "go-loop",
        )
        words.register(DEFINES_FUNCTION, "defn", "defn-", "defmacro", "defmulti", "defmethod", "definline")
        words.register(DEFINES_VARIABLE, "def", "defonce")
        words.register(DEFINES_TYPE, "defrecord", "deftype", "defprotocol", "definterface", "defstruct")
        words.register(CONSTANT, "true", "false", "nil")
    }
}
