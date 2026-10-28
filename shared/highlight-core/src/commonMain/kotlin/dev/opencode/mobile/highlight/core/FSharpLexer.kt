package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.SymbolicScan.isNameStart
import dev.opencode.mobile.highlight.core.SymbolicScan.isUpper

/**
 * F#.
 *
 * Roles: `//` line comments and nested `(* *)` comments are Comment (`(*)` is the multiplication
 * operator). Strings are String: `"..."` with escapes as Constant, verbatim `@"..."` with `""` as
 * Constant, triple-quoted `"""..."""`, byte strings (`"a"B`), and interpolated `$"..."`, `$@"..."`,
 * `$"""..."""`, and `$$"""..."""`, whose `{expr}` holes (`{{expr}}` with two dollars) are code
 * between Punctuation braces and whose `{{`/`}}` escapes are Constant. Every kind of string runs
 * across lines. Character literals (`'a'`, `'\n'`) are String; generic parameters (`'T`) and
 * capitalized names are Type, as are built-in type names (`int`, `string`, ...), and a name after
 * `type`. Keywords are Keyword, including the computation-expression forms (`let!`, `use!`,
 * `do!`, `yield!`, `return!`, `match!`, `and!`) and a builder (`async`, `task`, `seq`, `query`)
 * right before `{`. `true`, `false`, and `null` are Constant. The name a `let`, `use`, or `and`
 * binds is Function when parameters follow it and Variable when `=` or `:` does; the name a
 * `member`, `override`, `abstract`, `default`, or `val` declares (after `this.`) is Function, or
 * Property when `=` or `:` follows. After `.`, a name is Function when `(` follows or a module
 * precedes it, else Property. Attributes `[<Name(args)>]` are Attribute (brackets and names) and
 * line-start directives (`#if`, `#nowarn`, `#r`) Keyword. Numbers (with suffixes like `1uy`,
 * `1.5m`, `0x1F`) are Number; operators Operator; brackets, `,`, `;` Punctuation.
 *
 * States: the low 20 bits hold what is open at the line end: bits 0-2 the construct ([COMMENT]
 * with its depth in bits 6-19, or a string of a [NORMAL], [VERBATIM], or [TRIPLE] kind with
 * bit 3 set when interpolated and bits 4-5 holding its dollar count). Bits 20-28 hold an
 * interpolation hole the line ends inside: bit 20 set, the string's kind in bits 21-22, its
 * dollar count in bits 23-24, and the hole's open braces in bits 25-28. One hole level is
 * tracked; a string inside a hole does not interpolate.
 *
 * Known gaps: `%d`-style format specifiers are plain string text; `<@ @>` quotations, SRTP
 * `^T`, and active patterns `(|A|_|)` get no roles of their own; a lowercase function from a
 * module (`List.map`) and a value of one read the same.
 */
internal object FSharpLexer : HighlightLexer() {
    private const val MODE_MASK = 7
    private const val COMMENT = 1
    private const val NORMAL = 2
    private const val VERBATIM = 3
    private const val TRIPLE = 4
    private const val INTERP = 8
    private const val DOLLAR_SHIFT = 4
    private const val DEPTH_SHIFT = 6
    private const val HOLE_SHIFT = 20
    private const val INNER_MASK = (1 shl HOLE_SHIFT) - 1

    // A hole frame, shifted by HOLE_SHIFT in the state.
    private const val FRAME_KIND_SHIFT = 1
    private const val FRAME_DOLLAR_SHIFT = 3
    private const val FRAME_BRACE_SHIFT = 5
    private const val MAX_BRACES = 15

    // Word kinds and flags.
    private const val KEYWORD = 1
    private const val CONSTANT = 2
    private const val TYPE = 3
    private const val BUILDER = 4
    private const val BANG = 8
    private const val BINDS_LET = 16
    private const val BINDS_MEMBER = 32
    private const val KEEPS_BINDING = 64
    private const val TYPE_NEXT = 128

    // What the next name binds.
    private const val LET = 1
    private const val MEMBER = 2

