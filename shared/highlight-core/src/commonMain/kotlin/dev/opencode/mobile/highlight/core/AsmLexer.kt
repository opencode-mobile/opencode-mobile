package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * Assembly, generically: x86 in NASM, MASM, and GAS (AT&T) syntax, ARM and AArch64, and the
 * RISC-V register names, without an instruction table.
 *
 * Roles: `;`, `//`, and `/* … */` (across lines) comments Comment; `#` is a comment when it
 * opens a line (unless a C preprocessor directive follows, which is Keyword with an `#include`
 * path String) or is followed by a blank, and otherwise an ARM immediate's Punctuation; `@`
 * followed by a blank (ARM) is a comment too. A statement's first word is its mnemonic: Keyword,
 * and a prefix (`lock`, `rep`) keeps the next word a mnemonic. `name:` (and `1:`, `@@:`) labels
 * are Function with `:` Punctuation, and so is a name before a data or `equ` directive
 * (`msg db 'hi'`). Directives (`.text`, `.globl`, `section`, `global`, `db`, `%define`, `PROC`)
 * and section names (`.data`) are Keyword, as are operand size words (`dword`, `ptr`) and ARM
 * shifts (`lsl`). Registers (x86 general, segment, control, x87, MMX, SSE/AVX, mask; ARM `r`,
 * `x`, `w`, SIMD and system registers; RISC-V ABI names), with GAS's `%` prefix, are Constant;
 * numbers (`42`, `0x1F`, `0FFh`, `1010b`, `17q`, `0b101`, `1.5e3`) Number; `$` and `$$` (NASM's
 * addresses) Constant and GAS's `$` immediate prefix Punctuation; strings (`"…"`, `'…'`, and
 * NASM backquotes with escapes Constant) String; arithmetic Operator; `,`, brackets, and `:`
 * Punctuation. Other operand names (symbols, labels used) stay plain.
 *
 * States: 1 inside a block comment, else 0. Strings end with their line.
 */
internal object AsmLexer : HighlightLexer() {
    const val IN_COMMENT = 1

    // Word kinds.
    private const val DIRECTIVE = 1
    private const val DATA = 2
    private const val PREFIX = 3
    private const val SIZE = 4

    private val words =
        LexerWords(caseInsensitive = true).apply {
            register(
                DIRECTIVE, "section", "global", "globl", "extern", "bits", "default", "org", "align", "incbin", "endstruc", "istruc",
                "iend", "at", "assume", "public", "include", "includelib", "model", "cpu", "absolute", "common", "static", "endm",
                "end", "invoke", "option", "use16", "use32", "use64",
            )
            // Directives that name what comes before them: `msg db 1`, `main PROC`, `_TEXT SEGMENT`.
            register(
                DATA, "db", "dw", "dd", "dq", "dt", "do", "dy", "dz", "ddq", "resb", "resw", "resd", "resq", "rest", "reso", "resy",
                "resz", "equ", "times", "proc", "endp", "macro", "segment", "ends", "struc",
            )
            register(
                PREFIX, "lock", "rep", "repe", "repz", "repne", "repnz", "data16", "data32", "addr16", "addr32", "notrack", "bnd",
                "xacquire", "xrelease", "rex", "rex.w",
            )
            register(
                SIZE, "byte", "word", "dword", "qword", "tword", "oword", "xmmword", "ymmword", "zmmword", "ptr", "near", "far", "short",
                "offset", "rel", "abs", "wrt", "seg", "flat", "lsl", "lsr", "asr", "ror", "rrx", "uxtw", "sxtw", "uxtb", "sxtb", "uxth",
                "sxth", "uxtx", "sxtx", "msl",
            )
        }

    private val fixedRegisters =
        LexerWords(caseInsensitive = true).apply {
            register(
                1, "al", "ah", "ax", "eax", "rax", "bl", "bh", "bx", "ebx", "rbx", "cl", "ch", "cx", "ecx", "rcx", "dl", "dh", "dx",
                "edx", "rdx", "si", "sil", "esi", "rsi", "di", "dil", "edi", "rdi", "bp", "bpl", "ebp", "rbp", "sp", "spl", "esp", "rsp",
                "ip", "eip", "rip", "cs", "ds", "es", "fs", "gs", "ss", "st", "flags", "eflags", "rflags", "lr", "pc", "fp", "sb", "sl",
                "xzr", "wzr", "wsp", "cpsr", "spsr", "apsr", "fpscr", "fpsr", "fpcr", "nzcv", "zero", "ra", "gp", "tp",
            )
        }

