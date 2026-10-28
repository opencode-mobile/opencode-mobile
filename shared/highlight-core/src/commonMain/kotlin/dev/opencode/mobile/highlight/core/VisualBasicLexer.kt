package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.ClassicScan.isLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * Visual Basic: VB.NET first, leniently also VB6, VBA, and VBScript.
 *
 * Roles: keywords are Keyword in any case (conversion operators like `CInt` and `DirectCast`
 * too); `Me`, `MyBase`, and `MyClass` are Keyword; `True`, `False`, and `Nothing` are Constant.
 * Built-in types (`Integer`, `String`, ...) and the name after `As`, `New`, `Of`, `Inherits`,
 * `Implements`, `Class`, `Module`, `Structure`, `Interface`, `Enum`, or `Namespace` are Type; the
 * name after `Sub`, `Function`, `Property`, `Event`, `Operator`, `AddressOf`, or `RaiseEvent` is
 * Function, as is a name before `(` after `.`, `Call`, or at the start of a statement (an array
 * index elsewhere looks the same, so it stays plain). `'` and `REM` comments are Comment.
 * Strings `"..."` (with a `c` suffix for a Char) are String with a doubled `""` as Constant; in
 * an interpolated `$"..."` string `{{` and `}}` are Constant, `{` and `}` are Punctuation around
 * code, and a `:format` is String. `#1/1/2020#` date literals are Constant; numbers (`&HFF`,
 * `&O17`, `&B1010`, `1_000`, `1.5E3`, `10UL`, `2.5D`) are Number. A line-leading `#If`,
 * `#Region`, or `#Const` is a Punctuation `#` and a Keyword name. An attribute (`<Serializable>`,
 * `<DllImport("x")>` where a value cannot be) has Punctuation brackets and an Attribute name.
 * Operators are Operator; `( ) { } , . :` and a line-continuing `_` are Punctuation.
 *
 * States: the low 2 bits hold what is open at the line end: 0 code, [STRING] (VB 14 strings span
 * lines), [INTERPOLATED] string text, or [HOLE], the code of an interpolation, with its open brace
 * depth (up to 15) in bits 2-5. A string inside a hole ends with its line.
 *
 * Known gaps: XML literals lex as attributes and operators; strings nested in a hole do not
 * interpolate.
 */
internal object VisualBasicLexer : HighlightLexer() {
    private const val CODE = 0
    private const val STRING = 1
    private const val INTERPOLATED = 2
    private const val HOLE = 3
    private const val MODE_MASK = 3
    private const val DEPTH_SHIFT = 2
    private const val MAX_DEPTH = 15
    private const val MAX_DATE = 40

    // Word kinds.
    private const val KEYWORD = 1
    private const val TYPE = 2
    private const val CONSTANT = 3
    private const val KIND_MASK = 3
    private const val NAMES_FUNCTION = 4
    private const val NAMES_TYPE = 8
    private const val CALLS = 16
    private const val REM = 32

    private val words = LexerWords(caseInsensitive = true)

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int = Line(text, start, end, out).run(state)