    /** Returned by [stringBody] for a hole that opens at offset `at`: `HOLE_BASE - at`. */
    private const val HOLE_BASE = -3

    private val words = LexerWords()

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        var index = start
        var hole = state ushr HOLE_SHIFT
        var inner = state and INNER_MASK
        var first = true
        var binding = 0
        var typeNext = false
        // After a dot: 1 behind a capitalized name, 2 behind anything else.
        var afterDot = 0
        var attribute = false
        var attributeParens = 0
        while (!out.stopped) {
            // Resume a construct that is open at this point: carried from the line above or a hole's close.
            when (inner and MODE_MASK) {
                COMMENT -> {
                    val close = SymbolicScan.nestedCommentEnd(text, index, end, inner ushr DEPTH_SHIFT, '(', '*', '*', ')')
                    if (close < 0) {
                        out.span(index, end, RoleCode.COMMENT)
                        return (hole shl HOLE_SHIFT) or COMMENT or (-close shl DEPTH_SHIFT)
                    }
                    out.span(index, close, RoleCode.COMMENT)
                    index = close
                }
                NORMAL, VERBATIM, TRIPLE -> {
                    val dollars = (inner ushr DOLLAR_SHIFT) and 3
                    val result = stringBody(text, index, end, inner and MODE_MASK, inner and INTERP != 0, dollars, out)
                    if (result == SymbolicScan.OPEN) return (hole shl HOLE_SHIFT) or inner
                    if (result <= HOLE_BASE) {
                        hole = 1 or ((inner and MODE_MASK) - NORMAL shl FRAME_KIND_SHIFT) or (dollars shl FRAME_DOLLAR_SHIFT)
                        index = HOLE_BASE - result
                    } else {
                        index = result
                    }
                }
            }
            inner = 0
            // Code until the line ends, a construct stays open, or a hole closes back into its string.
            while (index < end && !out.stopped) {
                val char = text[index]
                val next = if (index + 1 < end) text[index + 1] else ' '
                if (char == ' ' || char == '\t') {
                    index += 1
                    continue
                }
                val wasFirst = first
                first = false
                val wasBinding = binding
                binding = 0
                val wasTypeNext = typeNext
                typeNext = false
                val wasAfterDot = afterDot
                afterDot = 0
                when {
                    char == '/' && next == '/' -> {
                        out.span(index, end, RoleCode.COMMENT)
                        return hole shl HOLE_SHIFT
                    }
                    char == '(' && next == '*' -> {
                        if (index + 2 < end && text[index + 2] == ')') {
                            out.span(index, index + 3, RoleCode.OPERATOR)
                            index += 3
                        } else {
                            val close = SymbolicScan.nestedCommentEnd(text, index + 2, end, 1, '(', '*', '*', ')')
                            out.span(index, if (close < 0) end else close, RoleCode.COMMENT)
                            if (close < 0) return (hole shl HOLE_SHIFT) or COMMENT or (-close shl DEPTH_SHIFT)
                            index = close
                            binding = wasBinding
                            typeNext = wasTypeNext
                        }
                    }
                    char == '"' || ((char == '$' || char == '@') && stringPrefixEnd(text, index, end) >= 0) -> {
                        val quote = if (char == '"') index else stringPrefixEnd(text, index, end)
                        var dollars = 0
                        var verbatim = false
                        for (at in index until quote) if (text[at] == '$') dollars += 1 else verbatim = true
                        if (hole != 0) dollars = 0
                        val triple = !verbatim && LexerScan.startsWith(text, quote, end, "\"\"\"")
                        val kind = if (triple) TRIPLE else if (verbatim) VERBATIM else NORMAL
                        val opener = if (triple) quote + 3 else quote + 1
                        out.span(index, opener, RoleCode.STRING)
                        index = opener
                        inner = kind or (if (dollars > 0) INTERP or (minOf(dollars, 3) shl DOLLAR_SHIFT) else 0)
                        break
                    }
                    char == '\'' -> {
                        val close = SymbolicScan.quotedCharEnd(text, index, end)
                        if (close >= 0) {
                            val after = if (close < end && text[close] == 'B') close + 1 else close
                            out.span(index, after, RoleCode.STRING)
                            index = after
                        } else if (isNameStart(next)) {
                            val after = SymbolicScan.primedNameEnd(text, index + 1, end)
                            out.span(index, after, RoleCode.TYPE)
                            index = after
                        } else {
                            out.span(index, index + 1, RoleCode.PUNCTUATION)
                            index += 1
                        }
                    }
                    char == '#' && wasFirst && isAsciiLetter(next) -> {
                        var after = index + 1
                        while (after < end && isAsciiLetter(text[after])) after += 1
                        out.span(index, after, RoleCode.KEYWORD)
                        index = after
                    }
                    isDigit(char) -> {
                        var after = SymbolicScan.numberEnd(text, index, end)
                        while (after < end && isAsciiLetter(text[after])) after += 1
                        out.span(index, after, RoleCode.NUMBER)
                        index = after
                    }
                    isNameStart(char) || (char == '`' && next == '`') -> {
                        val after = if (char == '`') quotedNameEnd(text, index, end) else SymbolicScan.primedNameEnd(text, index, end)
                        val dotted = after + 1 < end && text[after] == '.' && (isNameStart(text[after + 1]) || text[after + 1] == '`')
                        val upper = isUpper(char)
                        // `let!` and friends: the lookup decides where the token ends, so both passes make it.
                        val bang = char != '`' && wasAfterDot == 0 && after < end && text[after] == '!' && words.lookup(text, index, after) and BANG != 0
                        val tokenEnd = if (bang) after + 1 else after
                        if (out.collecting) {
                            val kind = if (char == '`' || wasAfterDot != 0) 0 else words.lookup(text, index, after)
                            val role =
                                when {
                                    attribute && attributeParens == 0 -> RoleCode.ATTRIBUTE
                                    wasAfterDot != 0 ->
                                        when {
                                            SymbolicScan.nextIs(text, after, end, '(') -> RoleCode.FUNCTION
                                            wasAfterDot == 1 -> if (upper) RoleCode.TYPE else RoleCode.FUNCTION
                                            wasBinding != 0 && !dotted -> bindingRole(wasBinding, text, after, end)
                                            else -> RoleCode.PROPERTY
                                        }
                                    kind and 7 == KEYWORD -> RoleCode.KEYWORD
                                    wasBinding != 0 -> if (dotted) 0 else bindingRole(wasBinding, text, after, end)
                                    upper -> RoleCode.TYPE
                                    kind and 7 == CONSTANT -> RoleCode.CONSTANT
                                    kind and 7 == BUILDER -> if (SymbolicScan.nextIs(text, after, end, '{')) RoleCode.KEYWORD else RoleCode.TYPE
                                    kind and 7 == TYPE || wasTypeNext -> RoleCode.TYPE
                                    else -> 0
                                }
                            if (role != 0) out.span(index, tokenEnd, role)
                            binding =
                                when {
                                    kind and KEEPS_BINDING != 0 -> wasBinding
                                    kind and BINDS_LET != 0 -> LET
                                    kind and BINDS_MEMBER != 0 -> MEMBER
                                    wasBinding != 0 && dotted -> wasBinding
                                    else -> 0
                                }
                            typeNext = kind and TYPE_NEXT != 0
                        }
                        index = tokenEnd
                        if (dotted && tokenEnd == after) {
                            out.span(index, index + 1, RoleCode.PUNCTUATION)
                            index += 1
                            afterDot = if (upper) 1 else 2
                        }
                    }
                    char == '[' && next == '<' -> {
                        out.span(index, index + 2, RoleCode.ATTRIBUTE)
                        index += 2
                        attribute = true
                        attributeParens = 0
                    }
                    char == '>' && next == ']' && attribute -> {
                        out.span(index, index + 2, RoleCode.ATTRIBUTE)
                        index += 2
                        attribute = false
                    }
                    char == '[' && next == '|' || char == '|' && next == ']' -> {
                        out.span(index, index + 2, RoleCode.PUNCTUATION)
                        index += 2
                    }
                    char == '{' -> {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                        if (hole != 0) {
                            val braces = hole ushr FRAME_BRACE_SHIFT
                            if (braces < MAX_BRACES) hole += 1 shl FRAME_BRACE_SHIFT
                        }
                    }
                    char == '}' -> {
                        if (hole == 0) {
                            out.span(index, index + 1, RoleCode.PUNCTUATION)
                            index += 1
                        } else if (hole ushr FRAME_BRACE_SHIFT > 0) {
                            out.span(index, index + 1, RoleCode.PUNCTUATION)
                            index += 1
                            hole -= 1 shl FRAME_BRACE_SHIFT
                        } else {
                            val dollars = (hole ushr FRAME_DOLLAR_SHIFT) and 3
                            val closing = if (dollars <= 1) 1 else dollars
                            if (LexerScan.startsWith(text, index, end, if (closing == 1) "}" else if (closing == 2) "}}" else "}}}")) {
                                out.span(index, index + closing, RoleCode.PUNCTUATION)
                                index += closing
                                val kind = ((hole ushr FRAME_KIND_SHIFT) and 3) + NORMAL
                                inner = kind or INTERP or (dollars shl DOLLAR_SHIFT)
                                hole = 0
                                break
                            }
                            out.span(index, index + 1, RoleCode.PUNCTUATION)
                            index += 1
                        }
                    }
                    char == '(' || char == ')' -> {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                        if (attribute) attributeParens = maxOf(0, attributeParens + if (char == '(') 1 else -1)
                    }
                    char == '[' || char == ']' || char == ',' || char == ';' -> {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                    }
                    isSymbol(char) -> {
                        var after = index + 1
                        while (after < end && isSymbol(text[after]) && !endsRun(text, after, end, attribute)) after += 1
                        out.span(index, after, RoleCode.OPERATOR)
                        index = after
                    }
                    else -> index += 1
                }
            }
            if (inner == 0) return hole shl HOLE_SHIFT
        }
        return 0
    }

    /** True when an operator run stops at [at]: before an attribute's `>]` or a string prefix (`$"`, `@"`). */
    private fun endsRun(
        text: CharArray,
        at: Int,
        end: Int,
        attribute: Boolean,
    ): Boolean {
        val char = text[at]
        return when (char) {
            '>' -> attribute && at + 1 < end && text[at + 1] == ']'
            '$', '@' -> stringPrefixEnd(text, at, end) >= 0
            else -> false
        }
    }

    /** Function or Property/Variable for the name a binding keyword declares, by what follows it. */
    private fun bindingRole(
        binding: Int,
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        val at = LexerScan.skipBlanks(text, from, end)
        val value =
            at < end &&
                when (text[at]) {
                    '=' -> !(at + 1 < end && isSymbol(text[at + 1]))
                    ':' -> !(at + 1 < end && (text[at + 1] == ':' || text[at + 1] == '?' || text[at + 1] == '>'))
                    ',' -> true
                    else -> false
                }
        return if (!value) RoleCode.FUNCTION else if (binding == LET) RoleCode.VARIABLE else RoleCode.PROPERTY
    }

    /** The quote after a `$`, `$$`, `@`, `$@`, or `@$` prefix at [at], or -1 when no string follows. */
    private fun stringPrefixEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at
        var verbatim = false
        while (index < end && index < at + 4) {
            val char = text[index]
            if (char == '$') {
                index += 1
            } else if (char == '@' && !verbatim) {
                verbatim = true
                index += 1
            } else {
                break
            }
        }
        return if (index < end && text[index] == '"') index else -1
    }

    /** The offset after a double-backtick name ``` ``a b`` ```, or past the opener when it does not close. */
    private fun quotedNameEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        val close = SymbolicScan.find(text, at + 2, end, "``")
        return if (close < 0) at + 2 else close
    }

    /**
     * String text of [kind] from [from]. Returns the offset after its close, [SymbolicScan.OPEN]
     * when it runs past the line, or `HOLE_BASE - at` when an interpolation hole opens and code
     * resumes at `at`.
     */
    private fun stringBody(
        text: CharArray,
        from: Int,
        end: Int,
        kind: Int,
        interpolated: Boolean,
        dollars: Int,
        out: SpanSink,
    ): Int {
        var index = from
        var segment = from
        while (index < end) {
            val char = text[index]
            val next = if (index + 1 < end) text[index + 1] else ' '
            if (kind == NORMAL && char == '\\') {
                val after = if (index + 1 < end) SymbolicScan.escapeEnd(text, index, end) else end
                out.span(segment, index, RoleCode.STRING)
                out.span(index, after, RoleCode.CONSTANT)
                index = after
                segment = after
                continue
            }
            if (char == '"') {
                if (kind == VERBATIM && next == '"') {
                    out.span(segment, index, RoleCode.STRING)
                    out.span(index, index + 2, RoleCode.CONSTANT)
                    index += 2
                    segment = index
                    continue
                }
                if (kind != TRIPLE || LexerScan.startsWith(text, index, end, "\"\"\"")) {
                    var after = index + if (kind == TRIPLE) 3 else 1
                    if (kind != TRIPLE && after < end && text[after] == 'B') after += 1
                    out.span(segment, after, RoleCode.STRING)
                    return after
                }
            }
            if (interpolated && (char == '{' || char == '}')) {
                var run = index + 1
                while (run < end && text[run] == char) run += 1
                val count = run - index
                if (dollars <= 1) {
                    if (count >= 2) {
                        out.span(segment, index, RoleCode.STRING)
                        out.span(index, index + 2, RoleCode.CONSTANT)
                        index += 2
                        segment = index
                        continue
                    }
                    if (char == '{') {
                        out.span(segment, index, RoleCode.STRING)
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        return HOLE_BASE - (index + 1)
                    }
                } else if (char == '{' && count >= dollars) {
                    val braces = run - dollars
                    out.span(segment, braces, RoleCode.STRING)
                    out.span(braces, run, RoleCode.PUNCTUATION)
                    return HOLE_BASE - run
                }
                index = run
                continue
            }
            index += 1
        }
        out.span(segment, end, RoleCode.STRING)
        return SymbolicScan.OPEN
    }

    private fun isSymbol(char: Char): Boolean =
        when (char) {
            '!', '$', '%', '&', '*', '+', '-', '.', '/', ':', '<', '=', '>', '?', '@', '^', '|', '~' -> true
            else -> false
        }

    init {
        words.register(
            KEYWORD,
            "as", "assert", "base", "begin", "class", "delegate", "done", "downcast", "downto", "elif", "else", "end",
            "exception", "extern", "finally", "fixed", "for", "fun", "function", "global", "if", "in", "inherit", "interface",
            "lazy", "module", "namespace", "new", "not", "of", "open", "or", "sig", "struct", "then", "to", "try", "upcast",
            "void", "when", "while", "with", "select", "const",
        )
        words.register(KEYWORD or BANG, "do", "yield", "return", "match")
        words.register(KEYWORD or BANG or BINDS_LET, "let", "use", "and")
        words.register(KEYWORD or BINDS_MEMBER, "member", "override", "abstract", "default", "val")
        words.register(KEYWORD or KEEPS_BINDING, "rec", "mutable", "inline", "private", "internal", "public", "static")
        words.register(KEYWORD or TYPE_NEXT, "type")
        words.register(CONSTANT, "true", "false", "null")
        words.register(BUILDER, "async", "task", "seq", "query")
        words.register(
            TYPE,
            "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32", "uint64", "byte", "sbyte", "float",
            "float32", "double", "single", "decimal", "bool", "char", "string", "unit", "obj", "list", "option", "voption",
            "array", "nativeint", "unativeint", "bigint", "exn",
        )
    }
}
