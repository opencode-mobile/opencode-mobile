package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit

/**
 * Elixir.
 *
 * Roles: `#` comments are Comment. Strings, charlists, heredocs (`"""`, `'''`), and sigils
 * (`~r/.../i`, `~s(...)`, `~w[...]a`, `~S"""...""""`, every delimiter, modifiers included) are
 * String with escapes as Constant; in interpolating ones (all but uppercase sigils) `#{` and
 * `}` are Punctuation around code. Paired sigil delimiters nest. Atoms (`:ok`, `:"quoted"`,
 * `:+`) and keyword keys (`key:`) are Constant, as are `true`, `false`, `nil`, and
 * `__MODULE__`-style names. Module names are Type; `@attr` module attributes are Variable;
 * keywords are Keyword (`defmodule`, `do`, `fn`, `when`, ...). A name after `def`, `defp`,
 * `defmacro`, or `defguard`, before `(`, or after `Module.` is Function; after another `.` it is
 * Property. `?a` character codes and numbers are Number; operators are Operator; brackets, `,`,
 * `;`, `.`, and `%` (maps and structs) are Punctuation.
 *
 * States: a [LiteralStackLexer] stack of strings, sigils, quoted atoms, and `#{` interpolations;
 * all of them stay open across lines.
 *
 * Known gaps: a heredoc closes at any `"""`, not only at the start of a line; a literal opened
 * past three levels of nesting colors to its close on its line without interpolation;
 * `"quoted": value` keys read as a string and an operator.
 */
internal object ElixirLexer : LiteralStackLexer(elixirKinds()) {
    private const val STRING = FIRST_KIND
    private const val CHARLIST = FIRST_KIND + 1
    private const val HEREDOC = FIRST_KIND + 2
    private const val CHARLIST_HEREDOC = FIRST_KIND + 3
    private const val ATOM_DOUBLE = FIRST_KIND + 4
    private const val ATOM_SINGLE = FIRST_KIND + 5
    private const val SIGIL = FIRST_KIND + 6
    private const val RAW_SIGIL = FIRST_KIND + 16

