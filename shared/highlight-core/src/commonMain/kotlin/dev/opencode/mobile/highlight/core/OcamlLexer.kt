package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.SymbolicScan.isNameStart
import dev.opencode.mobile.highlight.core.SymbolicScan.isUpper

/**
 * OCaml.
 *
 * Roles: nested `(* *)` comments are Comment. Strings (`"..."`, across lines) are String with
 * escapes as Constant, and so are quoted strings `{|...|}` and `{id|...|id}`, which run across
 * lines until their own closing id. Character literals (`'a'`, `'\n'`) are String; type variables
 * (`'a`, `'key`) are Type, as are capitalized names (modules, constructors, each segment of
 * `List.Map`), the name after `type`, and the built-in types (`int`, `string`, `list`, ...).
 * Polymorphic variants (`` `Tag ``), `true`, and `false` are Constant. Keywords are Keyword, with
 * the word operators (`mod`, `land`, `lsl`, ...) as Operator. The name a `let`, `and`, `val`,
 * `external`, or `method` binds is Function when parameters follow it and Variable when `=` or
 * `:` does; after `.` a name is Function behind a module and Property behind a value. Labels
 * (`~x`, `?x`, `~f:`) are Property; `[@attr]`, `[@@deriving ...]`, and `[%ext]` openers are
 * Attribute. Numbers are Number; operators Operator; brackets, `,`, `;`, `;;` Punctuation.
 *
 * States: 0 in code; otherwise the low 2 bits hold the open construct: [COMMENT] (with its
 * nesting depth from bit 2), [STRING], or [QUOTED] (with a 24-bit [LexerDelimiter] hash of its
 * id from bit 2).
 *
 * Known gaps: a string inside a comment is not followed, so `(* "*)" *)` ends early; the
 * `{%ext|...|}` quoted extension form is not recognized; `and` after `type` binds like `let`.
 */
internal object OcamlLexer : HighlightLexer() {
    private const val MODE_MASK = 3
    private const val COMMENT = 1
    private const val STRING = 2
    private const val QUOTED = 3
    private const val SHIFT = 2
    private const val TAG_BITS = 24

    private const val KEYWORD = 1
    private const val CONSTANT = 2
    private const val OPERATOR = 3
    private const val TYPE = 4

    // A keyword kind's flags.
    private const val BINDS = 8
    private const val BINDS_LET = 16
    private const val TYPE_NEXT = 32
    private const val KEEPS_BINDING = 64

