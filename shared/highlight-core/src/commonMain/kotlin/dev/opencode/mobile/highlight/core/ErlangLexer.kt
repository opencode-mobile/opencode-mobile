package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit

/**
 * Erlang.
 *
 * Roles: `%` comments are Comment. Strings (`"..."`, across lines, and OTP 27 `"""` strings)
 * and `$c` characters are String, with escapes and `~p`-style format directives as Constant.
 * Atoms, quoted (`'atom'`) or not, are Constant, as are `?MACRO` uses; an atom before `(` or
 * `/arity` (`fun label/1`, `-export([label/1])`) is Function, before `:` (a module) or after
 * `#` (a record) Type, and after `R#rec.` Property. Variables (capitalized or `_`) are Variable.
 * A `-module` style attribute at the line start has `-` as Punctuation and its name as Keyword,
 * and the module, behaviour, or record it names is Type. Keywords are Keyword (`fun`, `case`,
 * `receive`, `when`, `andalso`, ...). Numbers (`16#FF`, `1.5e3`) are Number; operators are
 * Operator; brackets, `<<` `>>`, `,`, `;`, `.`, `:`, `#`, `|` are Punctuation.
 *
 * States: a [LiteralStackLexer] stack holding the open string; strings and triple-quoted
 * strings stay open across lines, quoted atoms end with their line.
 *
 * Known gaps: a `"""` string closes at any `"""`; OTP 27 sigils (`~"..."`, `~b"""`) read as
 * an operator and a plain string.
 */
