package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit

/**
 * Common Lisp.
 *
 * Roles: `;` comments and nested `#| |#` comments are Comment. Strings are String (across lines)
 * with `\` escapes as Constant; character literals (`#\a`, `#\Space`) are String. Keywords
 * (`:kw`), uninterned symbols (`#:sym`), `nil`, and `t` are Constant; lambda-list markers
 * (`&optional`, `&key`, `&rest`, `&body`) are Keyword. A symbol right after `(` is Keyword when it
 * is a special operator or standard macro (`defun`, `let`, `if`, `loop`, ...) and Function
 * otherwise; so is a symbol after `#'`. The name a `defun`, `defmacro`, `defgeneric`, or
 * `defmethod` defines is Function, a `defvar`, `defparameter`, or `defconstant` name Variable,
 * and a `defclass`, `defstruct`, `deftype`, or `define-condition` name Type; the symbols of the
 * lambda list after a defined function's name (or after `lambda`, `destructuring-bind`, ...) are
 * Variable rather than calls, as are the names a `let` or `do` binds.
 * Numbers (integers, ratios `1/3`, floats `1.5d0`, `#x1F`, `#b101`, `#o17`, `#36rZZ`) are Number.
 * `'`, `` ` ``, `,`, `,@`, `#'`, and `#.` are Operator; `#+feature` and `#-feature` Attribute;
 * parentheses and `#(` Punctuation.
 *
 * States: 0 in code, [STRING] inside a string, and [COMMENT] (with its nesting depth from bit 2)
 * inside a `#|` comment.
 *
 * Known gaps: `|piped symbols|` are read only within one line; a lambda list that continues on
 * the next line reads its symbols as calls again; reader macros beyond the standard ones are
 * plain.
 */
internal object CommonLispLexer : HighlightLexer() {
    private const val MODE_MASK = 3
    private const val STRING = 1
    private const val COMMENT = 2
    private const val DEPTH_SHIFT = 2

    // Word kinds.
    private const val SPECIAL = 1
    private const val CONSTANT = 2
    private const val LAMBDA = 3
    private const val DEFINES_FUNCTION = 4
    private const val DEFINES_VARIABLE = 5
    private const val DEFINES_TYPE = 6
    private const val BINDS = 7

