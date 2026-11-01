package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.ClassicScan.isLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * SAS: DATA and PROC steps, the macro language, and the PROC SQL vocabulary.
 *
 * Roles: statement and step keywords (`data`, `set`, `if`, `run`, `quit`, `proc`, `select`, ...)
 * are Keyword in any case, except before `=` (`label = 1`, `data=work.x`) and after `.`
 * (`sashelp.class`, which is never a call either); the procedure name after `proc` and the macro
 * name after `%macro` are Function, as are other names before `(` (`cats(`, `put(`) and macro calls
 * (`%mymac`, `%sysfunc`); macro statements (`%let`, `%if`, `%do`, `%mend`, ...) are Keyword, and a
 * macro call that starts a statement ends it with its arguments (it needs no `;`); macro variable
 * references (`&name`, `&&name`, `&name.`) are Variable, also inside double-quoted strings. Strings
 * `'...'` and `"..."` are String with a doubled quote as Constant; date and time literals
 * (`'01jan2020'd`, `'12:00't`, `'...'dt`) are Constant and name literals (`'my var'n`) Variable.
 * Numbers (`1.5e3`, `0FFx`) are Number, as is a missing value `.` where a value may start. `/* */`
 * comments, `*...;` statement comments, and `%*...;` macro comments are Comment; DATALINES (CARDS)
 * data is String. Operators (`=`, `**`, `||`, `^=`, `<>`, ...) are Operator; `( ) , ; . :` are
 * Punctuation.
 *
 * States: the low 3 bits hold what is open at the line end: 0 code, a [BLOCK_COMMENT], a [SINGLE]
 * or [DOUBLE] string, a [STATEMENT_COMMENT] or [MACRO_COMMENT] waiting for its `;`, or
 * [DATALINES] or [DATALINES4] data. [MID_STATEMENT] marks code (or a block comment within code)
 * past the start of a statement, where `*` multiplies instead of starting a comment.
 *
 * Known gaps: the text of a statement comment ends at its first `;`, also one in quotes; a
 * date literal opened on one line and closed on another is String; DATALINES data starts only
 * after a `datalines;` written as one statement, and the rest of that line stays plain.
 */
internal object SasLexer : HighlightLexer() {
    private const val MODE_MASK = 7
    private const val BLOCK_COMMENT = 1
    private const val SINGLE = 2
    private const val DOUBLE = 3
    private const val STATEMENT_COMMENT = 4
    private const val MACRO_COMMENT = 5
    private const val DATALINES = 6
    private const val DATALINES4 = 7
    private const val MID_STATEMENT = 8

    // Word kinds.
    private const val KEYWORD = 1
    private const val MACRO_KEYWORD = 2
    private const val MACRO_FUNCTION = 3
    private const val CONSTANT = 4
    private const val KIND_MASK = 7
    private const val NAMES_FUNCTION = 8
    private const val CALLABLE = 16
    private const val STARTS_DATA = 32
    private const val STARTS_DATA4 = 64