internal object ErlangLexer : LiteralStackLexer(
    arrayOf(
        LiteralKind("\"", format = true),
        LiteralKind("'", role = RoleCode.CONSTANT, singleLine = true),
        LiteralKind("\"\"\"", escapes = ESCAPE_NONE),
    ),
) {
    private const val STRING = FIRST_KIND
    private const val ATOM = FIRST_KIND + 1
    private const val TRIPLE = FIRST_KIND + 2

    // What the token before a name was, for its role.
    private const val NONE = 0
    private const val ATTRIBUTE = 1
    private const val ATTRIBUTE_ARGUMENT = 2
    private const val RECORD = 3
    private const val RECORD_FIELD = 4
    private const val AFTER_ATTRIBUTE = 5

    private val words = LexerWords()

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        var st = state
        var index = start
        var context = NONE
        // The attribute whose argument names a module or record (Type) or a macro (Constant).
        var attributeRole = 0
        while (index < end && !out.stopped) {
            if (inLiteral(st)) {
                val result = literal(text, index, end, st, out)
                st = stateOf(result)
                index = offsetOf(result)
                context = NONE
                continue
            }
            val char = text[index]
            if (char == ' ' || char == '\t') {
                index += 1
                continue
            }
            val before = context
            context = NONE
            val next = if (index + 1 < end) text[index + 1] else '\u0000'
            when {
                char == '%' -> {
                    out.span(index, end, RoleCode.COMMENT)
                    index = end
                }
                char == '"' -> {
                    val triple = next == '"' && index + 2 < end && text[index + 2] == '"'
                    val result = openLiteral(text, index, index + if (triple) 3 else 1, end, if (triple) TRIPLE else STRING, st, out)
                    st = stateOf(result)
                    index = offsetOf(result)
                }
                char == '\'' -> {
                    val result = openLiteral(text, index, index + 1, end, ATOM, st, out)
                    st = stateOf(result)
                    index = offsetOf(result)
                }
                char == '$' && next != '\u0000' -> {
                    val after = characterEnd(text, index, end)
                    out.span(index, after, RoleCode.STRING)
                    index = after
                }
                isDigit(char) -> {
                    var after = numberEnd(text, index, end)
                    if (after + 1 < end && text[after] == '#' && LexerScan.isHexDigit(text[after + 1]) || after + 1 < end && text[after] == '#' && isAsciiLetter(text[after + 1])) {
                        // `16#FF`, `36#Zz`.
                        after += 1
                        while (after < end && (isAsciiLetter(text[after]) || isDigit(text[after]) || text[after] == '_')) after += 1
                    }
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                }
                char == '-' && index == start && next in 'a'..'z' -> {
                    // An attribute: `-module(name).`
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    context = ATTRIBUTE
                }
                char == '?' -> {
                    var after = index + 1
                    if (after < end && text[after] == '?') after += 1
                    after = atomEnd(text, after, end)
                    out.span(index, after, if (after > index + 1) RoleCode.CONSTANT else RoleCode.OPERATOR)
                    index = after
                }
                char == '#' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    context = RECORD
                }
                char in 'a'..'z' -> {
                    val after = atomEnd(text, index, end)
                    val following = if (after < end) text[after] else '\u0000'
                    val kind = words.lookup(text, index, after)
                    val role =
                        when {
                            before == ATTRIBUTE -> {
                                attributeRole =
                                    when {
                                        LexerScan.equals(text, index, after, "define") -> RoleCode.CONSTANT
                                        isTypeAttribute(text, index, after) -> RoleCode.TYPE
                                        else -> 0
                                    }
                                context = AFTER_ATTRIBUTE
                                RoleCode.KEYWORD
                            }
                            before == RECORD -> {
                                context = RECORD_FIELD
                                RoleCode.TYPE
                            }
                            before == RECORD_FIELD -> RoleCode.PROPERTY
                            before == ATTRIBUTE_ARGUMENT && attributeRole != 0 -> attributeRole
                            kind != 0 -> kind
                            following == '(' -> RoleCode.FUNCTION
                            following == ':' && (after + 1 >= end || (text[after + 1] != ':' && text[after + 1] != '=')) -> RoleCode.TYPE
                            following == '/' && after + 1 < end && isDigit(text[after + 1]) -> RoleCode.FUNCTION
                            else -> RoleCode.CONSTANT
                        }
                    out.span(index, after, role)
                    index = after
                }
                char in 'A'..'Z' || char == '_' -> {
                    val after = atomEnd(text, index, end)
                    out.span(index, after, if (before == ATTRIBUTE_ARGUMENT && attributeRole != 0) attributeRole else RoleCode.VARIABLE)
                    index = after
                }
                char == '<' && next == '<' || char == '>' && next == '>' -> {
                    out.span(index, index + 2, RoleCode.PUNCTUATION)
                    index += 2
                }
                char == '.' -> {
                    if (next == '.') {
                        val after = if (index + 2 < end && text[index + 2] == '.') index + 3 else index + 2
                        out.span(index, after, RoleCode.OPERATOR)
                        index = after
                    } else {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                        if (before == RECORD_FIELD) context = RECORD_FIELD
                    }
                }
                char == ':' && next != ':' && next != '=' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                }
                char == '(' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    if (before == AFTER_ATTRIBUTE) context = ATTRIBUTE_ARGUMENT
                }
                char == ')' || char == '[' || char == ']' || char == '{' || char == '}' || char == ',' || char == ';' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                }
                char == '|' && next != '|' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                }
                isOperatorChar(char) -> {
                    var after = index + 1
                    while (after < end && after < index + 3 && isOperatorChar(text[after])) after += 1
                    out.span(index, after, RoleCode.OPERATOR)
                    index = after
                }
                else -> index += 1
            }
        }
        if (out.stopped) return st
        return finishLine(st)
    }

    private fun isTypeAttribute(
        text: CharArray,
        from: Int,
        to: Int,
    ): Boolean =
        LexerScan.equals(text, from, to, "module") || LexerScan.equals(text, from, to, "behaviour") ||
            LexerScan.equals(text, from, to, "behavior") || LexerScan.equals(text, from, to, "record")

    /** The end of an atom or variable name: letters, digits, `_`, and `@`. */
    private fun atomEnd(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        var index = from
        while (index < end && (isNamePart(text[index]) || text[index] == '@')) index += 1
        return index
    }

    /** The offset after a `$c` character at [at]: `$a`, `$\n`, `$\x{41}`, `$\101`, `$\^A`, `$😀`. */
    private fun characterEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        val char = text[at + 1]
        if (char.isHighSurrogate() && at + 2 < end && text[at + 2].isLowSurrogate()) return at + 3
        if (char != '\\') return at + 2
        if (at + 2 >= end) return at + 2
        val escaped = text[at + 2]
        if (escaped == 'x' && at + 3 < end && text[at + 3] == '{') {
            var index = at + 4
            val limit = minOf(end, at + 14)
            while (index < limit && text[index] != '}') index += 1
            return if (index < limit) index + 1 else at + 3
        }
        if (escaped in '0'..'7') {
            var index = at + 3
            while (index < end && index < at + 5 && text[index] in '0'..'7') index += 1
            return index
        }
        if (escaped == '^' && at + 3 < end) return at + 4
        return at + 3
    }

    private fun isOperatorChar(char: Char): Boolean =
        when (char) {
            '+', '-', '*', '/', '=', '<', '>', '!', '|', ':' -> true
            else -> false
        }

    init {
        words.register(
            RoleCode.KEYWORD,
            "after", "begin", "case", "catch", "cond", "end", "fun", "if", "let", "of", "receive", "try", "when", "maybe",
            "else", "and", "andalso", "band", "bnot", "bor", "bsl", "bsr", "bxor", "div", "not", "or", "orelse", "rem", "xor",
        )
    }
}
