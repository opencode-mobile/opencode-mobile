package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * Perl 5.
 *
 * Roles: `#` comments, POD blocks (`=pod`, `=head1`, ... through `=cut`), and the data after
 * `__END__` or `__DATA__` are Comment. Strings (`"..."`, `'...'`, backticks), quote-like
 * operators (`q`, `qq`, `qw`, `qx`, `qr`, `m`, `s`, `tr`, `y`, with any delimiter, paired ones
 * nesting), regexes (`/.../` where a value is expected, as after `=~`, `(`, or `split`), and
 * heredocs (`<<"EOF"`, `<<'EOF'`, `<<EOF`, `<<~EOF`; opener, body, and terminator) are String,
 * with escapes as Constant; interpolating ones color `$name`, `@name`, `${name}`, and `$1` as
 * Variable. Variables with sigils (`$x`, `@list`, `%hash`, `$#list`, `$_`, `@_`, `$Pkg::x`,
 * `*glob`) are Variable; `&name` calls, the name after `sub`, a name called with `(`, a method
 * after `->`, and built-ins (`print`, `push`, `join`, ...) are Function. Keywords (`my`, `sub`,
 * `if`, `foreach`, `use`, `return`, `and`, ...) are Keyword, and the module after `use`,
 * `package`, `no`, or `require` and `Pkg::Name` (or a capitalized name before `->`) are Type.
 * A bareword before `=>` or alone in `{...}` is String. `__PACKAGE__`, `undef`, and ALL_CAPS
 * handles (`STDERR`) are Constant. Numbers are Number; operators (`=~`, `->`, `eq`, `x`, `..`,
 * `//`, ...) are Operator; brackets, `,`, and `;` are Punctuation.
 *
 * A `/`, `%`, `&`, `*`, `<`, or `<<NAME` reads as a literal or sigil where a value is expected
 * (line start, after an operator, `(`, `,`, a keyword, or a built-in) and as an operator after
 * a value.
 *
 * States: bits 30-31 hold the mode. In code, bits 0-12 hold an open literal (0 for none): bits
 * 0-4 its closing delimiter (an index into [CLOSERS] plus one), bits 5-7 its operator, bits 8-9
 * which part of a two-part `s` or `tr` is open (or that the second part's opener is still to
 * come), bits 10-12 how many of its paired opening delimiter are open inside it (0-7).
 * Literals stay open across lines, as in Perl. In a heredoc body, bit 29 means the terminator
 * may be indented (`<<~`), bit 28 a raw (`'`) heredoc, and bits 0-27 a 28-bit
 * [LexerDelimiter] hash of the terminator. POD blocks and the `__END__` data have modes of
 * their own.
 *
 * Known gaps: only the first heredoc of a line is followed, and only when no literal stays open
 * at its line end; interpolation colors names, not subscripts or method calls (`$x->{a}`);
 * `s///e` replacements lex as text; a quote-like operator whose delimiter sits on the next
 * line, `format` blocks, and v-strings are not recognized.
 */
internal object PerlLexer : HighlightLexer() {
    private const val MODE_SHIFT = 30
    private const val HEREDOC = 1
    private const val POD = 2
    private const val DATA = 3
    private const val INDENTED = 1 shl 29
    private const val RAW_HEREDOC = 1 shl 28
    private const val HASH_BITS = 28

    private const val CLOSER_MASK = 31
    private const val OP_SHIFT = 5
    private const val PART_SHIFT = 8
    private const val NEST_SHIFT = 10
    private const val MAX_NEST = 7

    // Literal operators.
    private const val Q = 0
    private const val QQ = 1
    private const val QW = 2
    private const val REGEX = 3
    private const val SUBST = 4
    private const val TR = 5
    private const val QX = 6

    // Parts of a literal.
    private const val FIRST = 0
    private const val AWAIT = 1
    private const val SECOND = 2

