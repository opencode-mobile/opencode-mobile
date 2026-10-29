package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.ClassicScan.isLetter
import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit

/**
 * Fortran in its two source forms: [freeForm] (Fortran 90 and later) and [fixedForm]
 * (FORTRAN 77 card layout).
 *
 * Fixed form, by column: a `C`, `c`, `*`, or `!` in column 1 makes the line a Comment; columns 1-5
 * hold a statement label (Number); a character other than blank or `0` in column 6 marks a
 * continuation line (Punctuation); the statement takes columns 7-72, and anything past column 72
 * is Comment. A tab in columns 1-6 ends the label field (DEC tab form: a digit after it marks a
 * continuation). A label field holding anything but digits and blanks lexes as statement text.
 * Free form: `!` starts a comment and `&` (Punctuation) continues a line, also inside a string.
 *
 * Roles, both forms: keywords are Keyword in any case, unless followed by `=` or `%` or listed
 * after `::` (Fortran reserves no words, so `data = 1` and `integer :: data` are variables). Intrinsic types (`integer`, `real`,
 * `character`, `double precision`, ...) and the name after `type` or `class` are Type. The name
 * after `program`, `module`, `subroutine`, `function`, `procedure`, `call`, or `entry` (also after
 * `end subroutine` and the like) is Function, as are common intrinsic procedures before `(`; a
 * component after `%` is Property. `.true.` and `.false.` are Constant; `.and.`, `.eq.`, and other
 * dotted operators are Operator. Strings `'...'` and `"..."` are String with a doubled quote as
 * Constant; numbers (`1.0_dp`, `1.5d-3`, `b'1010'`, `z"FF"`) are Number. Operators (`=`, `==`,
 * `/=`, `::`, `=>`, `**`, `//`, `*`, ...) are Operator; `( ) [ ] , ; : %` and `(/ /)` Punctuation.
 * A `#` directive in column 1 is a Punctuation `#` and a Keyword name.
 *
 * States: 0, or the quote of a string still open at the line end ([SINGLE] or [DOUBLE]): in free
 * form after a trailing `&`; in fixed form until the next line that is not a continuation (comment
 * and blank lines keep the state).
 */
