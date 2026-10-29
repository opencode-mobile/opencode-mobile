package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * YAML 1.2, with the YAML 1.1 booleans (`yes`, `no`, `on`, `off`) people still write.
 *
 * Roles: a mapping key (a plain or quoted scalar followed by `:`) is Property; other scalars
 * are String, or Number (ints, floats, hex, octal, `.inf`, `.nan`) and Constant (booleans,
 * `null`, `~`) when the whole plain scalar is one; escapes in quoted scalars are Constant.
 * Block scalars (`|`, `>`, with their indicators) have the header as Keyword and the body as
 * String. Anchors (`&a`) and aliases (`*a`) are Variable, tags (`!t`, `!!str`) Type; `---`,
 * `...`, and `%` directives are Keyword; `-`, `?`, `:`, `,`, and brackets are Punctuation;
 * comments are Comment.
 *
 * States: bits 0-1 hold the mode ([BLOCK] scalar, or an open double- or single-quoted scalar,
 * which may span lines), bits 2-5 the open flow collections (`[` `{`, up to 15). In a block
 * scalar, bits 6-13 hold the owner's indentation plus one (0 for the document root) and bits
 * 14-21 the content indentation plus one (0 until the first content line sets it), both capped
 * at 254. The body ends at the first non-blank line indented less than its content (or not
 * more than the owner before the content is known), or at a document marker.
 *
 * Known gaps: plain scalars continued on the next line color by that line alone; tabs never
 * count as indentation; a key is recognized by its `:` on the same line, so a multi-line
 * implicit key or a complex `? ` key's value colors as text.
 */
internal object YamlLexer : HighlightLexer() {
    private const val MODE_MASK = 3
    private const val BLOCK = 1
    private const val DOUBLE = 2
    private const val SINGLE = 3
    private const val FLOW_SHIFT = 2
    private const val FLOW_MASK = 15 shl FLOW_SHIFT
    private const val MAX_FLOW = 15
    private const val OWNER_SHIFT = 6
    private const val CONTENT_SHIFT = 14
    private const val INDENT_MASK = 0xFF
    private const val MAX_INDENT = 254

    private const val INFINITY = 1
    private const val NOT_A_NUMBER = 2

    private val constants = LexerWords()