    // What the next name binds.
    private const val LET = 1
    private const val DECLARE = 2

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
                val close = SymbolicScan.nestedCommentEnd(text, start, end, state ushr SHIFT, '(', '*', '*', ')')
                if (close < 0) {
                    out.span(start, end, RoleCode.COMMENT)
                    return COMMENT or (-close shl SHIFT)
                }
                out.span(start, close, RoleCode.COMMENT)
                index = close
            }
            STRING -> {
                val close = SymbolicScan.escapedEnd(text, start, end, '"', out)
                if (close < 0) return STRING
                index = close
            }
            QUOTED -> {
                val close = quotedEnd(text, start, end, state ushr SHIFT)
                out.span(start, if (close < 0) end else close, RoleCode.STRING)
                if (close < 0) return state
                index = close
            }
        }
        var binding = 0
        var typeNext = false
        // After a dot: 1 behind a module name, 2 behind a value.
        var afterDot = 0
        var previous = 0
        while (index < end && !out.stopped) {
            val char = text[index]
            val next = if (index + 1 < end) text[index + 1] else ' '
            if (char == ' ' || char == '\t') {
                index += 1
                continue
            }
            val wasBinding = binding
            binding = 0
            val wasTypeNext = typeNext
            typeNext = false
            val wasAfterDot = afterDot
            afterDot = 0
            val wasPrevious = previous
            previous = 0
            when {
                char == '(' && next == '*' -> {
                    val close = SymbolicScan.nestedCommentEnd(text, index + 2, end, 1, '(', '*', '*', ')')
                    out.span(index, if (close < 0) end else close, RoleCode.COMMENT)
                    if (close < 0) return COMMENT or (-close shl SHIFT)
                    index = close
                    binding = wasBinding
                    typeNext = wasTypeNext
                }
                char == '"' -> {
                    out.span(index, index + 1, RoleCode.STRING)
                    val close = SymbolicScan.escapedEnd(text, index + 1, end, '"', out)
                    if (close < 0) return STRING
                    index = close
                }
                char == '{' && (next == '|' || next in 'a'..'z' || next == '_') -> {
                    var bar = index + 1
                    while (bar < end && (text[bar] in 'a'..'z' || text[bar] == '_')) bar += 1
                    if (bar < end && text[bar] == '|') {
                        val tag = LexerDelimiter.of(text, index + 1, bar, TAG_BITS)
                        val close = quotedEnd(text, bar + 1, end, tag)
                        out.span(index, if (close < 0) end else close, RoleCode.STRING)
                        if (close < 0) return QUOTED or (tag shl SHIFT)
                        index = close
                    } else {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                    }
                }
                char == '\'' -> {
                    val close = SymbolicScan.quotedCharEnd(text, index, end)
                    if (close >= 0) {
                        out.span(index, close, RoleCode.STRING)
                        index = close
                    } else if (isNameStart(next)) {
                        val after = SymbolicScan.primedNameEnd(text, index + 1, end)
                        out.span(index, after, RoleCode.TYPE)
                        index = after
                        typeNext = wasTypeNext
                    } else {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                    }
                }
                char == '`' && isNameStart(next) -> {
                    val after = SymbolicScan.primedNameEnd(text, index + 1, end)
                    out.span(index, after, RoleCode.CONSTANT)
                    index = after
                }
                (char == '~' || char == '?') && (next in 'a'..'z' || next == '_') -> {
                    var after = SymbolicScan.primedNameEnd(text, index + 1, end)
                    if (after < end && text[after] == ':' && !(after + 1 < end && text[after + 1] == '=')) after += 1
                    out.span(index, after, RoleCode.PROPERTY)
                    index = after
                }
                isDigit(char) -> {
                    var after = SymbolicScan.numberEnd(text, index, end)
                    if (after < end && text[after] == '.' && !(after + 1 < end && text[after + 1] == '.')) after = SymbolicScan.exponentEnd(text, after + 1, end)
                    if (after < end && (text[after] == 'l' || text[after] == 'L' || text[after] == 'n')) after += 1
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                }
                isNameStart(char) -> {
                    val after = SymbolicScan.primedNameEnd(text, index, end)
                    val upper = isUpper(char)
                    val dotted = after + 1 < end && text[after] == '.' && isNameStart(text[after + 1])
                    if (out.collecting) {
                        val kind = if (wasAfterDot != 0 || upper) 0 else words.lookup(text, index, after)
                        val role =
                            when {
                                upper -> RoleCode.TYPE
                                wasAfterDot == 1 -> RoleCode.FUNCTION
                                wasAfterDot == 2 -> RoleCode.PROPERTY
                                kind != 0 -> kindRole(kind)
                                wasTypeNext -> RoleCode.TYPE
                                wasBinding != 0 && dotted -> 0
                                wasBinding == LET -> if (bindsValue(text, after, end)) RoleCode.VARIABLE else RoleCode.FUNCTION
                                wasBinding == DECLARE -> RoleCode.FUNCTION
                                else -> 0
                            }
                        if (role != 0) out.span(index, after, role)
                        binding =
                            when {
                                kind and KEEPS_BINDING != 0 -> wasBinding
                                kind and BINDS_LET != 0 -> LET
                                kind and BINDS != 0 -> DECLARE
                                else -> 0
                            }
                        typeNext = kind and TYPE_NEXT != 0 || (wasTypeNext && kind and KEEPS_BINDING != 0)
                    }
                    index = after
                    if (dotted) {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                        afterDot = if (upper) 1 else 2
                    }
                    previous = 1
                }
                char == '[' && (next == '@' || next == '%') -> {
                    var after = index + 2
                    while (after < end && text[after] == '@') after += 1
                    while (after < end && (SymbolicScan.isPrimedNamePart(text[after]) || text[after] == '.')) after += 1
                    out.span(index, after, RoleCode.ATTRIBUTE)
                    index = after
                }
                char == '.' && wasPrevious == 2 && isNameStart(next) && !isUpper(next) -> {
                    // `r.(i).field`, `(f x).y`: a field after a closing bracket.
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    afterDot = 2
                }
                char == ';' -> {
                    val after = if (next == ';') index + 2 else index + 1
                    out.span(index, after, RoleCode.PUNCTUATION)
                    index = after
                }
                char == '(' || char == ')' || char == '[' || char == ']' || char == '{' || char == '}' || char == ',' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    if (char == ')' || char == ']') previous = 2
                }
                isSymbol(char) -> {
                    var after = index + 1
                    while (after < end && isSymbol(text[after])) after += 1
                    out.span(index, after, RoleCode.OPERATOR)
                    index = after
                }
                else -> index += 1
            }
        }
        return 0
    }

    private fun kindRole(kind: Int): Int =
        when (kind and 7) {
            KEYWORD -> RoleCode.KEYWORD
            CONSTANT -> RoleCode.CONSTANT
            OPERATOR -> RoleCode.OPERATOR
            else -> RoleCode.TYPE
        }

    /** True when a bound name is followed by `=`, `:`, or `,`: a value, not a function. */
    private fun bindsValue(
        text: CharArray,
        from: Int,
        end: Int,
    ): Boolean {
        val at = LexerScan.skipBlanks(text, from, end)
        if (at >= end) return false
        val char = text[at]
        return (char == '=' && !(at + 1 < end && isSymbol(text[at + 1]))) || (char == ':' && !(at + 1 < end && text[at + 1] == ':')) || char == ','
    }

    /** The offset after the `|id}` whose id hashes to [tag], or -1. */
    private fun quotedEnd(
        text: CharArray,
        from: Int,
        end: Int,
        tag: Int,
    ): Int {
        var index = from
        while (index < end) {
            if (text[index] != '|') {
                index += 1
                continue
            }
            var after = index + 1
            while (after < end && (text[after] in 'a'..'z' || text[after] == '_')) after += 1
            if (after < end && text[after] == '}' && LexerDelimiter.of(text, index + 1, after, TAG_BITS) == tag) return after + 1
            index = after
        }
        return -1
    }

    private fun isSymbol(char: Char): Boolean =
        when (char) {
            '!', '$', '%', '&', '*', '+', '-', '.', '/', ':', '<', '=', '>', '?', '@', '^', '|', '~', '#' -> true
            else -> false
        }

    init {
        words.register(
            KEYWORD,
            "as", "assert", "begin", "class", "constraint", "do", "done", "downto", "else", "end", "exception", "for", "fun",
            "function", "functor", "if", "in", "include", "inherit", "initializer", "lazy", "match", "module", "new",
            "object", "of", "open", "private", "sig", "struct", "then", "to", "try", "virtual", "when", "while", "with", "not",
        )
        words.register(KEYWORD or BINDS_LET, "let", "and")
        words.register(KEYWORD or BINDS, "val", "external", "method")
        words.register(KEYWORD or KEEPS_BINDING, "rec", "mutable", "nonrec")
        words.register(KEYWORD or TYPE_NEXT, "type")
        words.register(CONSTANT, "true", "false")
        words.register(OPERATOR, "mod", "land", "lor", "lxor", "lsl", "lsr", "asr", "or")
        words.register(
            TYPE,
            "int", "float", "bool", "char", "string", "unit", "list", "option", "array", "bytes", "exn", "int32", "int64",
            "nativeint", "format", "result", "seq",
        )
    }
}
