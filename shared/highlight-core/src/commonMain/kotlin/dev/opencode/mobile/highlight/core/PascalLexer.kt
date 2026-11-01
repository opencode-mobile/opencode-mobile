package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.ClassicScan.isLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * Pascal as Object Pascal: Delphi and Free Pascal.
 *
 * Roles: reserved words are Keyword in any case, and so are `read`, `write`, `default`,
 * `stored`, `index`, and `implements` after `property` on the same line; predefined types
 * (`Integer`, `string`, `TObject`, ...) and Delphi-style class names (`TForm`, `EConvertError`:
 * `T` or `E`, a capital, and a lowercase letter somewhere) are Type. `True`, `False`, and `nil`
 * are Constant. The name after
 * `procedure`, `function`, `constructor`, `destructor`, or `operator` is Function (its class
 * part, `TForm1.` in `TForm1.Click`, Type), as is any other name before `(`. Strings `'...'` are
 * String with a doubled `''` as Constant; `#13` and `#$0A` character codes are Constant; Delphi 12
 * `'''` multi-line strings are String up to the closing `'''` line. Numbers (`1.5e3`, `$FF`, `&17`,
 * `%1010`) are Number. `{ }`, `(* *)`, and `//` comments are Comment; `{$...}` and `(*$...*)`
 * compiler directives are Keyword. Operators (`:=`, `+`, `<>`, `..`, `@`, `^`, ...) are Operator;
 * `( ) [ ] , ; : .` are Punctuation. `&begin` is a plain name.
 *
 * States: 0 in code; [BRACE_COMMENT], [PAREN_COMMENT], [BRACE_DIRECTIVE], [PAREN_DIRECTIVE], or
 * [MULTILINE_STRING] while that construct continues across lines. Comments do not nest.
 */
internal object PascalLexer : HighlightLexer() {
    private const val BRACE_COMMENT = 1
    private const val PAREN_COMMENT = 2
    private const val BRACE_DIRECTIVE = 3
    private const val PAREN_DIRECTIVE = 4
    private const val MULTILINE_STRING = 5

    // Word kinds.
    private const val KEYWORD = 1
    private const val TYPE = 2
    private const val CONSTANT = 3
    private const val PROPERTY_KEYWORD = 4
    private const val KIND_MASK = 7
    private const val NAMES_FUNCTION = 8
    private const val STARTS_PROPERTY = 16