    // Word kinds: a role code in the low byte plus flags.
    private const val DEFINES = 1 shl 8

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
        var afterDot = false
        var afterModuleDot = false
        var defineNext = false
        // True while the last token was a module name, so `.name` after it is a function.
        var moduleBefore = false
        while (index < end && !out.stopped) {
            if (inLiteral(st)) {
                val result = literal(text, index, end, st, out)
                st = stateOf(result)
                index = offsetOf(result)
                afterDot = false
                moduleBefore = false
                continue
            }
            val char = text[index]
            if (char == ' ' || char == '\t') {
                index += 1
                continue
            }
            val wasAfterDot = afterDot
            afterDot = false
            val wasModuleDot = afterModuleDot
            afterModuleDot = false
            val wasDefine = defineNext
            defineNext = false
            val wasModule = moduleBefore
            moduleBefore = false
            val next = if (index + 1 < end) text[index + 1] else '\u0000'
            when {
                char == '#' -> {
                    out.span(index, end, RoleCode.COMMENT)
                    index = end
                }
                char == '"' || char == '\'' -> {
                    val triple = next == char && index + 2 < end && text[index + 2] == char
                    val code =
                        if (char == '"') {
                            if (triple) HEREDOC else STRING
                        } else {
                            if (triple) CHARLIST_HEREDOC else CHARLIST
                        }
                    val result = openLiteral(text, index, index + if (triple) 3 else 1, end, code, st, out)
                    st = stateOf(result)
                    index = offsetOf(result)
                }
                char == '~' && isAsciiLetter(next) && sigilAt(text, index, end) > 0 -> {
                    val open = sigilAt(text, index, end)
                    val delimiter = text[open]
                    val triple = (delimiter == '"' || delimiter == '\'') && LexerScan.startsWith(text, open, end, if (delimiter == '"') "\"\"\"" else "'''")
                    val slot = if (triple) (if (delimiter == '"') 8 else 9) else ElixirSigils.OPENERS.indexOf(delimiter)
                    val base = if (next in 'A'..'Z') RAW_SIGIL else SIGIL
                    val result = openLiteral(text, index, open + if (triple) 3 else 1, end, base + slot, st, out)
                    st = stateOf(result)
                    index = offsetOf(result)
                }
                char == '?' && next != '\u0000' && next != ' ' && next != '\t' -> {
                    val after =
                        when {
                            next == '\\' -> minOf(index + 3, end)
                            next.isHighSurrogate() && index + 2 < end && text[index + 2].isLowSurrogate() -> index + 3
                            else -> index + 2
                        }
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                }
                char == ':' -> {
                    when {
                        next == ':' -> {
                            out.span(index, index + 2, RoleCode.OPERATOR)
                            index += 2
                        }
                        next == '"' || next == '\'' -> {
                            val result = openLiteral(text, index, index + 2, end, if (next == '"') ATOM_DOUBLE else ATOM_SINGLE, st, out)
                            st = stateOf(result)
                            index = offsetOf(result)
                        }
                        nameStartLength(text, index + 1, end) > 0 -> {
                            var after = nameEnd(text, index + 1, end)
                            if (after < end && (text[after] == '?' || text[after] == '!')) after += 1
                            out.span(index, after, RoleCode.CONSTANT)
                            index = after
                        }
                        isOperatorChar(next) -> {
                            var after = index + 2
                            while (after < end && after < index + 4 && isOperatorChar(text[after])) after += 1
                            out.span(index, after, RoleCode.CONSTANT)
                            index = after
                        }
                        else -> {
                            out.span(index, index + 1, RoleCode.PUNCTUATION)
                            index += 1
                        }
                    }
                }
                char == '@' && nameStartLength(text, index + 1, end) > 0 -> {
                    val after = nameEnd(text, index + 1, end)
                    out.span(index, after, RoleCode.VARIABLE)
                    index = after
                }
                isDigit(char) -> {
                    val after = numberEnd(text, index, end)
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                }
                nameStartLength(text, index, end) > 0 -> {
                    var after = nameEnd(text, index, end)
                    if (after < end && (text[after] == '?' || text[after] == '!')) after += 1
                    if (!wasAfterDot && after + 1 < end && text[after] == ':' && (text[after + 1] == ' ' || text[after + 1] == '\t')) {
                        // A keyword key: `key: value`, `do: x`.
                        out.span(index, after + 1, RoleCode.CONSTANT)
                        index = after + 1
                    } else if (after == end - 1 && text[after] == ':' && !wasAfterDot) {
                        out.span(index, after + 1, RoleCode.CONSTANT)
                        index = after + 1
                    } else {
                        val first = text[index]
                        val kind = if (wasAfterDot) 0 else words.lookup(text, index, after)
                        val call = after < end && text[after] == '('
                        val role =
                            when {
                                first in 'A'..'Z' -> {
                                    moduleBefore = true
                                    RoleCode.TYPE
                                }
                                kind != 0 -> kind and 0xFF
                                wasDefine -> RoleCode.FUNCTION
                                wasModuleDot || call -> RoleCode.FUNCTION
                                wasAfterDot -> RoleCode.PROPERTY
                                else -> 0
                            }
                        if (role != 0) out.span(index, after, role)
                        defineNext = kind and DEFINES != 0
                        index = after
                    }
                }
                char == '{' || char == '(' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    st = openBracket(st, char)
                    index += 1
                }
                char == '}' || char == ')' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    st = closeBracket(st, char)
                    index += 1
                }
                char == '[' || char == ']' || char == ',' || char == ';' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                }
                char == '%' && (next == '{' || next in 'A'..'Z' || next == '_') -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                }
                char == '.' && next != '.' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    afterDot = true
                    afterModuleDot = wasModule
                    // `def Mod.name`: rare, but keep the function name pending.
                    defineNext = wasDefine
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

    /** For `~` at [at] followed by a letter: the offset of the sigil's opening delimiter, or 0 when it is not a sigil. */
    private fun sigilAt(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at + 1
        if (text[index] in 'a'..'z') {
            index += 1
        } else {
            // Uppercase sigils may have several letters (`~HTML`), digits included.
            while (index < end && index < at + 12 && (text[index] in 'A'..'Z' || isDigit(text[index]))) index += 1
        }
        if (index >= end) return 0
        return if (ElixirSigils.OPENERS.indexOf(text[index]) >= 0) index else 0
    }

    private fun isOperatorChar(char: Char): Boolean =
        when (char) {
            '+', '-', '*', '/', '=', '<', '>', '!', '&', '|', '^', '~', '\\', '.', '%' -> true
            else -> false
        }

    init {
        words.register(
            RoleCode.KEYWORD,
            "do", "end", "fn", "when", "and", "or", "not", "in", "case", "cond", "if", "unless", "else", "try", "catch", "rescue",
            "after", "raise", "reraise", "receive", "quote", "unquote", "unquote_splicing", "import", "alias", "require", "use",
            "with", "for", "throw", "super", "defmodule", "defprotocol", "defimpl", "defstruct", "defexception", "defoverridable",
        )
        words.register(
            RoleCode.KEYWORD or DEFINES,
            "def", "defp", "defmacro", "defmacrop", "defguard", "defguardp", "defdelegate", "defn", "defnp",
        )
        words.register(RoleCode.CONSTANT, "true", "false", "nil", "__MODULE__", "__DIR__", "__ENV__", "__CALLER__", "__STACKTRACE__")
    }
}

/** Sigil delimiters, in the order of their kinds (then the two heredoc forms). */
private object ElixirSigils {
    const val CLOSERS = "/|\"')]}>"
    const val OPENERS = "/|\"'([{<"
}

private fun elixirKinds(): Array<LiteralKind> {
    val list = mutableListOf(
        LiteralKind("\"", interpolation = LiteralStackLexer.HASH_BRACE),
        LiteralKind("'", interpolation = LiteralStackLexer.HASH_BRACE),
        LiteralKind("\"\"\"", interpolation = LiteralStackLexer.HASH_BRACE),
        LiteralKind("'''", interpolation = LiteralStackLexer.HASH_BRACE),
        LiteralKind("\"", role = RoleCode.CONSTANT, interpolation = LiteralStackLexer.HASH_BRACE),
        LiteralKind("'", role = RoleCode.CONSTANT, interpolation = LiteralStackLexer.HASH_BRACE),
    )
    for (raw in listOf(false, true)) {
        val interpolation = if (raw) LiteralStackLexer.NO_INTERPOLATION else LiteralStackLexer.HASH_BRACE
        val escapes = if (raw) LiteralStackLexer.ESCAPE_CLOSER else LiteralStackLexer.ESCAPE_ALL
        for (slot in 0 until 8) {
            val close = ElixirSigils.CLOSERS[slot]
            val open = ElixirSigils.OPENERS[slot]
            list += LiteralKind(close.toString(), open = if (open != close) open.toString() else "", interpolation = interpolation, escapes = escapes, suffix = true)
        }
        list += LiteralKind("\"\"\"", interpolation = interpolation, escapes = escapes, suffix = true)
        list += LiteralKind("'''", interpolation = interpolation, escapes = escapes, suffix = true)
    }
    return list.toTypedArray()
}
