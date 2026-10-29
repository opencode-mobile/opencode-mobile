package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.SymbolicScan.isNameStart
import dev.opencode.mobile.highlight.core.SymbolicScan.isPrimedNamePart
import dev.opencode.mobile.highlight.core.SymbolicScan.isUpper

/**
 * Haskell.
 *
 * Roles: `--` line comments (two or more dashes not followed by an operator character, so `-->`
 * is an operator) and nested `{- -}` comments are Comment; `{-# ... #-}` pragmas are Attribute.
 * Strings are String with escapes (and the backslashes of a gap that continues one on the next
 * line) as Constant; character literals (`'a'`, `'\n'`) are String,
 * while a prime inside a name (`x'`, `foldl'`) stays part of the name and a Template Haskell or
 * promotion quote (`'name`, `''Type`) is Operator. Capitalized names (types, constructors,
 * modules, and each segment of `Data.Map`) are Type, with `True` and `False` Constant. Reserved
 * words are Keyword (`qualified`, `as`, and `hiding` only on `import` lines). A lowercase name at
 * the start of a line, or before `::`, is Function. Operators and backtick infix names
 * (`` `div` ``) are Operator; brackets, `,`, `;`, and backticks alone are Punctuation. Numbers
 * (decimal, `0x`, `0o`, `0b`, fractions, exponents, `_` separators) are Number. A quasi-quote
 * `[name| ... |]` is String between Punctuation brackets with its quoter as Function, and runs
 * across lines; Template Haskell brackets (`[|`, `[e|`, `[d|`, `[t|`, `[p|`) stay code.
 *
 * States: 0 in code; otherwise the low 3 bits hold the open construct: [COMMENT] (with its
 * nesting depth from bit 3, saturating at [SymbolicScan.MAX_DEPTH]), [PRAGMA], [STRING] (a string
 * whose line ended in a gap backslash), or [QUASI].
 *
 * Known gaps: layout is not tracked, so only line-start and `::` names are Function; `[x|x<-xs]`
 * without spaces reads as a quasi-quote, as in GHC with QuasiQuotes on; Unicode operator
 * characters are plain; a string gap resumes on the next line even when its closing backslash is
 * missing.
 */
internal object HaskellLexer : HighlightLexer() {
    private const val MODE_MASK = 7
    private const val COMMENT = 1
    private const val PRAGMA = 2
    private const val STRING = 3
    private const val QUASI = 4
    private const val DEPTH_SHIFT = 3

    private const val KEYWORD = 1
    private const val CONSTANT = 2
    private const val IMPORT_ONLY = 3