    /** Closing delimiters a literal can have; a frame stores index + 1. */
    private const val CLOSERS = "\"'`/|!)]}>#$%&*+,-.:;=?@\\^_~"

    // Word kinds.
    private const val KEYWORD = 1
    private const val DECLARE = 2
    private const val SUB = 3
    private const val PACKAGE = 4
    private const val WORD_OPERATOR = 5
    private const val BUILTIN = 6
    private const val CONSTANT = 7
    private const val REPEAT = 8
    private const val QUOTE_Q = 9
    private const val QUOTE_QQ = 10
    private const val QUOTE_QW = 11
    private const val QUOTE_QX = 12
    private const val QUOTE_REGEX = 13
    private const val QUOTE_SUBST = 14
    private const val QUOTE_TR = 15

    private val words = LexerWords()

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        when (state ushr MODE_SHIFT) {
            DATA -> {
                out.span(start, end, RoleCode.COMMENT)
                return state
            }
            POD -> {
                out.span(start, end, RoleCode.COMMENT)
                return if (startsDirective(text, start, end, "=cut")) 0 else state
            }
            HEREDOC -> return heredocLine(text, start, end, state, out)
        }
        if (state == 0 && start < end) {
            if (text[start] == '=' && start + 1 < end && isAsciiLetter(text[start + 1])) {
                out.span(start, end, RoleCode.COMMENT)
                return if (startsDirective(text, start, end, "=cut")) 0 else POD shl MODE_SHIFT
            }
            if (startsDirective(text, start, end, "__END__") || startsDirective(text, start, end, "__DATA__")) {
                out.span(start, end, RoleCode.KEYWORD)
                return DATA shl MODE_SHIFT
            }
        }
        var frame = state
        var index = start
        var valueEnded = false
        var afterArrow = false
        var afterSub = false
        var afterPackage = false
        var afterBrace = false
        var subscriptable = false
        var heredoc = 0
        while (index < end && !out.stopped) {
            if (frame != 0) {
                val result = literal(text, index, end, frame, out)
                frame = (result ushr 32).toInt()
                index = result.toInt()
                if (frame == 0) valueEnded = true
                continue
            }
            val char = text[index]
            if (char == ' ' || char == '\t') {
                index += 1
                continue
            }
            val wasAfterArrow = afterArrow
            val wasAfterSub = afterSub
            val wasAfterPackage = afterPackage
            val wasAfterBrace = afterBrace
            val wasSubscriptable = subscriptable && index > start && text[index - 1] != ' ' && text[index - 1] != '\t'
            afterArrow = false
            afterSub = false
            afterPackage = false
            afterBrace = false
            subscriptable = false
            val next = if (index + 1 < end) text[index + 1] else '\u0000'
            when {
                char == '#' -> {
                    out.span(index, end, RoleCode.COMMENT)
                    index = end
                }
                char == '"' || char == '\'' || char == '`' -> {
                    out.span(index, index + 1, RoleCode.STRING)
                    val op = if (char == '"') QQ else if (char == '`') QX else Q
                    frame = frameOf(char, op, FIRST)
                    index += 1
                }
                char == '$' -> {
                    val after = scalarEnd(text, index, end)
                    if (after > index + 1) out.span(index, after, RoleCode.VARIABLE)
                    index = after
                    valueEnded = true
                    subscriptable = true
                }
                char == '@' && isArrayStart(text, index + 1, end) -> {
                    val after = sigilNameEnd(text, index, end)
                    out.span(index, after, RoleCode.VARIABLE)
                    index = after
                    valueEnded = true
                    subscriptable = true
                }
                (char == '%' || char == '*') && !valueEnded && isArrayStart(text, index + 1, end) -> {
                    val after = sigilNameEnd(text, index, end)
                    out.span(index, after, RoleCode.VARIABLE)
                    index = after
                    valueEnded = true
                    subscriptable = true
                }
                char == '&' && !valueEnded && (isNameStart(next) || next == '$' || next == '{' || next == ':') -> {
                    if (next == '$' || next == '{') {
                        out.span(index, index + 1, RoleCode.OPERATOR)
                        index += 1
                    } else {
                        val after = nameEnd(text, index + 1, end)
                        out.span(index, after, RoleCode.FUNCTION)
                        index = after
                        valueEnded = true
                    }
                }
                isDigit(char) || (char == '.' && isDigit(next) && !valueEnded) -> {
                    val after = numberEnd(text, index, end)
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                    valueEnded = true
                }
                isNameStart(char) -> {
                    val minus = index > start && text[index - 1] == '-'
                    val result =
                        word(text, index, end, valueEnded, minus, wasAfterArrow, wasAfterSub, wasAfterPackage, wasAfterBrace, out)
                    index = result.toInt()
                    val kind = (result ushr 32).toInt()
                    if (kind >= QUOTE_Q) {
                        // A quote-like operator: the kind carries its literal frame.
                        frame = kind - QUOTE_Q
                        valueEnded = false
                    } else {
                        afterSub = kind == SUB
                        afterPackage = kind == PACKAGE
                        valueEnded = kind == 0 || kind == CONSTANT
                    }
                }
                char == '/' && !valueEnded -> {
                    out.span(index, index + 1, RoleCode.STRING)
                    frame = frameOf('/', REGEX, FIRST)
                    index += 1
                }
                char == '<' && next == '<' && heredocAhead(text, index + 2, end, valueEnded) -> {
                    val result = heredocStart(text, index, end, out)
                    index = result.toInt()
                    if (heredoc == 0) heredoc = (result ushr 32).toInt()
                    valueEnded = true
                }
                char == '<' && !valueEnded && readlineEnd(text, index, end) > 0 -> {
                    val after = readlineEnd(text, index, end)
                    out.span(index, after, RoleCode.VARIABLE)
                    index = after
                    valueEnded = true
                }
                char == '-' && next == '>' -> {
                    out.span(index, index + 2, RoleCode.OPERATOR)
                    index += 2
                    afterArrow = true
                    subscriptable = true
                    valueEnded = false
                }
                char == '(' || char == '[' || char == '{' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    // A bareword alone in a hash subscript (`$h{key}`, `$h->{key}`) is a string.
                    afterBrace = char == '{' && (wasSubscriptable || wasAfterArrow)
                    valueEnded = false
                }
                char == ')' || char == ']' || char == '}' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    subscriptable = char != ')'
                    valueEnded = true
                }
                char == ',' || char == ';' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    valueEnded = false
                }
                char == ':' && next == ':' -> {
                    out.span(index, index + 2, RoleCode.PUNCTUATION)
                    index += 2
                }
                isOperator(char) -> {
                    var after = index + 1
                    while (after < end && after < index + 3 && isOperator(text[after]) && !startsOperand(text, after, end)) after += 1
                    out.span(index, after, RoleCode.OPERATOR)
                    // `$i++ / 2` divides; any other operator expects a value.
                    val last = text[after - 1]
                    valueEnded = valueEnded && after - index == 2 && (last == '+' || last == '-') && text[index] == last
                    index = after
                }
                else -> index += 1
            }
        }
        if (out.stopped) return frame
        if (heredoc != 0 && frame == 0) return heredoc
        return frame
    }

    /**
     * A bareword at [from]. Returns its kind above the offset after it; a quote-like operator
     * returns [QUOTE_Q] plus its literal frame instead, having consumed its opening delimiter.
     */
    private fun word(
        text: CharArray,
        from: Int,
        end: Int,
        valueEnded: Boolean,
        minus: Boolean,
        afterArrow: Boolean,
        afterSub: Boolean,
        afterPackage: Boolean,
        afterBrace: Boolean,
        out: SpanSink,
    ): Long {
        val after = nameEnd(text, from, end)
        val following = LexerScan.skipBlanks(text, after, end)
        val fatComma = following + 1 < end && text[following] == '=' && text[following + 1] == '>'
        if (fatComma || (afterBrace && following < end && text[following] == '}')) {
            out.span(from, after, RoleCode.STRING)
            return packed(CONSTANT, after)
        }
        if (afterArrow) {
            out.span(from, after, RoleCode.FUNCTION)
            return packed(0, after)
        }
        if (afterSub) {
            out.span(from, after, RoleCode.FUNCTION)
            return packed(0, after)
        }
        val kind = words.lookup(text, from, after)
        if (kind >= QUOTE_Q && !minus) {
            val delimiter = quoteDelimiter(text, after, end)
            if (delimiter >= 0) {
                val op =
                    when (kind) {
                        QUOTE_Q -> Q
                        QUOTE_QQ -> QQ
                        QUOTE_QW -> QW
                        QUOTE_QX -> QX
                        QUOTE_REGEX -> REGEX
                        QUOTE_SUBST -> SUBST
                        else -> TR
                    }
                out.span(from, delimiter + 1, RoleCode.STRING)
                return packed(QUOTE_Q + frameOf(text[delimiter], op, FIRST), delimiter + 1)
            }
        }
        if (afterPackage) {
            out.span(from, after, RoleCode.TYPE)
            return packed(0, after)
        }
        when (kind) {
            KEYWORD, DECLARE -> {
                out.span(from, after, RoleCode.KEYWORD)
                return packed(KEYWORD, after)
            }
            SUB, PACKAGE -> {
                out.span(from, after, RoleCode.KEYWORD)
                return packed(kind, after)
            }
            WORD_OPERATOR -> {
                out.span(from, after, RoleCode.OPERATOR)
                return packed(KEYWORD, after)
            }
            REPEAT -> if (valueEnded) {
                out.span(from, after, RoleCode.OPERATOR)
                return packed(KEYWORD, after)
            }
            BUILTIN -> {
                out.span(from, after, RoleCode.FUNCTION)
                return packed(BUILTIN, after)
            }
            CONSTANT -> {
                out.span(from, after, RoleCode.CONSTANT)
                return packed(CONSTANT, after)
            }
        }
        val role =
            when {
                following < end && text[following] == '(' -> RoleCode.FUNCTION
                hasPackageSeparator(text, from, after) -> RoleCode.TYPE
                text[from] in 'A'..'Z' && following + 1 < end && text[following] == '-' && text[following + 1] == '>' -> RoleCode.TYPE
                isAllCaps(text, from, after) -> RoleCode.CONSTANT
                else -> 0
            }
        if (role != 0) out.span(from, after, role)
        return packed(0, after)
    }

    /** The offset of a quote-like operator's opening delimiter after its name (ending at [at]), or -1. */
    private fun quoteDelimiter(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        val index = LexerScan.skipBlanks(text, at, end)
        if (index >= end) return -1
        val char = text[index]
        val spaced = index > at
        if (char == '=' && (spaced || (index + 1 < end && text[index + 1] == '>'))) return -1
        if (spaced && char == '#') return -1
        return when (char) {
            ',', ';', ')', ']', '}', '>' -> -1
            '(', '[', '{', '<' -> index
            else -> if (CLOSERS.indexOf(char) >= 0) index else -1
        }
    }

    /**
     * Lexes inside the open literal [frame] from [from]; returns the new frame (0 once it
     * closes) above the offset where it stopped (after its close, or the line end).
     */
    private fun literal(
        text: CharArray,
        from: Int,
        end: Int,
        frame: Int,
        out: SpanSink,
    ): Long {
        val op = (frame ushr OP_SHIFT) and 7
        var part = (frame ushr PART_SHIFT) and 3
        var closer = CLOSERS[(frame and CLOSER_MASK) - 1]
        if (part == AWAIT) {
            // `s{...}` closed its first part; its second has its own delimiters, maybe after blanks or lines.
            val index = LexerScan.skipBlanks(text, from, end)
            if (index >= end) return packed(frame, end)
            val char = text[index]
            if (char == '#') {
                out.span(index, end, RoleCode.COMMENT)
                return packed(frame, end)
            }
            if (CLOSERS.indexOf(closerOf(char)) < 0) return packed(0, index)
            out.span(index, index + 1, RoleCode.STRING)
            return packed(frameOf(char, op, SECOND), index + 1)
        }
        var opener = openerOf(closer)
        var nest = (frame ushr NEST_SHIFT) and MAX_NEST
        var segment = from
        var index = from
        while (index < end) {
            val char = text[index]
            val interpolates = closer != '\'' && (op == QQ || op == QX || op == REGEX || op == SUBST)
            when {
                char == '\\' -> {
                    val length = if (index + 1 < end) 2 else 1
                    if (interpolates || op == TR || (length == 2 && (text[index + 1] == '\\' || text[index + 1] == closer || text[index + 1] == opener))) {
                        out.span(segment, index, RoleCode.STRING)
                        out.span(index, index + length, RoleCode.CONSTANT)
                        segment = index + length
                    }
                    index += length
                }
                opener != '\u0000' && char == opener -> {
                    if (nest < MAX_NEST) nest += 1
                    index += 1
                }
                char == closer && nest > 0 -> {
                    nest -= 1
                    index += 1
                }
                char == closer -> {
                    if ((op == SUBST || op == TR) && part == FIRST) {
                        out.span(segment, index + 1, RoleCode.STRING)
                        if (opener != '\u0000') return packed(frameOf(closer, op, AWAIT), index + 1)
                        // `s/a/b/`: the second part starts right away.
                        part = SECOND
                        index += 1
                        segment = index
                        continue
                    }
                    var after = index + 1
                    if (op == REGEX || op == SUBST || op == TR) {
                        while (after < end && isAsciiLetter(text[after])) after += 1
                    }
                    out.span(segment, after, RoleCode.STRING)
                    return packed(0, after)
                }
                interpolates && (char == '$' || char == '@') -> {
                    val after = interpolationEnd(text, index, end, closer, op)
                    if (after > index + 1) {
                        out.span(segment, index, RoleCode.STRING)
                        out.span(index, after, RoleCode.VARIABLE)
                        segment = after
                        index = after
                    } else {
                        index += 1
                    }
                }
                else -> index += 1
            }
        }
        out.span(segment, end, RoleCode.STRING)
        return packed(frameOf(closer, op, part) or (nest shl NEST_SHIFT), end)
    }

    /** The offset after an interpolated variable at [at] (`$` or `@`), or [at] + 1 when none starts there. */
    private fun interpolationEnd(
        text: CharArray,
        at: Int,
        end: Int,
        closer: Char,
        op: Int,
    ): Int {
        if (at + 1 >= end) return at + 1
        val next = text[at + 1]
        if (next == closer) return at + 1
        if (next == '{') {
            var index = at + 2
            if (index < end && text[index] == '^') index += 1
            while (index < end && isNamePart(text[index])) index += 1
            return if (index > at + 2 && index < end && text[index] == '}') index + 1 else at + 1
        }
        if (isNameStart(next) || (next == ':' && at + 2 < end && text[at + 2] == ':')) return nameEnd(text, at + 1, end)
        if (text[at] == '$') {
            if (isDigit(next)) {
                var index = at + 2
                while (index < end && isDigit(text[index])) index += 1
                return index
            }
            // `$&` and friends, but not a regex's `$)` or `$|` end anchor.
            if (op != REGEX && (next == '&' || next == '!' || next == '@' || next == '/' || next == '0')) return at + 2
            if (next == '$' && at + 2 < end && isNameStart(text[at + 2])) return nameEnd(text, at + 2, end)
        }
        return at + 1
    }

    /** The offset after a scalar at the `$` at [at] (`$x`, `$Pkg::x`, `${x}`, `$#x`, `$$`, `$1`, `$_`, `$!`), at least [at] + 1. */
    private fun scalarEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        if (at + 1 >= end) return at + 1
        val next = text[at + 1]
        return when {
            isNameStart(next) || next == ':' -> nameEnd(text, at + 1, end)
            next == '{' -> {
                var index = at + 2
                if (index < end && text[index] == '^') index += 1
                while (index < end && isNamePart(text[index])) index += 1
                if (index > at + 2 && index < end && text[index] == '}') index + 1 else at + 2
            }
            next == '#' -> {
                val after = at + 2
                when {
                    after < end && (isNameStart(text[after]) || text[after] == ':') -> nameEnd(text, after, end)
                    after < end && (text[after] == '$' || text[after] == '{') -> after + 1
                    else -> after
                }
            }
            next == '$' -> if (at + 2 < end && (isNameStart(text[at + 2]) || text[at + 2] == ':')) nameEnd(text, at + 2, end) else at + 2
            isDigit(next) -> {
                var index = at + 2
                while (index < end && isDigit(text[index])) index += 1
                index
            }
            next == '^' && at + 2 < end && text[at + 2] in 'A'..'Z' -> at + 3
            next == ' ' || next == '\t' -> at + 1
            else -> at + 2
        }
    }

    /** True when a sigil before [at] starts a variable: a name, `$`, `{`, `::`, `_`, or `@-`/`@+`. */
    private fun isArrayStart(
        text: CharArray,
        at: Int,
        end: Int,
    ): Boolean {
        if (at >= end) return false
        val char = text[at]
        return isNameStart(char) || char == '$' || char == '{' || (char == ':' && at + 1 < end && text[at + 1] == ':')
    }

    /** The offset after a `@`, `%`, or `*` variable at [at]: its name, or the `$` or `{` of a dereference. */
    private fun sigilNameEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        val next = text[at + 1]
        if (next == '{') return at + 2
        if (next == '$') return if (at + 2 < end && isNameStart(text[at + 2])) nameEnd(text, at + 2, end) else at + 2
        return nameEnd(text, at + 1, end)
    }

    /** A name with `::` package separators from [from]. */
    private fun nameEnd(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        var index = from
        while (index < end) {
            if (isNamePart(text[index])) {
                index += 1
            } else if (text[index] == ':' && index + 1 < end && text[index + 1] == ':') {
                index += 2
            } else {
                break
            }
        }
        return index
    }

    /** `<STDIN>`, `<$fh>`, `<>`: the offset after `>`, or 0 when [at] does not start one. */
    private fun readlineEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at + 1
        val dollar = index < end && text[index] == '$'
        if (dollar) index += 1
        val name = index
        while (index < end && isNamePart(text[index])) index += 1
        return if (index < end && text[index] == '>' && (!dollar || index > name)) index + 1 else 0
    }

    /** True when `<<` before [at] starts a heredoc: a quote, `~`, or (where a value is expected) a name follows. */
    private fun heredocAhead(
        text: CharArray,
        at: Int,
        end: Int,
        valueEnded: Boolean,
    ): Boolean {
        if (at >= end) return false
        val char = text[at]
        if (char == '"' || char == '\'' || char == '`') return true
        if (char == '~') return at + 1 < end && (text[at + 1] == '"' || text[at + 1] == '\'' || text[at + 1] == '`' || isNameStart(text[at + 1]))
        return isNameStart(char) && !valueEnded
    }

    /** Lexes `<<~"ID"`, `<<'ID'`, or `<<ID` as String; returns the heredoc state above the offset after it. */
    private fun heredocStart(
        text: CharArray,
        at: Int,
        end: Int,
        out: SpanSink,
    ): Long {
        var index = at + 2
        var indented = false
        if (text[index] == '~') {
            indented = true
            index += 1
        }
        var raw = false
        val idStart: Int
        val idEnd: Int
        val char = text[index]
        if (char == '"' || char == '\'' || char == '`') {
            raw = char == '\''
            idStart = index + 1
            var close = idStart
            while (close < end && text[close] != char) close += 1
            idEnd = close
            index = minOf(close + 1, end)
        } else {
            idStart = index
            var after = index
            while (after < end && isNamePart(text[after])) after += 1
            idEnd = after
            index = after
        }
        out.span(at, index, RoleCode.STRING)
        var bits = (HEREDOC shl MODE_SHIFT) or LexerDelimiter.of(text, idStart, idEnd, HASH_BITS)
        if (indented) bits = bits or INDENTED
        if (raw) bits = bits or RAW_HEREDOC
        return packed(bits, index)
    }

    /** A heredoc body line, or its terminator (String, which returns to code). */
    private fun heredocLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        val from = if (state and INDENTED != 0) LexerScan.skipBlanks(text, start, end) else start
        if (LexerDelimiter.of(text, from, end, HASH_BITS) == state and ((1 shl HASH_BITS) - 1)) {
            out.span(from, end, RoleCode.STRING)
            return 0
        }
        if (state and RAW_HEREDOC != 0) {
            out.span(start, end, RoleCode.STRING)
            return state
        }
        var segment = start
        var index = start
        while (index < end && !out.stopped) {
            val char = text[index]
            if (char == '\\') {
                val length = if (index + 1 < end) 2 else 1
                out.span(segment, index, RoleCode.STRING)
                out.span(index, index + length, RoleCode.CONSTANT)
                index += length
                segment = index
            } else if (char == '$' || char == '@') {
                val after = interpolationEnd(text, index, end, '\u0000', QQ)
                if (after > index + 1) {
                    out.span(segment, index, RoleCode.STRING)
                    out.span(index, after, RoleCode.VARIABLE)
                    segment = after
                }
                index = maxOf(after, index + 1)
            } else {
                index += 1
            }
        }
        out.span(segment, end, RoleCode.STRING)
        return state
    }

    private fun numberEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at
        if (text[at] == '0' && at + 1 < end && text[at + 1] in "xXbBoO") {
            index += 2
            while (index < end && (isHexDigit(text[index]) || text[index] == '_')) index += 1
            return index
        }
        while (index < end && (isDigit(text[index]) || text[index] == '_')) index += 1
        if (index + 1 < end && text[index] == '.' && isDigit(text[index + 1])) {
            index += 1
            while (index < end && (isDigit(text[index]) || text[index] == '_')) index += 1
        }
        if (index < end && (text[index] == 'e' || text[index] == 'E')) {
            var exponent = index + 1
            if (exponent < end && (text[exponent] == '+' || text[exponent] == '-')) exponent += 1
            if (exponent < end && isDigit(text[exponent])) {
                index = exponent
                while (index < end && isDigit(text[index])) index += 1
            }
        }
        return index
    }

    /** `=pod` / `=cut` / `__END__` at the line start, alone or followed by a blank. */
    private fun startsDirective(
        text: CharArray,
        start: Int,
        end: Int,
        word: String,
    ): Boolean {
        if (!LexerScan.startsWith(text, start, end, word)) return false
        val after = start + word.length
        return after == end || text[after] == ' ' || text[after] == '\t'
    }

    /** True at a `$`, `@`, or `%` sigil glued after an operator (`=$x`, `!@list`), which ends the operator. */
    private fun startsOperand(
        text: CharArray,
        at: Int,
        end: Int,
    ): Boolean {
        val char = text[at]
        return (char == '%' || char == '&' || char == '*' || char == '/') && at + 1 < end && (isNameStart(text[at + 1]) || text[at + 1] == '$' || text[at + 1] == '{')
    }

    private fun frameOf(
        open: Char,
        op: Int,
        part: Int,
    ): Int = (CLOSERS.indexOf(closerOf(open)) + 1) or (op shl OP_SHIFT) or (part shl PART_SHIFT)

    private fun packed(
        high: Int,
        offset: Int,
    ): Long = (high.toLong() shl 32) or (offset.toLong() and 0xFFFFFFFFL)

    private fun closerOf(open: Char): Char =
        when (open) {
            '(' -> ')'
            '[' -> ']'
            '{' -> '}'
            '<' -> '>'
            else -> open
        }

    private fun openerOf(close: Char): Char =
        when (close) {
            ')' -> '('
            ']' -> '['
            '}' -> '{'
            '>' -> '<'
            else -> '\u0000'
        }

    private fun hasPackageSeparator(
        text: CharArray,
        from: Int,
        to: Int,
    ): Boolean {
        for (index in from until to - 1) if (text[index] == ':' && text[index + 1] == ':') return true
        return false
    }

    private fun isAllCaps(
        text: CharArray,
        from: Int,
        to: Int,
    ): Boolean {
        if (to - from < 2) return false
        for (index in from until to) if (text[index] in 'a'..'z') return false
        return true
    }

    private fun isOperator(char: Char): Boolean =
        when (char) {
            '+', '-', '*', '/', '%', '=', '<', '>', '!', '&', '|', '^', '~', '?', ':', '.', '\\' -> true
            else -> false
        }

    private fun isNameStart(char: Char): Boolean = isAsciiLetter(char) || char == '_'

    private fun isNamePart(char: Char): Boolean = isAsciiLetter(char) || isDigit(char) || char == '_'

    init {
        words.register(
            KEYWORD,
            "if", "elsif", "else", "unless", "while", "until", "for", "foreach", "do", "last", "next", "redo", "goto", "return",
            "and", "or", "not", "xor", "given", "when", "default", "continue", "eval", "BEGIN", "END", "INIT", "CHECK", "UNITCHECK",
        )
        words.register(DECLARE, "my", "our", "local", "state")
        words.register(SUB, "sub")
        words.register(PACKAGE, "package", "use", "no", "require")
        words.register(WORD_OPERATOR, "lt", "gt", "le", "ge", "eq", "ne", "cmp", "isa")
        words.register(REPEAT, "x")
        words.register(
            BUILTIN,
            "print", "printf", "say", "push", "pop", "shift", "unshift", "splice", "join", "split", "map", "grep", "sort", "reverse",
            "keys", "values", "each", "exists", "delete", "defined", "ref", "bless", "open", "close", "binmode", "opendir",
            "readdir", "closedir", "eof", "length", "substr", "index", "rindex", "sprintf", "lc", "uc", "lcfirst", "ucfirst",
            "chomp", "chop", "chr", "ord", "die", "warn", "exit", "scalar", "wantarray", "time", "localtime", "gmtime", "sleep",
            "system", "exec", "abs", "int", "sqrt", "rand", "srand", "hex", "oct", "pack", "unpack", "read", "seek", "tell",
            "mkdir", "rmdir", "unlink", "rename", "chdir", "wait", "waitpid", "kill", "fork", "pos", "quotemeta", "caller", "select",
            "lock", "chmod", "chown", "utime", "glob", "stat", "lstat", "exp", "log", "sin", "cos", "atan2",
        )
        words.register(CONSTANT, "__PACKAGE__", "__FILE__", "__LINE__", "__SUB__", "undef")
        words.register(QUOTE_Q, "q")
        words.register(QUOTE_QQ, "qq")
        words.register(QUOTE_QW, "qw")
        words.register(QUOTE_QX, "qx")
        words.register(QUOTE_REGEX, "m", "qr")
        words.register(QUOTE_SUBST, "s")
        words.register(QUOTE_TR, "tr", "y")
    }
}