    /** The words after `.` in `.inf` and `.nan`. */
    private val special = LexerWords()

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        var index = start
        var st = state
        val mode = st and MODE_MASK
        if (mode == BLOCK) {
            var indent = start
            while (indent < end && text[indent] == ' ') indent += 1
            val column = indent - start
            var blank = indent
            while (blank < end && (text[blank] == ' ' || text[blank] == '\t')) blank += 1
            if (blank >= end) return st
            val owner = ((st ushr OWNER_SHIFT) and INDENT_MASK) - 1
            val content = ((st ushr CONTENT_SHIFT) and INDENT_MASK) - 1
            val marker = column == 0 && isDocumentMarker(text, start, end)
            if (!marker && content >= 0 && column >= content) {
                out.span(start + content, end, RoleCode.STRING)
                return st
            }
            if (!marker && content < 0 && column > owner) {
                out.span(indent, end, RoleCode.STRING)
                return st or ((minOf(column, MAX_INDENT) + 1) shl CONTENT_SHIFT)
            }
            st = 0
        } else if (mode == DOUBLE || mode == SINGLE) {
            val close = quotedEnd(text, start, end, if (mode == DOUBLE) '"' else '\'')
            quotedSpans(text, start, if (close < 0) end else close, mode == DOUBLE, out)
            if (close < 0) return st
            st = st and MODE_MASK.inv()
            index = close
        }
        var flow = (st and FLOW_MASK) ushr FLOW_SHIFT
        var node = index == start
        var owner = -1
        var block = 0
        if (index == start && flow == 0) {
            if (isDocumentMarker(text, start, end)) {
                out.span(start, start + 3, RoleCode.KEYWORD)
                index = start + 3
            } else if (start < end && text[start] == '%') {
                val comment = commentStart(text, start, end)
                out.span(start, comment, RoleCode.KEYWORD)
                out.span(comment, end, RoleCode.COMMENT)
                return 0
            }
        }
        while (index < end && !out.stopped) {
            val char = text[index]
            val next = if (index + 1 < end) text[index + 1] else ' '
            val separated = next == ' ' || next == '\t' || index + 1 >= end
            when {
                char == ' ' || char == '\t' -> index += 1
                char == '#' -> {
                    out.span(index, end, RoleCode.COMMENT)
                    index = end
                }
                (char == '-' || char == '?') && separated && flow == 0 -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    if (char == '-') owner = index - start
                    index += 1
                    node = true
                }
                char == ':' && (separated || (flow > 0 && isFlowIndicator(next))) -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    node = true
                }
                char == '[' || char == '{' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    if (flow < MAX_FLOW) flow += 1
                    index += 1
                    node = true
                }
                char == ']' || char == '}' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    if (flow > 0) flow -= 1
                    index += 1
                    node = false
                }
                char == ',' && flow > 0 -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    node = true
                }
                char == '&' || char == '*' || char == '!' -> {
                    var after = index + 1
                    while (after < end && text[after] != ' ' && text[after] != '\t' && !(flow > 0 && isFlowIndicator(text[after]))) after += 1
                    out.span(index, after, if (char == '!') RoleCode.TYPE else RoleCode.VARIABLE)
                    index = after
                }
                (char == '|' || char == '>') && flow == 0 && node -> {
                    var after = index + 1
                    var explicit = 0
                    while (after < end && (text[after] == '-' || text[after] == '+' || isDigit(text[after]))) {
                        if (isDigit(text[after])) explicit = text[after] - '0'
                        after += 1
                    }
                    out.span(index, after, RoleCode.KEYWORD)
                    val ownerIndent = minOf(owner, MAX_INDENT)
                    block = BLOCK or ((ownerIndent + 1) shl OWNER_SHIFT)
                    if (explicit > 0) block = block or ((minOf(maxOf(ownerIndent, 0) + explicit, MAX_INDENT) + 1) shl CONTENT_SHIFT)
                    index = after
                    node = false
                }
                char == '"' || char == '\'' -> {
                    val close = quotedEnd(text, index + 1, end, char)
                    if (close < 0) {
                        quotedSpans(text, index, end, char == '"', out)
                        return (if (char == '"') DOUBLE else SINGLE) or (flow shl FLOW_SHIFT)
                    }
                    var after = LexerScan.skipBlanks(text, close, end)
                    if (after < end && text[after] == ':') {
                        out.span(index, close, RoleCode.PROPERTY)
                        owner = index - start
                    } else {
                        quotedSpans(text, index, close, char == '"', out)
                        after = close
                    }
                    index = after
                    node = false
                }
                else -> {
                    val result = plainScalar(text, index, end, flow)
                    val scalarEnd = result.toInt()
                    val key = (result ushr 32) != 0L
                    if (key) {
                        out.span(index, scalarEnd, RoleCode.PROPERTY)
                        owner = index - start
                    } else if (out.collecting) {
                        out.span(index, scalarEnd, scalarRole(text, index, scalarEnd))
                    } else {
                        out.span(index, scalarEnd, RoleCode.STRING)
                    }
                    index = maxOf(scalarEnd, index + 1)
                    node = false
                }
            }
        }
        if (out.stopped) return st
        if (block != 0 && flow == 0) return block
        return flow shl FLOW_SHIFT
    }

    /**
     * A plain scalar from [from]: its end (trailing blanks excluded) in the low half, and 1 in
     * the high half when a `:` after it makes it a key.
     */
    private fun plainScalar(
        text: CharArray,
        from: Int,
        end: Int,
        flow: Int,
    ): Long {
        var index = from
        var last = from
        var key = 0L
        while (index < end) {
            val char = text[index]
            if (char == ':') {
                val next = if (index + 1 < end) text[index + 1] else ' '
                if (next == ' ' || next == '\t' || index + 1 >= end || (flow > 0 && isFlowIndicator(next))) {
                    key = 1L
                    break
                }
            } else if (char == '#' && index > from && (text[index - 1] == ' ' || text[index - 1] == '\t')) {
                break
            } else if (flow > 0 && isFlowIndicator(char)) {
                break
            }
            if (char != ' ' && char != '\t') last = index + 1
            index += 1
        }
        if (last == from) last = index
        return (key shl 32) or last.toLong()
    }

    /** The offset after the quote closing a scalar whose text starts at [from], or -1 when it continues past the line. */
    private fun quotedEnd(
        text: CharArray,
        from: Int,
        end: Int,
        quote: Char,
    ): Int {
        var index = from
        while (index < end) {
            val char = text[index]
            if (quote == '"' && char == '\\') {
                index += 2
                continue
            }
            if (char == quote) {
                if (quote == '\'' && index + 1 < end && text[index + 1] == '\'') {
                    index += 2
                    continue
                }
                return index + 1
            }
            index += 1
        }
        return -1
    }

    /** Colors quoted text `[from, to)` as String with escapes (`\x`, or `''`) as Constant. */
    private fun quotedSpans(
        text: CharArray,
        from: Int,
        to: Int,
        double: Boolean,
        out: SpanSink,
    ) {
        if (!out.collecting) {
            out.span(from, to, RoleCode.STRING)
            return
        }
        var segment = from
        var index = from
        while (index < to) {
            val char = text[index]
            val escape =
                when {
                    double && char == '\\' -> minOf(escapeLength(text, index, to), to - index)
                    // `''` inside single quotes, but not an opening quote followed by a closing one.
                    !double && char == '\'' && index > from && index + 1 < to && text[index + 1] == '\'' -> 2
                    else -> 0
                }
            if (escape > 0) {
                out.span(segment, index, RoleCode.STRING)
                out.span(index, index + escape, RoleCode.CONSTANT)
                index += escape
                segment = index
            } else {
                index += 1
            }
        }
        out.span(segment, to, RoleCode.STRING)
    }

    private fun escapeLength(
        text: CharArray,
        backslash: Int,
        end: Int,
    ): Int {
        if (backslash + 1 >= end) return 1
        val digits =
            when (text[backslash + 1]) {
                'x' -> 2
                'u' -> 4
                'U' -> 8
                else -> return 2
            }
        var length = 2
        while (length < 2 + digits && backslash + length < end && isHexDigit(text[backslash + length])) length += 1
        return length
    }

    /** The role of the plain scalar `text[from, to)`: Number, Constant, or String. */
    private fun scalarRole(
        text: CharArray,
        from: Int,
        to: Int,
    ): Int {
        if (constants.lookup(text, from, to) != 0) return RoleCode.CONSTANT
        return if (isNumber(text, from, to)) RoleCode.NUMBER else RoleCode.STRING
    }

    private fun isNumber(
        text: CharArray,
        from: Int,
        to: Int,
    ): Boolean {
        var index = from
        if (index < to && (text[index] == '+' || text[index] == '-')) index += 1
        if (index >= to) return false
        if (text[index] == '.' && to - index == 4) {
            val word = index + 1
            if (special.lookup(text, word, to) == INFINITY) return true
            return index == from && special.lookup(text, word, to) == NOT_A_NUMBER
        }
        if (text[index] == '0' && index + 1 < to && (text[index + 1] == 'x' || text[index + 1] == 'o')) {
            val hex = text[index + 1] == 'x'
            index += 2
            if (index >= to) return false
            while (index < to) {
                val char = text[index]
                if (!(if (hex) isHexDigit(char) else char in '0'..'7') && char != '_') return false
                index += 1
            }
            return true
        }
        var digits = 0
        while (index < to && (isDigit(text[index]) || (digits > 0 && text[index] == '_'))) {
            digits += 1
            index += 1
        }
        if (index < to && text[index] == '.') {
            index += 1
            while (index < to && isDigit(text[index])) {
                digits += 1
                index += 1
            }
        }
        if (digits == 0) return false
        if (index < to && (text[index] == 'e' || text[index] == 'E')) {
            index += 1
            if (index < to && (text[index] == '+' || text[index] == '-')) index += 1
            if (index >= to) return false
            while (index < to && isDigit(text[index])) index += 1
        }
        return index == to
    }

    /** `---` or `...` at the start of a line, alone or followed by a blank. */
    private fun isDocumentMarker(
        text: CharArray,
        start: Int,
        end: Int,
    ): Boolean {
        if (end - start < 3) return false
        val char = text[start]
        if (char != '-' && char != '.') return false
        if (text[start + 1] != char || text[start + 2] != char) return false
        return end - start == 3 || text[start + 3] == ' ' || text[start + 3] == '\t'
    }

    /** The offset of a comment (`#` after a blank) on the line, or [end]. */
    private fun commentStart(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        for (index in from + 1 until end) {
            if (text[index] == '#' && (text[index - 1] == ' ' || text[index - 1] == '\t')) return index
        }
        return end
    }

    private fun isFlowIndicator(char: Char): Boolean = char == ',' || char == '[' || char == ']' || char == '{' || char == '}'

    init {
        constants.register(
            1,
            "~", "null", "Null", "NULL", "true", "True", "TRUE", "false", "False", "FALSE", "yes", "Yes", "YES", "no", "No", "NO",
            "on", "On", "ON", "off", "Off", "OFF",
        )
        special.register(INFINITY, "inf", "Inf", "INF")
        special.register(NOT_A_NUMBER, "nan", "NaN", "NAN")
    }
}