internal class FortranLexer private constructor(
    private val fixed: Boolean,
) : HighlightLexer() {
    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int = if (fixed) fixedLine(text, start, end, state, out) else freeLine(text, start, end, state, out)

    private fun freeLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        var index = start
        if (state != 0) {
            // A continued string resumes after the `&` that opens the next line, or at its start.
            val at = LexerScan.skipBlanks(text, start, end)
            if (at < end && text[at] == '&') {
                out.span(at, at + 1, RoleCode.PUNCTUATION)
                index = at + 1
            }
        } else if (start < end && text[start] == '#') {
            index = directive(text, start, end, out)
        }
        var trailing = end
        while (trailing > index && (text[trailing - 1] == ' ' || text[trailing - 1] == '\t')) trailing -= 1
        val ampersand = if (trailing > index && text[trailing - 1] == '&') trailing - 1 else -1
        return statement(text, index, end, quoteOf(state), ampersand, out)
    }

    private fun fixedLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        // A blank line is a comment line: an open string waits for the next continuation.
        if (LexerScan.skipBlanks(text, start, end) == end) return state
        val first = text[start]
        if (first == 'C' || first == 'c' || first == '*' || first == '!') {
            out.span(start, end, RoleCode.COMMENT)
            return state
        }
        if (first == '#') {
            return statement(text, directive(text, start, end, out), end, NONE, -1, out)
        }
        val limit = if (end - start > STATEMENT_COLUMNS) start + STATEMENT_COLUMNS else end
        var index = start
        var codeStart = -1
        var continuation = false
        while (index < start + LABEL_COLUMNS && index < end) {
            val char = text[index]
            when {
                char == '\t' -> {
                    // Tab form: a nonzero digit right after the tab marks a continuation.
                    if (index + 1 < end && text[index + 1] in '1'..'9') {
                        out.span(index + 1, index + 2, RoleCode.PUNCTUATION)
                        continuation = true
                        codeStart = index + 2
                    } else {
                        codeStart = index + 1
                    }
                    break
                }
                isDigit(char) -> out.span(index, index + 1, RoleCode.NUMBER)
                char == ' ' -> {}
                else -> {
                    // Not a label: free text in the label field lexes as a statement.
                    codeStart = index
                    break
                }
            }
            index += 1
        }
        if (codeStart < 0) {
            val column6 = start + LABEL_COLUMNS
            if (column6 < end && text[column6] != ' ' && text[column6] != '0' && text[column6] != '\t') {
                out.span(column6, column6 + 1, RoleCode.PUNCTUATION)
                continuation = true
                codeStart = column6 + 1
            } else {
                codeStart = minOf(column6 + 1, end)
            }
        }
        val quote = if (continuation) quoteOf(state) else NONE
        val result =
            when {
                codeStart < limit -> statement(text, codeStart, limit, quote, -1, out)
                continuation -> state
                else -> 0
            }
        if (end > limit) out.span(limit, end, RoleCode.COMMENT)
        return result
    }

    /** `#` and the directive name at the line start; the offset after the name. */
    private fun directive(
        text: CharArray,
        at: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        out.span(at, at + 1, RoleCode.PUNCTUATION)
        val nameStart = LexerScan.skipBlanks(text, at + 1, end)
        var nameEnd = nameStart
        while (nameEnd < end && isAsciiLetter(text[nameEnd])) nameEnd += 1
        out.span(nameStart, nameEnd, RoleCode.KEYWORD)
        return nameEnd
    }

    /**
     * Statement text `text[from, limit)`, starting inside a string when [quote] is not [NONE].
     * [ampersand] is the free-form trailing `&`, or -1. Returns the end state.
     */
    private fun statement(
        text: CharArray,
        from: Int,
        limit: Int,
        quote: Char,
        ampersand: Int,
        out: SpanSink,
    ): Int {
        var index = from
        if (quote != NONE) {
            val close = continueString(text, index, limit, quote, ampersand, out)
            if (close < 0) return -close - 1
            index = close
        }
        var functionNext = false
        var typeNext = false
        var afterPercent = false
        // Past `::` a declaration lists entity names, so `integer :: data` declares a variable.
        var entities = false
        while (index < limit && !out.stopped) {
            val char = text[index]
            val next = if (index + 1 < limit) text[index + 1] else ' '
            val wasAfterPercent = afterPercent
            if (char != ' ' && char != '\t') afterPercent = false
            when {
                char == ' ' || char == '\t' -> index += 1
                char == '!' -> {
                    out.span(index, limit, RoleCode.COMMENT)
                    index = limit
                }
                char == '&' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                }
                char == '\'' || char == '"' -> {
                    val close = continueString(text, index + 1, limit, char, ampersand, out, index)
                    if (close < 0) return -close - 1
                    index = close
                    functionNext = false
                }
                isBozStart(char) && (next == '\'' || next == '"') -> {
                    val close = ClassicScan.doubledQuoteEnd(text, index + 2, limit, next)
                    val after = if (close < 0) limit else close
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                }
                isDigit(char) || (char == '.' && isDigit(next)) -> {
                    val after = numberEnd(text, index, limit)
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                    functionNext = false
                }
                char == '.' && isAsciiLetter(next) && dottedEnd(text, index, limit) > 0 -> {
                    val close = dottedEnd(text, index, limit)
                    var after = close
                    val logical = ClassicScan.equalsIgnoringCase(text, index + 1, close - 1, "true") || ClassicScan.equalsIgnoringCase(text, index + 1, close - 1, "false")
                    if (logical && after < limit && text[after] == '_') after = ClassicScan.wordEnd(text, after + 1, limit)
                    out.span(index, after, if (logical) RoleCode.CONSTANT else RoleCode.OPERATOR)
                    index = after
                }
                isLetter(char) -> {
                    var after = ClassicScan.wordEnd(text, index + 1, limit)
                    while (after < limit && text[after] == '$') after += 1
                    if (wasAfterPercent) {
                        out.span(index, after, RoleCode.PROPERTY)
                        index = after
                        continue
                    }
                    val kind = words.lookup(text, index, after)
                    val assigned = kind and KIND_MASK != INTRINSIC && kind != 0 && (entities || isAssignedOrSelected(text, after, limit))
                    when {
                        kind == 0 || assigned -> {
                            if (functionNext) {
                                out.span(index, after, RoleCode.FUNCTION)
                            } else if (typeNext) {
                                out.span(index, after, RoleCode.TYPE)
                            }
                            functionNext = false
                            typeNext = false
                        }
                        kind and KIND_MASK == INTRINSIC -> {
                            if (functionNext) {
                                out.span(index, after, RoleCode.FUNCTION)
                            } else if (typeNext) {
                                out.span(index, after, RoleCode.TYPE)
                            } else if (ClassicScan.nextIs(text, after, limit, '(')) {
                                out.span(index, after, RoleCode.FUNCTION)
                            }
                            functionNext = false
                            typeNext = false
                        }
                        kind and KIND_MASK == TYPE -> {
                            out.span(index, after, RoleCode.TYPE)
                            functionNext = kind and NAMES_FUNCTION != 0
                        }
                        else -> {
                            out.span(index, after, RoleCode.KEYWORD)
                            functionNext = kind and NAMES_FUNCTION != 0
                            // `type, public :: t` and `type is (t)`: attributes keep the type name pending.
                            if (kind and NAMES_TYPE != 0) typeNext = true
                        }
                    }
                    index = after
                }
                char == '%' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    afterPercent = true
                }
                char == '(' && next == '/' && !(index + 2 < limit && (text[index + 2] == '/' || text[index + 2] == '=' || text[index + 2] == ')')) -> {
                    out.span(index, index + 2, RoleCode.PUNCTUATION)
                    index += 2
                }
                char == '/' && next == ')' -> {
                    out.span(index, index + 2, RoleCode.PUNCTUATION)
                    index += 2
                }
                char == ':' && next == ':' -> {
                    out.span(index, index + 2, RoleCode.OPERATOR)
                    index += 2
                    entities = true
                }
                char == '(' || char == ')' || char == '[' || char == ']' || char == ',' || char == ';' || char == ':' || char == '.' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    if (char != '(' && char != ',') typeNext = false
                    if (char == ';') functionNext = false
                }
                isOperator(char) -> {
                    val two =
                        (char == '*' && next == '*') || (char == '/' && (next == '/' || next == '=')) || (char == '=' && (next == '=' || next == '>')) ||
                            ((char == '<' || char == '>') && next == '=')
                    val after = if (two) index + 2 else index + 1
                    out.span(index, after, RoleCode.OPERATOR)
                    index = after
                    functionNext = false
                    typeNext = false
                }
                else -> index += 1
            }
        }
        return 0
    }

    /**
     * A string body from [from] up to its [quote]; [segment] is where its String span starts.
     * Returns the offset after the close, or, when it stays open, `-(state + 1)` of the end state:
     * the quote's state when it continues on the next line (free form: only before a trailing `&`,
     * which is Punctuation), else 0.
     */
    private fun continueString(
        text: CharArray,
        from: Int,
        limit: Int,
        quote: Char,
        ampersand: Int,
        out: SpanSink,
        segment: Int = from,
    ): Int {
        val bodyEnd = if (!fixed && ampersand >= from) ampersand else limit
        val close = ClassicScan.doubledQuoteString(text, segment, from, bodyEnd, quote, out)
        if (close >= 0) return close
        if (fixed) return -stateOf(quote) - 1
        if (ampersand >= from) {
            out.span(ampersand, ampersand + 1, RoleCode.PUNCTUATION)
            return -stateOf(quote) - 1
        }
        // An unterminated free-form string ends with its line.
        return -1
    }

    private fun numberEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = digitsEnd(text, at, end)
        // `1.eq.2` compares 1 with 2; `1.e5` and `1.` are reals.
        if (index < end && text[index] == '.' && !(index + 1 < end && isAsciiLetter(text[index + 1]) && dottedEnd(text, index, end) > 0)) {
            index = digitsEnd(text, index + 1, end)
        }
        index = exponentEnd(text, index, end)
        // A kind parameter: `1.0_dp`, `42_int64`.
        if (index + 1 < end && text[index] == '_' && (isLetter(text[index + 1]) || isDigit(text[index + 1]))) {
            index = ClassicScan.wordEnd(text, index + 1, end)
        }
        return index
    }

    /** Fortran has no digit separators: `_` starts a kind parameter. */
    private fun digitsEnd(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        var index = from
        while (index < end && isDigit(text[index])) index += 1
        return index
    }

    /** An `e`, `d`, or `q` exponent at [at]; the offset after it, or [at]. */
    private fun exponentEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        if (at >= end || "eEdDqQ".indexOf(text[at]) < 0) return at
        var index = at + 1
        if (index < end && (text[index] == '+' || text[index] == '-')) index += 1
        if (index >= end || !isDigit(text[index])) return at
        return digitsEnd(text, index, end)
    }

    /** The offset after a dotted operator or logical (`.and.`, `.true.`) at [at], or -1. */
    private fun dottedEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at + 1
        while (index < end && index - at <= MAX_DOTTED && isAsciiLetter(text[index])) index += 1
        return if (index > at + 1 && index < end && text[index] == '.') index + 1 else -1
    }

    /** `data = 1`, `value%x`: a keyword used as a variable. */
    private fun isAssignedOrSelected(
        text: CharArray,
        from: Int,
        end: Int,
    ): Boolean {
        val at = LexerScan.skipBlanks(text, from, end)
        if (at >= end) return false
        if (text[at] == '%') return true
        return text[at] == '=' && !(at + 1 < end && (text[at + 1] == '=' || text[at + 1] == '>'))
    }

    private fun isBozStart(char: Char): Boolean = char == 'b' || char == 'B' || char == 'o' || char == 'O' || char == 'z' || char == 'Z'

    private fun isOperator(char: Char): Boolean =
        when (char) {
            '=', '<', '>', '+', '-', '*', '/' -> true
            else -> false
        }

    private fun quoteOf(state: Int): Char =
        when (state) {
            SINGLE -> '\''
            DOUBLE -> '"'
            else -> NONE
        }

    private fun stateOf(quote: Char): Int = if (quote == '\'') SINGLE else DOUBLE

    companion object {
        private const val SINGLE = 1
        private const val DOUBLE = 2
        private const val NONE = '\u0000'
        private const val LABEL_COLUMNS = 5
        private const val STATEMENT_COLUMNS = 72
        private const val MAX_DOTTED = 32

        // Word kinds.
        private const val KEYWORD = 1
        private const val TYPE = 2
        private const val INTRINSIC = 3
        private const val KIND_MASK = 3
        private const val NAMES_FUNCTION = 4
        private const val NAMES_TYPE = 8

        private val words = LexerWords(caseInsensitive = true)

        val freeForm = FortranLexer(fixed = false)
        val fixedForm = FortranLexer(fixed = true)

        init {
            words.register(
                KEYWORD,
                "end", "contains", "return", "use", "only", "implicit", "none", "intrinsic", "external", "interface",
                "abstract", "generic", "operator", "assignment", "if", "then", "else", "elseif", "endif", "do", "enddo",
                "while", "select", "case", "default", "selectcase", "endselect", "where", "elsewhere", "endwhere",
                "forall", "endforall", "continue", "cycle", "exit", "stop", "goto", "go", "to", "print", "read", "write",
                "open", "close", "inquire", "rewind", "backspace", "endfile", "format", "allocate", "deallocate",
                "nullify", "associate", "endassociate", "block", "endblock", "critical", "endcritical", "intent", "in",
                "out", "inout", "parameter", "dimension", "allocatable", "pointer", "target", "save", "data", "common",
                "equivalence", "namelist", "public", "private", "protected", "optional", "value", "volatile",
                "asynchronous", "bind", "result", "recursive", "pure", "elemental", "impure", "non_recursive", "extends",
                "sequence", "include", "import", "enum", "enumerator", "endenum", "concurrent", "deferred", "nopass",
                "pass", "non_overridable", "final", "endtype", "endinterface", "error", "rank", "is", "simple",
            )
            words.register(
                KEYWORD or NAMES_FUNCTION,
                "program", "module", "submodule", "subroutine", "function", "procedure", "call", "entry", "endprogram",
                "endmodule", "endsubroutine", "endfunction", "endprocedure", "endsubmodule",
            )
            words.register(KEYWORD or NAMES_TYPE, "type", "class")
            words.register(
                TYPE,
                "integer", "real", "complex", "character", "logical", "double", "precision", "doubleprecision",
                "doublecomplex", "byte",
            )
            words.register(
                INTRINSIC,
                "abs", "sqrt", "exp", "log", "log10", "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "sinh", "cosh",
                "tanh", "min", "max", "mod", "modulo", "sign", "floor", "ceiling", "nint", "int", "dble", "cmplx", "aimag",
                "conjg", "size", "shape", "lbound", "ubound", "allocated", "associated", "present", "len", "len_trim",
                "trim", "adjustl", "adjustr", "index", "scan", "verify", "achar", "iachar", "char", "ichar", "sum",
                "product", "maxval", "minval", "maxloc", "minloc", "count", "any", "all", "dot_product", "matmul",
                "transpose", "reshape", "spread", "pack", "unpack", "merge", "huge", "tiny", "epsilon", "kind",
                "selected_real_kind", "selected_int_kind", "bit_size", "btest", "iand", "ior", "ieor", "ishft", "not",
                "random_number", "random_seed", "cpu_time", "system_clock", "date_and_time", "get_command_argument",
                "command_argument_count", "move_alloc", "null", "transfer", "storage_size", "c_loc", "c_f_pointer",
                "repeat", "new_line", "huge", "real_kind",
            )
        }
    }
}