    private val words = LexerWords(caseInsensitive = true)

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        var index = start
        if (state != 0) {
            val close = continueConstruct(text, start, end, state, out)
            if (close < 0) return state
            index = close
        }
        var functionNext = false
        var inProperty = false
        while (index < end && !out.stopped) {
            val char = text[index]
            val next = if (index + 1 < end) text[index + 1] else ' '
            when {
                char == ' ' || char == '\t' -> index += 1
                char == '/' && next == '/' -> {
                    out.span(index, end, RoleCode.COMMENT)
                    index = end
                }
                char == '{' -> {
                    val directive = next == '$'
                    val close = braceEnd(text, index + 1, end)
                    out.span(index, if (close < 0) end else close, if (directive) RoleCode.KEYWORD else RoleCode.COMMENT)
                    if (close < 0) return if (directive) BRACE_DIRECTIVE else BRACE_COMMENT
                    index = close
                }
                char == '(' && next == '*' -> {
                    val directive = index + 2 < end && text[index + 2] == '$'
                    val close = parenEnd(text, index + 2, end)
                    out.span(index, if (close < 0) end else close, if (directive) RoleCode.KEYWORD else RoleCode.COMMENT)
                    if (close < 0) return if (directive) PAREN_DIRECTIVE else PAREN_COMMENT
                    index = close
                }
                char == '\'' -> {
                    if (opensMultiline(text, index, end)) {
                        out.span(index, end, RoleCode.STRING)
                        return MULTILINE_STRING
                    }
                    val close = ClassicScan.doubledQuoteString(text, index, index + 1, end, '\'', out)
                    index = if (close < 0) end else close
                    functionNext = false
                }
                char == '#' && (isDigit(next) || next == '$') -> {
                    var after = index + 1
                    if (text[after] == '$') {
                        after += 1
                        while (after < end && isHexDigit(text[after])) after += 1
                    } else {
                        while (after < end && isDigit(text[after])) after += 1
                    }
                    out.span(index, after, RoleCode.CONSTANT)
                    index = after
                }
                isDigit(char) || (char == '$' && isHexDigit(next)) || (char == '&' && next in '0'..'7') || (char == '%' && (next == '0' || next == '1')) -> {
                    val after = numberEnd(text, index, end)
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                    functionNext = false
                }
                isLetter(char) || char == '_' || (char == '&' && (isLetter(next) || next == '_')) -> {
                    val wordStart = if (char == '&') index + 1 else index
                    val after = ClassicScan.wordEnd(text, wordStart, end)
                    val kind = if (char == '&') 0 else words.lookup(text, wordStart, after)
                    val base = kind and KIND_MASK
                    when {
                        base == KEYWORD -> {
                            out.span(index, after, RoleCode.KEYWORD)
                            functionNext = kind and NAMES_FUNCTION != 0
                            if (kind and STARTS_PROPERTY != 0) inProperty = true
                        }
                        base == PROPERTY_KEYWORD && inProperty -> out.span(index, after, RoleCode.KEYWORD)
                        functionNext -> {
                            // `procedure TForm1.Click`: the class part is a Type, the method a Function.
                            val qualified = after + 1 < end && text[after] == '.' && isLetter(text[after + 1])
                            out.span(index, after, if (qualified) RoleCode.TYPE else RoleCode.FUNCTION)
                            functionNext = qualified
                        }
                        base == TYPE || (base == 0 && char != '&' && looksLikeClass(text, index, after)) -> out.span(index, after, RoleCode.TYPE)
                        base == CONSTANT -> out.span(index, after, RoleCode.CONSTANT)
                        ClassicScan.nextIs(text, after, end, '(') && !nextIsComment(text, after, end) -> out.span(index, after, RoleCode.FUNCTION)
                    }
                    index = after
                }
                char == '.' && next == '.' -> {
                    out.span(index, index + 2, RoleCode.OPERATOR)
                    index += 2
                }
                char == ':' && next == '=' -> {
                    out.span(index, index + 2, RoleCode.OPERATOR)
                    index += 2
                }
                char == '(' || char == ')' || char == '[' || char == ']' || char == ',' || char == ';' || char == ':' || char == '.' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    if (char != '.') functionNext = false
                    if (char == ';') inProperty = false
                }
                isOperator(char) -> {
                    val two = (char == '<' && (next == '>' || next == '=')) || (char == '>' && next == '=') || (next == '=' && (char == '+' || char == '-' || char == '*' || char == '/'))
                    val after = if (two) index + 2 else index + 1
                    out.span(index, after, RoleCode.OPERATOR)
                    index = after
                }
                else -> index += 1
            }
        }
        return 0
    }

    /** Continues a construct open at the line start; returns the offset after it closes, or -1. */
    private fun continueConstruct(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int =
        when (state) {
            BRACE_COMMENT, BRACE_DIRECTIVE -> {
                val close = braceEnd(text, start, end)
                out.span(start, if (close < 0) end else close, if (state == BRACE_DIRECTIVE) RoleCode.KEYWORD else RoleCode.COMMENT)
                close
            }
            PAREN_COMMENT, PAREN_DIRECTIVE -> {
                val close = parenEnd(text, start, end)
                out.span(start, if (close < 0) end else close, if (state == PAREN_DIRECTIVE) RoleCode.KEYWORD else RoleCode.COMMENT)
                close
            }
            else -> {
                // A multi-line string closes at a line whose first non-blank starts `'''`.
                val at = LexerScan.skipBlanks(text, start, end)
                if (LexerScan.startsWith(text, at, end, "'''")) {
                    out.span(start, at + 3, RoleCode.STRING)
                    at + 3
                } else {
                    out.span(start, end, RoleCode.STRING)
                    -1
                }
            }
        }

    /** `'''` followed by nothing but blanks opens a Delphi 12 multi-line string. */
    private fun opensMultiline(
        text: CharArray,
        at: Int,
        end: Int,
    ): Boolean = LexerScan.startsWith(text, at, end, "'''") && LexerScan.skipBlanks(text, at + 3, end) == end

    private fun braceEnd(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        var index = from
        while (index < end) {
            if (text[index] == '}') return index + 1
            index += 1
        }
        return -1
    }

    private fun parenEnd(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        var index = from
        while (index + 1 < end) {
            if (text[index] == '*' && text[index + 1] == ')') return index + 2
            index += 1
        }
        return -1
    }

    /** `(*` right after a name opens a comment, not a call. */
    private fun nextIsComment(
        text: CharArray,
        from: Int,
        end: Int,
    ): Boolean {
        val at = LexerScan.skipBlanks(text, from, end)
        return at + 1 < end && text[at + 1] == '*'
    }

    /** Delphi naming: `TName` and `EName` with a lowercase letter somewhere are types. */
    private fun looksLikeClass(
        text: CharArray,
        from: Int,
        to: Int,
    ): Boolean {
        if (to - from < 3 || (text[from] != 'T' && text[from] != 'E') || text[from + 1] !in 'A'..'Z') return false
        for (index in from + 2 until to) {
            if (text[index] in 'a'..'z') return true
        }
        return false
    }

    private fun numberEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at
        when (text[at]) {
            '$' -> {
                index += 1
                while (index < end && (isHexDigit(text[index]) || text[index] == '_')) index += 1
                return index
            }
            '&' -> {
                index += 1
                while (index < end && (text[index] in '0'..'7' || text[index] == '_')) index += 1
                return index
            }
            '%' -> {
                index += 1
                while (index < end && (text[index] == '0' || text[index] == '1' || text[index] == '_')) index += 1
                return index
            }
        }
        index = ClassicScan.digitsEnd(text, index, end)
        // `1..10` is a range, not a real.
        if (index + 1 < end && text[index] == '.' && isDigit(text[index + 1])) index = ClassicScan.digitsEnd(text, index + 1, end)
        return ClassicScan.exponentEnd(text, index, end, "e")
    }

    private fun isOperator(char: Char): Boolean =
        when (char) {
            '=', '<', '>', '+', '-', '*', '/', '@', '^' -> true
            else -> false
        }

    init {
        words.register(
            KEYWORD,
            "and", "array", "as", "asm", "begin", "case", "class", "const", "dispinterface", "div", "do", "downto",
            "else", "end", "except", "exports", "file", "finalization", "finally", "for", "goto", "if",
            "implementation", "in", "inherited", "initialization", "inline", "interface", "is", "label", "library",
            "mod", "not", "object", "of", "or", "out", "packed", "program", "raise", "record", "repeat",
            "resourcestring", "set", "shl", "shr", "then", "threadvar", "to", "try", "type", "unit", "until", "uses",
            "var", "while", "with", "xor", "absolute", "abstract", "assembler", "cdecl", "deprecated", "dynamic",
            "external", "forward", "helper", "overload", "override", "platform", "private", "protected", "public",
            "published", "reintroduce", "safecall", "sealed", "static", "stdcall", "strict", "virtual", "varargs",
            "generic", "specialize", "self",
        )
        words.register(KEYWORD or NAMES_FUNCTION, "procedure", "function", "constructor", "destructor", "operator")
        words.register(KEYWORD or STARTS_PROPERTY, "property")
        words.register(PROPERTY_KEYWORD, "read", "write", "default", "stored", "index", "implements", "nodefault", "readonly", "writeonly")
        words.register(
            TYPE,
            "integer", "cardinal", "shortint", "smallint", "longint", "int64", "uint64", "byte", "word", "longword",
            "dword", "nativeint", "nativeuint", "boolean", "bytebool", "wordbool", "longbool", "char", "ansichar",
            "widechar", "string", "ansistring", "widestring", "unicodestring", "shortstring", "rawbytestring", "real",
            "single", "double", "extended", "comp", "currency", "pointer", "pchar", "pansichar", "pwidechar", "variant",
            "olevariant", "exception", "tobject", "tclass", "iinterface", "iunknown", "tdatetime",
        )
        words.register(CONSTANT, "true", "false", "nil")
    }
}