    private val words = LexerWords(caseInsensitive = true)
    private val macroWords = LexerWords(caseInsensitive = true)

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        var index = start
        var mid = state and MID_STATEMENT != 0
        when (state and MODE_MASK) {
            BLOCK_COMMENT -> {
                val close = LexerScan.blockCommentEnd(text, start, end)
                out.span(start, if (close < 0) end else close, RoleCode.COMMENT)
                if (close < 0) return state
                index = close
            }
            SINGLE, DOUBLE -> {
                val quote = if (state and MODE_MASK == SINGLE) '\'' else '"'
                val close = stringBody(text, start, start, end, quote, out)
                if (close < 0) return state
                index = close
                mid = true
            }
            STATEMENT_COMMENT, MACRO_COMMENT -> {
                val close = statementCommentEnd(text, start, end)
                out.span(start, if (close < 0) end else close, RoleCode.COMMENT)
                if (close < 0) return state
                index = close
                mid = false
            }
            DATALINES, DATALINES4 -> {
                val four = state and MODE_MASK == DATALINES4
                val first = LexerScan.skipBlanks(text, start, end)
                val ends = if (four) LexerScan.startsWith(text, first, end, ";;;;") else indexOfSemicolon(text, start, end) >= 0
                if (!ends) {
                    out.span(start, end, RoleCode.STRING)
                    return state
                }
                val semicolon = if (four) first else indexOfSemicolon(text, start, end)
                out.span(start, semicolon, RoleCode.STRING)
                val after = if (four) semicolon + 4 else semicolon + 1
                out.span(semicolon, after, RoleCode.PUNCTUATION)
                index = after
                mid = false
            }
        }
        var valueBefore = false
        var functionNext = false
        var dataNext = 0
        // A macro call that starts a statement needs no `;`: the statement ends with its arguments.
        var callDepth = -1
        while (index < end && !out.stopped) {
            val char = text[index]
            val next = if (index + 1 < end) text[index + 1] else ' '
            val wasMid = mid
            val hadValue = valueBefore
            if (char != ' ' && char != '\t') {
                mid = true
                valueBefore = false
            }
            when {
                char == ' ' || char == '\t' -> index += 1
                char == '/' && next == '*' -> {
                    mid = wasMid
                    val close = LexerScan.blockCommentEnd(text, index + 2, end)
                    out.span(index, if (close < 0) end else close, RoleCode.COMMENT)
                    if (close < 0) return BLOCK_COMMENT or (if (wasMid) MID_STATEMENT else 0)
                    index = close
                }
                char == '*' && !wasMid || char == '%' && next == '*' -> {
                    val close = statementCommentEnd(text, index + 1, end)
                    out.span(index, if (close < 0) end else close, RoleCode.COMMENT)
                    if (close < 0) return if (char == '*') STATEMENT_COMMENT else MACRO_COMMENT
                    index = close
                    mid = false
                }
                char == ';' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    mid = false
                    functionNext = false
                    if (dataNext != 0) return dataNext
                }
                char == '\'' || char == '"' -> {
                    val close = ClassicScan.doubledQuoteEnd(text, index + 1, end, char)
                    val suffixRole = if (close < 0) 0 else suffixRole(text, close, end)
                    if (suffixRole != 0) {
                        val after = suffixEnd(text, close, end)
                        out.span(index, after, suffixRole)
                        index = after
                    } else {
                        val after = stringBody(text, index, index + 1, end, char, out)
                        if (after < 0) return (if (char == '\'') SINGLE else DOUBLE) or MID_STATEMENT
                        index = after
                    }
                    valueBefore = true
                    functionNext = false
                }
                char == '&' && isMacroReference(text, index, end) -> {
                    val after = macroReferenceEnd(text, index, end)
                    out.span(index, after, RoleCode.VARIABLE)
                    index = after
                    valueBefore = true
                }
                char == '%' && isLetter(next) -> {
                    val after = ClassicScan.wordEnd(text, index + 1, end)
                    val kind = macroWords.lookup(text, index + 1, after)
                    out.span(index, after, if (kind and KIND_MASK == MACRO_KEYWORD) RoleCode.KEYWORD else RoleCode.FUNCTION)
                    functionNext = kind and NAMES_FUNCTION != 0
                    index = after
                    if (kind == 0 && !wasMid) {
                        if (ClassicScan.nextIs(text, after, end, '(')) callDepth = 0 else mid = false
                    }
                }
                isDigit(char) || (char == '.' && isDigit(next)) -> {
                    val after = numberEnd(text, index, end)
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                    valueBefore = true
                    functionNext = false
                }
                char == '.' && !hadValue -> {
                    // A missing value: `.`, or a special one like `.A` or `._`.
                    var after = index + 1
                    if (after < end && (text[after] == '_' || LexerScan.isAsciiLetter(text[after])) && !(after + 1 < end && ClassicScan.isWordPart(text[after + 1]))) after += 1
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                    valueBefore = true
                }
                isLetter(char) || char == '_' -> {
                    val after = ClassicScan.wordEnd(text, index + 1, end)
                    // `sashelp.class`: a member name after `.` is never a keyword or a call.
                    val member = index > start && text[index - 1] == '.'
                    val kind = if (member) 0 else words.lookup(text, index, after)
                    val call = !member && ClassicScan.nextIs(text, after, end, '(')
                    when {
                        functionNext -> {
                            out.span(index, after, RoleCode.FUNCTION)
                            functionNext = false
                        }
                        kind and KIND_MASK == KEYWORD && !(call && kind and CALLABLE != 0) && !isAssigned(text, after, end) -> {
                            out.span(index, after, RoleCode.KEYWORD)
                            functionNext = kind and NAMES_FUNCTION != 0
                            if (!wasMid && kind and (STARTS_DATA or STARTS_DATA4) != 0 && ClassicScan.nextIs(text, after, end, ';')) {
                                dataNext = if (kind and STARTS_DATA4 != 0) DATALINES4 else DATALINES
                            }
                        }
                        kind and KIND_MASK == CONSTANT -> out.span(index, after, RoleCode.CONSTANT)
                        call -> out.span(index, after, RoleCode.FUNCTION)
                    }
                    index = after
                    valueBefore = true
                }
                char == '(' || char == ')' || char == ',' || char == '.' || char == ':' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    valueBefore = char == ')'
                    functionNext = false
                    if (callDepth >= 0 && char == '(') callDepth += 1
                    if (callDepth > 0 && char == ')') {
                        callDepth -= 1
                        if (callDepth == 0) {
                            mid = false
                            callDepth = -1
                        }
                    }
                }
                isOperator(char) -> {
                    val two = isOperator(next) && isTwoCharOperator(char, next)
                    val after = if (two) index + 2 else index + 1
                    out.span(index, after, RoleCode.OPERATOR)
                    index = after
                    functionNext = false
                }
                else -> index += 1
            }
        }
        return if (mid) MID_STATEMENT else 0
    }

    /**
     * A string body from [from]; [segment] is where its String span starts. A doubled quote is
     * Constant and, in double quotes, a macro reference is Variable. Returns the offset after the
     * close, or -1 when the line ends first.
     */
    private fun stringBody(
        text: CharArray,
        segment: Int,
        from: Int,
        end: Int,
        quote: Char,
        out: SpanSink,
    ): Int {
        var index = from
        var open = segment
        while (index < end) {
            val char = text[index]
            if (char == quote) {
                if (index + 1 < end && text[index + 1] == quote) {
                    out.span(open, index, RoleCode.STRING)
                    out.span(index, index + 2, RoleCode.CONSTANT)
                    index += 2
                    open = index
                    continue
                }
                out.span(open, index + 1, RoleCode.STRING)
                return index + 1
            }
            if (quote == '"' && char == '&' && isMacroReference(text, index, end)) {
                val after = macroReferenceEnd(text, index, end)
                out.span(open, index, RoleCode.STRING)
                out.span(index, after, RoleCode.VARIABLE)
                index = after
                open = index
                continue
            }
            index += 1
        }
        out.span(open, end, RoleCode.STRING)
        return -1
    }

    /** The role a literal's suffix gives it: `d`, `t`, `dt` Constant, `n` Variable; 0 for none. */
    private fun suffixRole(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        val after = suffixEnd(text, at, end)
        if (after == at) return 0
        return if (text[at] == 'n' || text[at] == 'N') RoleCode.VARIABLE else RoleCode.CONSTANT
    }

    private fun suffixEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        if (at >= end) return at
        val first = text[at]
        var after =
            when (first) {
                'd', 'D' -> if (at + 1 < end && (text[at + 1] == 't' || text[at + 1] == 'T')) at + 2 else at + 1
                't', 'T', 'n', 'N' -> at + 1
                else -> return at
            }
        if (after < end && ClassicScan.isWordPart(text[after])) after = at
        return after
    }

    /** `label = 1`, `data=x`: a keyword before `=` is a variable or an option name. */
    private fun isAssigned(
        text: CharArray,
        after: Int,
        end: Int,
    ): Boolean = ClassicScan.nextIs(text, after, end, '=')

    /** `&name` or `&&name`: ampersands then a name start. */
    private fun isMacroReference(
        text: CharArray,
        at: Int,
        end: Int,
    ): Boolean {
        var index = at
        while (index < end && text[index] == '&') index += 1
        return index < end && (isLetter(text[index]) || text[index] == '_')
    }

    /** The end of a macro reference at [at], with one trailing `.` delimiter. */
    private fun macroReferenceEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at
        while (index < end && text[index] == '&') index += 1
        index = ClassicScan.wordEnd(text, index, end)
        if (index < end && text[index] == '.') index += 1
        return index
    }

    private fun statementCommentEnd(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        val semicolon = indexOfSemicolon(text, from, end)
        return if (semicolon < 0) -1 else semicolon + 1
    }

    private fun indexOfSemicolon(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        var index = from
        while (index < end) {
            if (text[index] == ';') return index
            index += 1
        }
        return -1
    }

    private fun numberEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        // `0FFx`: a hexadecimal constant starts with a digit and ends with `x`.
        if (isDigit(text[at])) {
            var hex = at
            while (hex < end && isHexDigit(text[hex])) hex += 1
            if (hex < end && (text[hex] == 'x' || text[hex] == 'X') && !(hex + 1 < end && ClassicScan.isWordPart(text[hex + 1]))) return hex + 1
        }
        var index = at
        while (index < end && isDigit(text[index])) index += 1
        if (index < end && text[index] == '.') {
            index += 1
            while (index < end && isDigit(text[index])) index += 1
        }
        return ClassicScan.exponentEnd(text, index, end, "e")
    }

    private fun isOperator(char: Char): Boolean =
        when (char) {
            '=', '<', '>', '+', '-', '*', '/', '^', '~', '¬', '|', '!', '&' -> true
            else -> false
        }

    private fun isTwoCharOperator(
        first: Char,
        second: Char,
    ): Boolean =
        when (first) {
            '*' -> second == '*'
            '|' -> second == '|'
            '!' -> second == '!'
            '<' -> second == '=' || second == '>'
            '>' -> second == '=' || second == '<'
            '^', '~', '¬', '=' -> second == '='
            else -> false
        }

    init {
        words.register(
            KEYWORD,
            "data", "set", "merge", "update", "modify", "by", "if", "then", "else", "do", "end", "to", "until", "while",
            "output", "run", "quit", "retain", "keep", "drop", "rename", "format", "informat", "label", "infile",
            "file", "array", "where", "select", "when", "otherwise", "stop", "return", "delete", "leave", "continue",
            "of", "and", "or", "not", "in", "eq", "ne", "lt", "le", "gt", "ge", "libname", "filename", "options",
            "title", "footnote", "ods", "goto", "link", "attrib", "missing", "call", "var", "class", "model",
            "tables", "table", "freq", "weight", "from", "group", "order", "having", "create", "as", "join",
            "on", "inner", "outer", "full", "into", "values", "distinct", "case", "insert", "view", "calculated",
            "abort", "error", "endsas", "descending", "ascending", "nodupkey", "noprint",
        )
        words.register(KEYWORD or NAMES_FUNCTION, "proc")
        words.register(KEYWORD or CALLABLE, "input", "put", "length", "left", "right", "sum")
        words.register(KEYWORD or STARTS_DATA, "datalines", "cards", "lines")
        words.register(KEYWORD or STARTS_DATA4, "datalines4", "cards4", "lines4")
        words.register(CONSTANT, "_n_", "_error_", "_all_", "_numeric_", "_character_", "_null_", "_data_", "_last_", "_infile_")
        macroWords.register(
            MACRO_KEYWORD,
            "let", "mend", "if", "then", "else", "do", "end", "to", "by", "while", "until", "put", "global", "local",
            "include", "goto", "return", "abort", "syscall", "sysexec", "symdel", "window", "display", "input", "copy",
            "sysmstoreclear", "syslput", "sysrput",
        )
        macroWords.register(MACRO_KEYWORD or NAMES_FUNCTION, "macro")
    }
}