    /** One line's scan; a small object per line keeps the many flags readable. */
    private class Line(
        val text: CharArray,
        val start: Int,
        val end: Int,
        val out: SpanSink,
    ) {
        var index = start
        var mode = CODE
        var depth = 0
        var statementStart = true
        var valueBefore = false
        var afterDot = false
        var nameRole = 0
        var callNext = false
        var inAttribute = false
        var attributeNameNext = false
        var parens = 0

        fun run(state: Int): Int {
            mode = state and MODE_MASK
            depth = (state ushr DEPTH_SHIFT) and MAX_DEPTH
            statementStart = mode == CODE
            if (mode == CODE) {
                val first = LexerScan.skipBlanks(text, start, end)
                if (first + 1 < end && text[first] == '#' && isLetter(text[first + 1])) directive(first)
            }
            while (index < end && !out.stopped) {
                when (mode) {
                    STRING -> {
                        val close = ClassicScan.doubledQuoteString(text, index, index, end, '"', out)
                        if (close < 0) return STRING
                        index = charSuffix(close)
                        mode = CODE
                        value()
                    }
                    INTERPOLATED -> interpolated()
                    else -> code()
                }
            }
            return when (mode) {
                STRING, INTERPOLATED -> mode
                HOLE -> HOLE or (depth shl DEPTH_SHIFT)
                else -> CODE
            }
        }

        /** After a closing `"`, a `c` suffix makes the literal a Char. */
        fun charSuffix(close: Int): Int {
            if (close < end && (text[close] == 'c' || text[close] == 'C') && !(close + 1 < end && ClassicScan.isWordPart(text[close + 1]))) {
                out.span(close, close + 1, RoleCode.STRING)
                return close + 1
            }
            return close
        }

        fun value() {
            valueBefore = true
            statementStart = false
            nameRole = 0
            callNext = false
        }

        /** `#If`, `#Region`, `#End Region`: Punctuation `#` and Keyword words. */
        fun directive(at: Int) {
            out.span(at, at + 1, RoleCode.PUNCTUATION)
            val nameEnd = ClassicScan.wordEnd(text, at + 1, end)
            out.span(at + 1, nameEnd, RoleCode.KEYWORD)
            index = nameEnd
            if (ClassicScan.equalsIgnoringCase(text, at + 1, nameEnd, "end")) {
                val second = LexerScan.skipBlanks(text, nameEnd, end)
                val secondEnd = ClassicScan.wordEnd(text, second, end)
                out.span(second, secondEnd, RoleCode.KEYWORD)
                index = secondEnd
            }
            statementStart = false
        }

        /** Interpolated string text up to its close, a hole, or the line end. */
        fun interpolated() {
            var segment = index
            while (index < end) {
                val char = text[index]
                val next = if (index + 1 < end) text[index + 1] else ' '
                when {
                    char == '"' && next == '"' || char == '{' && next == '{' || char == '}' && next == '}' -> {
                        out.span(segment, index, RoleCode.STRING)
                        out.span(index, index + 2, RoleCode.CONSTANT)
                        index += 2
                        segment = index
                    }
                    char == '"' -> {
                        out.span(segment, index + 1, RoleCode.STRING)
                        index += 1
                        mode = CODE
                        value()
                        return
                    }
                    char == '{' -> {
                        out.span(segment, index, RoleCode.STRING)
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                        mode = HOLE
                        depth = 0
                        valueBefore = false
                        return
                    }
                    else -> index += 1
                }
            }
            out.span(segment, end, RoleCode.STRING)
        }

        fun code() {
            val char = text[index]
            val next = if (index + 1 < end) text[index + 1] else ' '
            val wasAfterDot = afterDot
            if (char != ' ' && char != '\t') afterDot = false
            when {
                char == ' ' || char == '\t' -> index += 1
                char == '\'' || char == '‘' || char == '’' -> {
                    out.span(index, end, RoleCode.COMMENT)
                    index = end
                }
                char == '"' -> {
                    if (mode == HOLE) {
                        holeString(index + 1, index)
                    } else {
                        val close = ClassicScan.doubledQuoteString(text, index, index + 1, end, '"', out)
                        if (close < 0) {
                            mode = STRING
                            index = end
                            return
                        }
                        index = charSuffix(close)
                        value()
                    }
                }
                char == '$' && next == '"' -> {
                    if (mode == HOLE) {
                        holeString(index + 2, index)
                    } else {
                        out.span(index, index + 2, RoleCode.STRING)
                        index += 2
                        mode = INTERPOLATED
                    }
                }
                char == '#' -> date()
                char == '&' && (next == 'h' || next == 'H' || next == 'o' || next == 'O' || next == 'b' || next == 'B') && index + 2 < end && isHexDigit(text[index + 2]) -> {
                    var after = index + 2
                    while (after < end && (isHexDigit(text[after]) || text[after] == '_')) after += 1
                    after = suffixEnd(after)
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                    value()
                }
                isDigit(char) || (char == '.' && isDigit(next) && !valueBefore) -> {
                    var after = ClassicScan.digitsEnd(text, index, end)
                    if (after + 1 < end && text[after] == '.' && isDigit(text[after + 1])) after = ClassicScan.digitsEnd(text, after + 1, end)
                    after = ClassicScan.exponentEnd(text, after, end, "e")
                    after = suffixEnd(after)
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                    value()
                }
                char == '[' && index + 1 < end && isLetter(next) -> {
                    // `[Error]`: an escaped name.
                    var close = index + 1
                    while (close < end && close - index < 256 && text[close] != ']') close += 1
                    if (close < end && text[close] == ']') {
                        index = close + 1
                        value()
                    } else {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                    }
                }
                isLetter(char) || (char == '_' && index + 1 < end && ClassicScan.isWordPart(next)) -> word(wasAfterDot)
                char == '_' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                }
                char == '<' && !valueBefore && inAttributeStart() -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    inAttribute = true
                    attributeNameNext = true
                    parens = 0
                    statementStart = false
                }
                char == '>' && inAttribute && parens == 0 -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    inAttribute = false
                    attributeNameNext = false
                    valueBefore = false
                }
                char == '{' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    if (mode == HOLE && depth < MAX_DEPTH) depth += 1
                    valueBefore = false
                }
                char == '}' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    if (mode == HOLE) {
                        if (depth == 0) {
                            mode = INTERPOLATED
                            return
                        }
                        depth -= 1
                    }
                    valueBefore = true
                }
                char == ':' && mode == HOLE && depth == 0 && parens == 0 && next != '=' -> {
                    // A format: `{x:N2}`.
                    var close = index + 1
                    while (close < end && text[close] != '}' && text[close] != '"') close += 1
                    out.span(index, close, RoleCode.STRING)
                    index = close
                }
                char == ':' && next == '=' -> {
                    out.span(index, index + 2, RoleCode.OPERATOR)
                    index += 2
                    valueBefore = false
                }
                char == ':' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    if (!inAttribute) {
                        statementStart = true
                        nameRole = 0
                        callNext = false
                    }
                    valueBefore = false
                }
                char == '(' || char == ')' || char == ',' || char == '.' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    when (char) {
                        '(' -> {
                            parens += 1
                            attributeNameNext = false
                            valueBefore = false
                            nameRole = 0
                        }
                        ')' -> {
                            if (parens > 0) parens -= 1
                            value()
                        }
                        ',' -> {
                            if (inAttribute && parens == 0) attributeNameNext = true
                            valueBefore = false
                            nameRole = 0
                        }
                        else -> {
                            afterDot = true
                            valueBefore = false
                        }
                    }
                    statementStart = false
                }
                isOperator(char) -> {
                    var after = index + 1
                    if (after < end && (text[after] == '=' || (char == '<' && (text[after] == '>' || text[after] == '<')) || (char == '>' && text[after] == '>'))) after += 1
                    if (after < end && text[after] == '=' && after == index + 2 && (char == '<' || char == '>') && text[index + 1] == char) after += 1
                    out.span(index, after, RoleCode.OPERATOR)
                    index = after
                    valueBefore = false
                    statementStart = false
                    nameRole = 0
                }
                else -> {
                    index += 1
                    valueBefore = false
                }
            }
        }

        /** A string inside a hole: one line, no interpolation. */
        fun holeString(
            from: Int,
            segment: Int,
        ) {
            val close = ClassicScan.doubledQuoteString(text, segment, from, end, '"', out)
            index = if (close < 0) end else charSuffix(close)
            value()
        }

        /** `<` that opens an attribute: a name (or `Assembly:` target) right after it. */
        fun inAttributeStart(): Boolean = index + 1 < end && isLetter(text[index + 1])

        fun word(afterDot: Boolean) {
            var after = ClassicScan.wordEnd(text, index, end)
            // `Left$(`: a type character on a name.
            if (after < end && text[after] == '$' && !(after + 1 < end && text[after + 1] == '"')) after += 1
            val kind = if (afterDot) 0 else words.lookup(text, index, after)
            val wasStatementStart = statementStart
            statementStart = false
            if (kind and REM != 0 && wasStatementStart && (after == end || text[after] == ' ' || text[after] == '\t')) {
                out.span(index, end, RoleCode.COMMENT)
                index = end
                return
            }
            val base = kind and KIND_MASK
            when {
                inAttribute && attributeNameNext && base != KEYWORD -> {
                    out.span(index, after, RoleCode.ATTRIBUTE)
                    valueBefore = true
                }
                base == KEYWORD -> {
                    out.span(index, after, RoleCode.KEYWORD)
                    nameRole =
                        when {
                            kind and NAMES_FUNCTION != 0 -> RoleCode.FUNCTION
                            kind and NAMES_TYPE != 0 -> RoleCode.TYPE
                            else -> 0
                        }
                    callNext = kind and CALLS != 0
                    // `Me`, `MyBase`: a tick or `<` after them compares.
                    valueBefore = kind and CALLS == 0 && kind and (NAMES_FUNCTION or NAMES_TYPE) == 0 && isValueKeyword(index, after)
                }
                base == TYPE -> {
                    out.span(index, after, RoleCode.TYPE)
                    nameRole = 0
                    valueBefore = true
                }
                base == CONSTANT -> {
                    out.span(index, after, RoleCode.CONSTANT)
                    value()
                }
                else -> {
                    val role =
                        when {
                            nameRole != 0 -> nameRole
                            (afterDot || callNext || wasStatementStart) && ClassicScan.nextIs(text, after, end, '(') -> RoleCode.FUNCTION
                            else -> 0
                        }
                    if (role != 0) out.span(index, after, role)
                    // `As System.Text.StringBuilder`: a dotted name keeps its role.
                    if (!(after < end && text[after] == '.')) nameRole = 0
                    if (!(after < end && text[after] == '.')) callNext = false
                    valueBefore = true
                }
            }
            index = after
        }

        fun isValueKeyword(
            from: Int,
            to: Int,
        ): Boolean =
            ClassicScan.equalsIgnoringCase(text, from, to, "me") || ClassicScan.equalsIgnoringCase(text, from, to, "mybase") ||
                ClassicScan.equalsIgnoringCase(text, from, to, "myclass")

        /** `#1/1/2020#` (Constant) where a value may start; any other `#` is an Operator. */
        fun date() {
            if (!valueBefore) {
                var close = index + 1
                while (close < end && close - index <= MAX_DATE && isDateChar(text[close])) close += 1
                if (close < end && close > index + 1 && text[close] == '#' && isDigit(text[index + 1])) {
                    out.span(index, close + 1, RoleCode.CONSTANT)
                    index = close + 1
                    value()
                    return
                }
            }
            out.span(index, index + 1, RoleCode.OPERATOR)
            index += 1
        }

        fun isDateChar(char: Char): Boolean = isDigit(char) || char == '/' || char == '-' || char == ':' || char == ' ' || char == '.' || ClassicScan.isLetter(char) && char.code < 0x80

        /** Type suffixes on a number: `S`, `I`, `L`, `US`, `UI`, `UL`, `D`, `F`, `R`, `C`, `@`, `!`, `%`. */
        fun suffixEnd(at: Int): Int {
            if (at >= end) return at
            var after = at
            val char = text[at]
            when {
                char == 'u' || char == 'U' -> if (at + 1 < end && "sSiIlL".indexOf(text[at + 1]) >= 0) after = at + 2
                "sSiIlLdDfFrR".indexOf(char) >= 0 -> after = at + 1
                char == '@' || char == '!' || char == '%' -> return at + 1
            }
            return if (after < end && ClassicScan.isWordPart(text[after])) at else after
        }

        fun isOperator(char: Char): Boolean =
            when (char) {
                '=', '<', '>', '+', '-', '*', '/', '\\', '^', '&', '?', '!' -> true
                else -> false
            }
    }

    init {
        words.register(
            KEYWORD,
            "addhandler", "alias", "and", "andalso", "assembly", "async", "await", "byref", "byval",
            "case", "catch", "cbool", "cbyte", "cchar", "cdate", "cdbl", "cdec", "cint", "clng", "cobj", "const",
            "continue", "csbyte", "cshort", "csng", "cstr", "ctype", "cuint", "culng", "cushort", "custom", "declare",
            "default", "dim", "directcast", "do", "each", "else", "elseif", "end", "endif", "erase", "error", "exit",
            "explicit", "finally", "for", "friend", "get", "gettype", "global", "gosub", "goto", "handles", "if", "in",
            "infer", "is", "isnot", "iterator", "let", "lib", "like", "loop", "mod", "mustinherit", "mustoverride",
            "narrowing", "next", "not", "notinheritable", "notoverridable", "on", "option", "optional", "or", "orelse",
            "overloads", "overridable", "overrides", "paramarray", "partial", "preserve", "private", "protected",
            "public", "readonly", "redim", "removehandler", "resume", "return", "select", "set", "shadows", "shared",
            "static", "step", "stop", "strict", "synclock", "then", "throw", "to", "try", "trycast", "typeof",
            "until", "using", "wend", "when", "while", "widening", "with", "withevents", "writeonly", "xor", "yield",
            "me", "mybase", "myclass", "nameof", "from", "where", "aggregate", "distinct", "group", "join", "into",
            "order", "by", "ascending", "descending", "imports", "namespace",
        )
        words.register(KEYWORD or NAMES_FUNCTION, "sub", "function", "property", "event", "operator")
        words.register(KEYWORD or NAMES_FUNCTION or CALLS, "addressof", "raiseevent")
        words.register(KEYWORD or CALLS, "call")
        words.register(KEYWORD or NAMES_TYPE, "as", "new", "of", "inherits", "implements", "class", "module", "structure", "interface", "enum", "delegate", "type")
        words.register(
            TYPE,
            "boolean", "byte", "char", "date", "decimal", "double", "integer", "long", "object", "sbyte", "short",
            "single", "string", "uinteger", "ulong", "ushort", "variant", "currency",
        )
        words.register(CONSTANT, "true", "false", "nothing", "null", "empty")
        words.register(KEYWORD or REM, "rem")
    }
}