    private val sections = listOf(".text", ".data", ".bss", ".rodata", ".rdata", ".code", ".const")

    private val preprocessor =
        listOf("include", "import", "define", "undef", "if", "ifdef", "ifndef", "elif", "else", "endif", "error", "warning", "pragma", "line")

    /** Numbered register families: a prefix and the highest number. */
    private val numbered =
        listOf(
            "r" to 15, "x" to 31, "w" to 31, "v" to 31, "q" to 31, "d" to 31, "s" to 31, "h" to 31, "b" to 31, "xmm" to 31,
            "ymm" to 31, "zmm" to 31, "mm" to 7, "k" to 7, "st" to 7, "cr" to 15, "dr" to 15, "t" to 6, "a" to 7,
        )

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int = AsmLine(text, start, end, out).run(state)

    /** True when `text[from, to)` names a register (any case). */
    fun isRegister(
        text: CharArray,
        from: Int,
        to: Int,
    ): Boolean {
        if (to - from < 2 || to - from > 7) return false
        if (fixedRegisters.lookup(text, from, to) != 0) return true
        var at = from
        while (at < to && isAsciiLetter(text[at])) at += 1
        val digitsStart = at
        while (at < to && isDigit(text[at])) at += 1
        if (digitsStart == from || at == digitsStart || at - digitsStart > 2) return false
        val number = if (at - digitsStart == 1) text[digitsStart] - '0' else (text[digitsStart] - '0') * 10 + (text[digitsStart + 1] - '0')
        if (at - digitsStart == 2 && text[digitsStart] == '0') return false
        val suffix = to - at
        if (suffix > 1) return false
        for ((prefix, max) in numbered) {
            if (!MarkupLexer.equalsIgnoreCase(text, from, digitsStart, prefix)) continue
            if (number > max) return false
            if (suffix == 0) return true
            // r8b, r8w, r8d, r8l.
            val last = MarkupLexer.lower(text[at])
            return prefix == "r" && number >= 8 && (last == 'b' || last == 'w' || last == 'd' || last == 'l')
        }
        return false
    }