    private val words = LexerWords()

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        var index = start
        when (state and MODE_MASK) {
            COMMENT -> {
                val close = SymbolicScan.nestedCommentEnd(text, start, end, state ushr DEPTH_SHIFT, '{', '-', '-', '}')
                if (close < 0) {
                    out.span(start, end, RoleCode.COMMENT)
                    return COMMENT or (-close shl DEPTH_SHIFT)
                }
                out.span(start, close, RoleCode.COMMENT)
                index = close
            }
            PRAGMA -> {
                val close = SymbolicScan.find(text, start, end, "#-}")
                out.span(start, if (close < 0) end else close, RoleCode.ATTRIBUTE)
                if (close < 0) return PRAGMA
                index = close
            }
            STRING -> {
                // A string gap resumes after the blanks and backslash that close it.
                val gap = LexerScan.skipBlanks(text, start, end)
                if (gap < end && text[gap] == '\\') {
                    out.span(start, gap + 1, RoleCode.CONSTANT)
                    index = gap + 1
                }
                val close = SymbolicScan.escapedEnd(text, index, end, '"', out)
                if (close < 0) return if (close == SymbolicScan.CONTINUED) STRING else 0
                index = close
            }
            QUASI -> {
                val close = quasiBody(text, start, end, out)
                if (close < 0) return QUASI
                index = close
            }
        }
        var importLine = false
        var qualified = false
        var first = true
        while (index < end && !out.stopped) {
            val char = text[index]
            val next = if (index + 1 < end) text[index + 1] else ' '
            if (char == ' ' || char == '\t') {
                index += 1
                continue
            }
            val wasQualified = qualified
            qualified = false
            val wasFirst = first
            first = false
            when {
                char == '{' && next == '-' -> {
                    if (index + 2 < end && text[index + 2] == '#') {
                        val close = SymbolicScan.find(text, index + 3, end, "#-}")
                        out.span(index, if (close < 0) end else close, RoleCode.ATTRIBUTE)
                        if (close < 0) return PRAGMA
                        index = close
                    } else {
                        val close = SymbolicScan.nestedCommentEnd(text, index + 2, end, 1, '{', '-', '-', '}')
                        out.span(index, if (close < 0) end else close, RoleCode.COMMENT)
                        if (close < 0) return COMMENT or (-close shl DEPTH_SHIFT)
                        index = close
                    }
                }
                char == '"' -> {
                    out.span(index, index + 1, RoleCode.STRING)
                    val close = SymbolicScan.escapedEnd(text, index + 1, end, '"', out)
                    if (close < 0) return if (close == SymbolicScan.CONTINUED) STRING else 0
                    index = close
                }
                char == '\'' -> {
                    val close = SymbolicScan.quotedCharEnd(text, index, end)
                    if (close >= 0) {
                        out.span(index, close, RoleCode.STRING)
                        index = close
                    } else {
                        val after = if (next == '\'') index + 2 else index + 1
                        out.span(index, after, RoleCode.OPERATOR)
                        index = after
                    }
                }
                isDigit(char) -> {
                    val after = SymbolicScan.numberEnd(text, index, end)
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                }
                isNameStart(char) -> {
                    val after = SymbolicScan.primedNameEnd(text, index, end)
                    if (isUpper(char)) {
                        if (out.collecting) {
                            out.span(index, after, if (words.lookup(text, index, after) == CONSTANT) RoleCode.CONSTANT else RoleCode.TYPE)
                        }
                        index = after
                        // `Data.Map.lookup`: the dot belongs to the qualified name, not composition.
                        if (index + 1 < end && text[index] == '.' && isNameStart(text[index + 1])) {
                            out.span(index, index + 1, RoleCode.PUNCTUATION)
                            index += 1
                            qualified = true
                        }
                    } else {
                        if (out.collecting) {
                            val kind = if (wasQualified) 0 else words.lookup(text, index, after)
                            if (wasFirst && kind == KEYWORD && LexerScan.equals(text, index, after, "import")) importLine = true
                            val role =
                                when {
                                    kind == KEYWORD || (kind == IMPORT_ONLY && importLine) -> RoleCode.KEYWORD
                                    wasQualified -> 0
                                    index == start || isSignature(text, after, end) -> RoleCode.FUNCTION
                                    else -> 0
                                }
                            if (role != 0) out.span(index, after, role)
                        } else if (wasFirst && LexerScan.equals(text, index, after, "import")) {
                            importLine = true
                        }
                        index = after
                    }
                }
                char == '`' -> {
                    var after = index + 1
                    while (after < end && (isPrimedNamePart(text[after]) || text[after] == '.')) after += 1
                    if (after > index + 1 && after < end && text[after] == '`') {
                        out.span(index, after + 1, RoleCode.OPERATOR)
                        index = after + 1
                    } else {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                    }
                }
                char == '[' -> {
                    val close = bracket(text, index, end, out)
                    if (close < 0) return QUASI
                    index = close
                }
                isSymbol(char) -> {
                    var after = index + 1
                    while (after < end && isSymbol(text[after])) after += 1
                    var dashes = 0
                    while (index + dashes < after && text[index + dashes] == '-') dashes += 1
                    if (dashes >= 2 && dashes == after - index) {
                        out.span(index, end, RoleCode.COMMENT)
                        return 0
                    }
                    out.span(index, after, RoleCode.OPERATOR)
                    index = after
                }
                char == '(' || char == ')' || char == ']' || char == ',' || char == ';' || char == '{' || char == '}' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                }
                else -> index += 1
            }
        }
        return 0
    }

    /** True when `::` (and not a longer operator) follows [from] after blanks: a type signature. */
    private fun isSignature(
        text: CharArray,
        from: Int,
        end: Int,
    ): Boolean {
        val at = LexerScan.skipBlanks(text, from, end)
        return at + 1 < end && text[at] == ':' && text[at + 1] == ':' && (at + 2 >= end || !isSymbol(text[at + 2]))
    }

    /**
     * A `[` at [at]: a Template Haskell bracket, a quasi-quote, or plain punctuation. Returns the
     * offset after what it lexed, or -1 when a quasi-quote runs past the line.
     */
    private fun bracket(
        text: CharArray,
        at: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        var after = at + 1
        if (after < end && isNameStart(text[after])) {
            while (after < end && (isPrimedNamePart(text[after]) || text[after] == '.')) after += 1
        }
        if (after >= end || text[after] != '|') {
            out.span(at, at + 1, RoleCode.PUNCTUATION)
            return at + 1
        }
        val name = after - at - 1
        val quoteKind = name == 0 || (name == 1 && (text[at + 1] == 'e' || text[at + 1] == 'd' || text[at + 1] == 't' || text[at + 1] == 'p'))
        out.span(at, at + 1, RoleCode.PUNCTUATION)
        if (quoteKind) {
            if (name == 1) out.span(at + 1, after, RoleCode.KEYWORD)
            val bars = if (after + 1 < end && text[after + 1] == '|') 2 else 1
            out.span(after, after + bars, RoleCode.PUNCTUATION)
            return after + bars
        }
        out.span(at + 1, after, RoleCode.FUNCTION)
        out.span(after, after + 1, RoleCode.PUNCTUATION)
        return quasiBody(text, after + 1, end, out)
    }

    /** A quasi-quote's body from [from] through `|]`; the offset after it, or -1 when it continues. */
    private fun quasiBody(
        text: CharArray,
        from: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        val close = SymbolicScan.find(text, from, end, "|]")
        if (close < 0) {
            out.span(from, end, RoleCode.STRING)
            return -1
        }
        out.span(from, close - 2, RoleCode.STRING)
        out.span(close - 2, close, RoleCode.PUNCTUATION)
        return close
    }

    private fun isSymbol(char: Char): Boolean =
        when (char) {
            '!', '#', '$', '%', '&', '*', '+', '.', '/', '<', '=', '>', '?', '@', '\\', '^', '|', '-', '~', ':' -> true
            else -> false
        }

    init {
        words.register(
            KEYWORD,
            "case", "class", "data", "default", "deriving", "do", "else", "foreign", "if", "import", "in", "infix", "infixl",
            "infixr", "instance", "let", "module", "newtype", "of", "then", "type", "where", "forall", "mdo", "family",
        )
        words.register(IMPORT_ONLY, "qualified", "as", "hiding")
        words.register(CONSTANT, "True", "False")
    }
}