    private val words = LexerWords(caseInsensitive = true)

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        var index = start
        when (state and MODE_MASK) {
            STRING -> {
                val close = SymbolicScan.escapedEnd(text, start, end, '"', out, simpleEscapes = true)
                if (close < 0) return STRING
                index = close
            }
            COMMENT -> {
                val close = SymbolicScan.nestedCommentEnd(text, start, end, state ushr DEPTH_SHIFT, '#', '|', '|', '#')
                if (close < 0) {
                    out.span(start, end, RoleCode.COMMENT)
                    return COMMENT or (-close shl DEPTH_SHIFT)
                }
                out.span(start, close, RoleCode.COMMENT)
                index = close
            }
        }
        var call = false
        var functionQuote = false
        var defines = 0
        // A lambda list comes next (1), or its parentheses are open this deep (2 and more).
        var lambdaList = 0
        // A `let` or `do` binding list comes next (1), or its parentheses are open this deep (2 and more).
        var bindings = 0
        while (index < end && !out.stopped) {
            val char = text[index]
            val next = if (index + 1 < end) text[index + 1] else ' '
            if (char == ' ' || char == '\t') {
                index += 1
                continue
            }
            val wasCall = call
            call = false
            val wasFunctionQuote = functionQuote
            functionQuote = false
            val wasDefines = defines
            defines = 0
            when {
                char == ';' -> {
                    out.span(index, end, RoleCode.COMMENT)
                    return 0
                }
                char == '"' -> {
                    out.span(index, index + 1, RoleCode.STRING)
                    val close = SymbolicScan.escapedEnd(text, index + 1, end, '"', out, simpleEscapes = true)
                    if (close < 0) return STRING
                    index = close
                }
                char == '#' && next == '|' -> {
                    val close = SymbolicScan.nestedCommentEnd(text, index + 2, end, 1, '#', '|', '|', '#')
                    out.span(index, if (close < 0) end else close, RoleCode.COMMENT)
                    if (close < 0) return COMMENT or (-close shl DEPTH_SHIFT)
                    index = close
                    defines = wasDefines
                    call = wasCall
                }
                char == '#' -> {
                    val after = dispatch(text, index, end, out)
                    functionQuote = next == '\''
                    if (next == '(' && lambdaList >= 2) lambdaList += 1
                    index = after
                }
                char == '(' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    if (lambdaList >= 1) {
                        lambdaList += 1
                    } else {
                        call = true
                        if (bindings >= 1) bindings += 1
                    }
                }
                char == ')' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    if (lambdaList >= 2) lambdaList = if (lambdaList == 2) 0 else lambdaList - 1
                    if (bindings >= 2) bindings = if (bindings == 2) 0 else bindings - 1
                }
                char == '\'' || char == '`' -> {
                    out.span(index, index + 1, RoleCode.OPERATOR)
                    index += 1
                }
                char == ',' -> {
                    val after = if (next == '@' || next == '.') index + 2 else index + 1
                    out.span(index, after, RoleCode.OPERATOR)
                    index = after
                }
                char == '|' -> {
                    val close = SymbolicScan.find(text, index + 1, end, "|")
                    index = if (close < 0) index + 1 else close
                }
                else -> {
                    var after = index + SymbolicScan.charLength(text, index, end)
                    while (after < end && isConstituent(text[after])) after += 1
                    if (out.collecting) {
                        val kind = words.lookup(text, index, after)
                        val role =
                            when {
                                isNumber(text, index, after) -> RoleCode.NUMBER
                                char == ':' -> RoleCode.CONSTANT
                                char == '&' -> RoleCode.KEYWORD
                                kind == CONSTANT -> RoleCode.CONSTANT
                                wasFunctionQuote -> RoleCode.FUNCTION
                                lambdaList >= 2 -> RoleCode.VARIABLE
                                bindings == 2 || (bindings == 3 && wasCall) -> RoleCode.VARIABLE
                                wasCall -> if (kind == 0) RoleCode.FUNCTION else RoleCode.KEYWORD
                                wasDefines == DEFINES_FUNCTION -> RoleCode.FUNCTION
                                wasDefines == DEFINES_VARIABLE -> RoleCode.VARIABLE
                                wasDefines == DEFINES_TYPE -> RoleCode.TYPE
                                else -> 0
                            }
                        if (role != 0) out.span(index, after, role)
                        if (wasCall && kind in DEFINES_FUNCTION..DEFINES_TYPE) defines = kind
                        if (wasCall && kind == LAMBDA || wasDefines == DEFINES_FUNCTION) lambdaList = 1
                        if (wasCall && kind == BINDS) bindings = 1
                    }
                    index = after
                }
            }
        }
        return 0
    }

    /** A `#` dispatch at [at] other than `#|`; returns the offset after it. */
    private fun dispatch(
        text: CharArray,
        at: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        val next = if (at + 1 < end) text[at + 1] else ' '
        return when {
            next == '\\' -> {
                if (at + 2 >= end) {
                    out.span(at, end, RoleCode.STRING)
                    return end
                }
                var after = at + 2 + SymbolicScan.charLength(text, at + 2, end)
                if (isAsciiLetter(text[at + 2])) {
                    while (after < end && (isAsciiLetter(text[after]) || isDigit(text[after]) || text[after] == '-' || text[after] == '_')) after += 1
                }
                out.span(at, after, RoleCode.STRING)
                after
            }
            next == '\'' || next == '.' -> {
                out.span(at, at + 2, RoleCode.OPERATOR)
                at + 2
            }
            next == '(' -> {
                out.span(at, at + 2, RoleCode.PUNCTUATION)
                at + 2
            }
            next == '+' || next == '-' -> {
                var after = at + 2
                while (after < end && isConstituent(text[after])) after += 1
                out.span(at, after, RoleCode.ATTRIBUTE)
                after
            }
            next == ':' -> {
                var after = at + 2
                while (after < end && isConstituent(text[after])) after += 1
                out.span(at, after, RoleCode.CONSTANT)
                after
            }
            next == 'x' || next == 'X' || next == 'b' || next == 'B' || next == 'o' || next == 'O' || isDigit(next) -> {
                var after = at + 2
                while (after < end && isConstituent(text[after])) after += 1
                out.span(at, after, if (isDigit(next) && !hasRadix(text, at + 1, after)) RoleCode.OPERATOR else RoleCode.NUMBER)
                after
            }
            isAsciiLetter(next) -> {
                // `#p"..."`, `#c(1 2)`, `#s(...)`: the dispatch letter is an operator on what follows.
                out.span(at, at + 2, RoleCode.OPERATOR)
                at + 2
            }
            else -> {
                out.span(at, at + 1, RoleCode.PUNCTUATION)
                at + 1
            }
        }
    }

    /** True when `text[from, to)` is digits then `r`: a `#36rZZ` radix number. */
    private fun hasRadix(
        text: CharArray,
        from: Int,
        to: Int,
    ): Boolean {
        var index = from
        while (index < to && isDigit(text[index])) index += 1
        return index < to && (text[index] == 'r' || text[index] == 'R')
    }

    /** True when the token `text[from, to)` reads as a number: an integer, a ratio, or a float. */
    private fun isNumber(
        text: CharArray,
        from: Int,
        to: Int,
    ): Boolean {
        var index = from
        if (text[index] == '+' || text[index] == '-') index += 1
        val digitsFrom = index
        while (index < to && isDigit(text[index])) index += 1
        var digits = index - digitsFrom
        if (index == to) return digits > 0
        if (text[index] == '/') {
            val ratioFrom = index + 1
            index = ratioFrom
            while (index < to && isDigit(text[index])) index += 1
            return digits > 0 && index == to && index > ratioFrom
        }
        if (text[index] == '.') {
            index += 1
            val fractionFrom = index
            while (index < to && isDigit(text[index])) index += 1
            digits += index - fractionFrom
            if (index == to) return digits > 0
        }
        if (digits == 0) return false
        if (text[index] !in "eEsSfFdDlL") return false
        index += 1
        if (index < to && (text[index] == '+' || text[index] == '-')) index += 1
        val exponentFrom = index
        while (index < to && isDigit(text[index])) index += 1
        return index == to && index > exponentFrom
    }

    private fun isConstituent(char: Char): Boolean =
        when (char) {
            ' ', '\t', '(', ')', '"', ';', '\'', '`', ',', '|' -> false
            else -> true
        }

    init {
        words.register(
            SPECIAL,
            "defpackage", "in-package", "flet", "labels", "macrolet", "symbol-macrolet", "if", "when", "unless",
            "cond", "case", "ecase", "ccase", "typecase", "etypecase", "ctypecase", "progn", "prog1", "prog2", "block", "return",
            "return-from", "loop", "dolist", "dotimes", "tagbody", "go", "catch", "throw", "unwind-protect",
            "handler-case", "handler-bind", "ignore-errors", "restart-case", "setq",
            "setf", "psetq", "psetf", "incf", "decf", "push", "pop", "pushnew", "quote", "function", "the", "declare", "declaim",
            "proclaim", "locally", "eval-when", "load-time-value", "and", "or", "with-open-file", "with-accessors",
            "with-output-to-string", "with-input-from-string", "check-type", "assert", "defsetf", "define-compiler-macro",
            "define-modify-macro", "define-symbol-macro", "multiple-value-prog1", "progv", "defsystem",
        )
        words.register(LAMBDA, "lambda", "multiple-value-bind", "destructuring-bind", "with-slots")
        words.register(BINDS, "let", "let*", "do", "do*")
        words.register(CONSTANT, "nil", "t")
        words.register(DEFINES_FUNCTION, "defun", "defmacro", "defgeneric", "defmethod")
        words.register(DEFINES_VARIABLE, "defvar", "defparameter", "defconstant")
        words.register(DEFINES_TYPE, "defclass", "defstruct", "deftype", "define-condition")
    }
}