    private class AsmLine(
        private val text: CharArray,
        start: Int,
        private val end: Int,
        private val out: SpanSink,
    ) {
        private var index = start

        // Per line only: the next word is a statement's first (label, mnemonic, or directive).
        private var head = true

        // Per line only: the line's first non-blank offset.
        private val lineHead = LexerScan.skipBlanks(text, start, end)

        fun run(state: Int): Int {
            if (state == IN_COMMENT) {
                val close = LexerScan.blockCommentEnd(text, index, end)
                out.span(index, if (close < 0) end else close, RoleCode.COMMENT)
                if (close < 0) return IN_COMMENT
                index = close
            }
            while (index < end && !out.stopped) {
                val char = text[index]
                val next = if (index + 1 < end) text[index + 1] else ' '
                when {
                    char == ' ' || char == '\t' || char == '\r' -> index += 1
                    char == ';' || (char == '/' && next == '/') -> comment()
                    char == '/' && next == '*' -> {
                        val close = LexerScan.blockCommentEnd(text, index + 2, end)
                        out.span(index, if (close < 0) end else close, RoleCode.COMMENT)
                        if (close < 0) return IN_COMMENT
                        index = close
                    }
                    char == '#' -> hash(next)
                    char == '@' && (next == ' ' || next == '\t' || index + 1 >= end) -> comment()
                    char == '"' || char == '\'' || char == '`' -> string(char)
                    head && char == '%' && isAsciiLetter(next) -> {
                        val wordEnd = wordEnd(index + 1)
                        out.span(index, wordEnd, RoleCode.KEYWORD)
                        index = wordEnd
                        head = false
                    }
                    char == '%' && isAsciiLetter(next) -> {
                        val wordEnd = wordEnd(index + 1)
                        if (isRegister(text, index + 1, wordEnd)) out.span(index, wordEnd, RoleCode.CONSTANT)
                        index = wordEnd
                    }
                    char == '%' && isDigit(next) -> {
                        var digitsEnd = index + 1
                        while (digitsEnd < end && isDigit(text[digitsEnd])) digitsEnd += 1
                        out.span(index, digitsEnd, RoleCode.VARIABLE)
                        index = digitsEnd
                    }
                    char == '$' -> dollar(next)
                    isDigit(char) -> number()
                    isWordStart(char) -> word()
                    else -> symbol(char, next)
                }
            }
            return 0
        }

        private fun comment() {
            out.span(index, end, RoleCode.COMMENT)
            index = end
        }

        private fun isWordStart(char: Char): Boolean = isAsciiLetter(char) || char == '_' || char == '.' || char == '?' || char == '@'

        private fun isWordPart(char: Char): Boolean = isWordStart(char) || isDigit(char) || char == '$'

        private fun wordEnd(from: Int): Int {
            var at = from
            while (at < end && isWordPart(text[at])) at += 1
            return at
        }

        /** A `#`: a C preprocessor directive or a comment opening a line, a comment before a blank, else an immediate. */
        private fun hash(next: Char) {
            if (index == lineHead) {
                val wordFrom = LexerScan.skipBlanks(text, index + 1, end)
                val wordTo = wordEnd(wordFrom)
                val directive = wordTo > wordFrom && preprocessor.any { LexerScan.equals(text, wordFrom, wordTo, it) }
                if (!directive) {
                    comment()
                    return
                }
                out.span(index, wordTo, RoleCode.KEYWORD)
                index = wordTo
                head = false
                if (LexerScan.equals(text, wordFrom, wordTo, "include") || LexerScan.equals(text, wordFrom, wordTo, "import")) {
                    val path = LexerScan.skipBlanks(text, index, end)
                    if (path < end && text[path] == '<') {
                        var close = path + 1
                        while (close < end && text[close] != '>') close += 1
                        val to = if (close < end) close + 1 else end
                        out.span(path, to, RoleCode.STRING)
                        index = to
                    }
                }
                return
            }
            if (next == ' ' || next == '\t' || index + 1 >= end) {
                comment()
                return
            }
            out.span(index, index + 1, RoleCode.PUNCTUATION)
            index += 1
        }

        private fun dollar(next: Char) {
            when {
                next == '$' -> {
                    out.span(index, index + 2, RoleCode.CONSTANT)
                    index += 2
                }
                isDigit(next) || next == '-' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                }
                isWordStart(next) -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index = wordEnd(index + 1)
                }
                else -> {
                    out.span(index, index + 1, RoleCode.CONSTANT)
                    index += 1
                }
            }
        }

        private fun word() {
            val from = index
            val to = wordEnd(from)
            index = to
            if (head) {
                headWord(from, to)
                return
            }
            when {
                isRegister(text, from, to) -> out.span(from, to, RoleCode.CONSTANT)
                text[from] == '.' && sections.any { MarkupLexer.equalsIgnoreCase(text, from, to, it) } -> out.span(from, to, RoleCode.KEYWORD)
                else -> {
                    val kind = words.lookup(text, from, to)
                    if (kind == SIZE || kind == DATA) {
                        out.span(from, to, RoleCode.KEYWORD)
                    } else {
                        // `v0.4s`, `v1.b`: a register before an arrangement.
                        var dot = from + 1
                        while (dot < to && text[dot] != '.') dot += 1
                        if (dot < to && isRegister(text, from, dot)) out.span(from, to, RoleCode.CONSTANT)
                    }
                }
            }
        }

        /** A statement's first word: a label, a directive, or a mnemonic. */
        private fun headWord(
            from: Int,
            to: Int,
        ) {
            if (to < end && text[to] == ':' && !(to + 1 < end && text[to + 1] == '=')) {
                label(from, to)
                return
            }
            head = false
            if (text[from] == '.') {
                out.span(from, to, RoleCode.KEYWORD)
                return
            }
            val kind = words.lookup(text, from, to)
            if (kind == DIRECTIVE || kind == DATA) {
                out.span(from, to, RoleCode.KEYWORD)
                return
            }
            // A name before a data directive or `equ` is a label without its colon.
            val nextFrom = LexerScan.skipBlanks(text, to, end)
            if (nextFrom > to && nextFrom < end && isWordStart(text[nextFrom])) {
                val nextTo = wordEnd(nextFrom)
                if (words.lookup(text, nextFrom, nextTo) == DATA) {
                    out.span(from, to, RoleCode.FUNCTION)
                    head = true
                    return
                }
            }
            out.span(from, to, RoleCode.KEYWORD)
            if (kind == PREFIX) head = true
        }

        /** `name:` or `name::` at [from]; the statement continues after it. */
        private fun label(
            from: Int,
            to: Int,
        ) {
            out.span(from, to, RoleCode.FUNCTION)
            val colonEnd = if (to + 1 < end && text[to + 1] == ':') to + 2 else to + 1
            out.span(to, colonEnd, RoleCode.PUNCTUATION)
            index = colonEnd
            head = true
        }

        private fun number() {
            val from = index
            if (head) {
                // `1:` is a local label.
                var digitsEnd = index
                while (digitsEnd < end && isDigit(text[digitsEnd])) digitsEnd += 1
                if (digitsEnd < end && text[digitsEnd] == ':') {
                    label(from, digitsEnd)
                    return
                }
            }
            var at = index
            val next = if (at + 1 < end) MarkupLexer.lower(text[at + 1]) else ' '
            if (text[at] == '0' && (next == 'x' || next == 'b' || next == 'o') && at + 2 < end && isHexDigit(text[at + 2])) {
                at += 2
                while (at < end && (isHexDigit(text[at]) || text[at] == '_')) at += 1
            } else {
                // Digits and letters together: `0FFh`, `1010b`, `17q`, `1f` (a local label reference).
                while (at < end && (isHexDigit(text[at]) || text[at] == '_')) at += 1
                if (at < end && (text[at] == 'h' || text[at] == 'H' || text[at] == 'q' || text[at] == 'Q' || text[at] == 'o' || text[at] == 'O')) {
                    at += 1
                } else if (at + 1 < end && text[at] == '.' && isDigit(text[at + 1])) {
                    at += 1
                    while (at < end && isDigit(text[at])) at += 1
                    if (at < end && (text[at] == 'e' || text[at] == 'E')) {
                        var exponent = at + 1
                        if (exponent < end && (text[exponent] == '+' || text[exponent] == '-')) exponent += 1
                        if (exponent < end && isDigit(text[exponent])) {
                            at = exponent
                            while (at < end && isDigit(text[at])) at += 1
                        }
                    }
                }
            }
            out.span(from, at, RoleCode.NUMBER)
            index = at
            head = false
        }

        /** A quoted string at [index]; backslash escapes in `"` and backquote strings are Constant. It ends with its line. */
        private fun string(quote: Char) {
            var segment = index
            var at = index + 1
            val escapes = quote != '\''
            while (at < end) {
                val char = text[at]
                if (char == quote) {
                    at += 1
                    break
                }
                if (char == '\\' && at + 1 < end) {
                    if (escapes && out.collecting) {
                        out.span(segment, at, RoleCode.STRING)
                        out.span(at, at + 2, RoleCode.CONSTANT)
                        segment = at + 2
                    }
                    at += 2
                    continue
                }
                at += 1
            }
            out.span(segment, minOf(at, end), RoleCode.STRING)
            index = minOf(at, end)
            head = false
        }

        private fun symbol(
            char: Char,
            next: Char,
        ) {
            when (char) {
                ',', '[', ']', '(', ')', '{', '}', ':' -> out.span(index, index + 1, RoleCode.PUNCTUATION)
                '<', '>' ->
                    if (next == char) {
                        out.span(index, index + 2, RoleCode.OPERATOR)
                        index += 1
                    } else {
                        out.span(index, index + 1, RoleCode.OPERATOR)
                    }
                '+', '-', '*', '/', '%', '&', '|', '^', '~', '!', '=' -> out.span(index, index + 1, RoleCode.OPERATOR)
                else -> {}
            }
            index += 1
        }
    }
}
