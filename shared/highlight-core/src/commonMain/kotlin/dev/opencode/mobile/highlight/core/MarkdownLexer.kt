package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit

/**
 * Markdown (CommonMark with the GitHub additions), one line at a time.
 *
 * Roles: a heading's `#` marks are Punctuation and its text Keyword (a setext underline is
 * Punctuation); list markers, task boxes, block quote `>`, thematic breaks, table pipes and
 * delimiter rows, emphasis and strikethrough delimiters, code span backticks, and link brackets
 * are Punctuation; code span text and link text String; a reference label Constant; a link
 * title String; backslash escapes and entities Constant. Inline HTML and HTML blocks are
 * [HtmlLexer]'s. A fenced code block (``` or ~~~, any length) whose info string names a
 * registered language is that language's lexer; an unknown one, and indented code, stay plain.
 * Front matter (`---` or `+++` on the first line) is YAML or TOML when those are registered.
 * Emphasis text, link addresses, and paragraph text stay plain.
 *
 * Inline syntax is matched within a line, with bounded work: a search for a closing backtick
 * run, `]`, `)`, or `>` that fails is not repeated for later openers that cannot do better, and
 * one emphasis of each delimiter is open at a time, so nested emphasis of the same delimiter is
 * not recognized.
 *
 * States: the block (normal text, a fence with its character, length up to 255, indent, and
 * language, front matter, or an HTML block) plus whether the line above was paragraph text
 * (which rules out indented code and allows a setext underline) and whether a list is open
 * (where indented lines are list content). Inside a fence, front matter, or HTML block the
 * embedded lexer's state joins it through [SpanSink.embedded]. A fence nested past
 * [SpanSink.MAX_EMBEDDING_DEPTH] embedding levels stays plain.
 */
internal object MarkdownLexer : HighlightLexer() {
    const val NORMAL = 0
    const val FENCE = 1
    const val FRONT_MATTER = 2
    const val HTML_BLOCK = 3
    const val BLOCK_MASK = 7
    const val PARAGRAPH = 1 shl 3
    const val LIST = 1 shl 4
    const val TILDE = 1 shl 5
    const val LENGTH_SHIFT = 6
    const val MAX_LENGTH = 255
    const val INDENT_SHIFT = 14
    const val LIST_FENCE = 1 shl 16
    const val LANGUAGE_SHIFT = 17
    const val MAX_LANGUAGE = 255

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int = MarkdownLine(text, start, end, out).run(state)
}

/** One line of Markdown; a new one per line keeps [MarkdownLexer] stateless. */
private class MarkdownLine(
    private val text: CharArray,
    private val lineStart: Int,
    private val end: Int,
    private val out: SpanSink,
) {
    private var host = 0
    private var inner = 0

    // Inline scanning: one open emphasis per delimiter (its closing run and length), and where
    // searches for closing characters ended, so none is repeated.
    private var starCloseAt = -1
    private var starCloseLength = 0
    private var underscoreCloseAt = -1
    private var underscoreCloseLength = 0
    private var tildeCloseAt = -1
    private var starFailedLength = Int.MAX_VALUE
    private var underscoreFailedLength = Int.MAX_VALUE
    private var tildeFailed = false
    private var commentFailed = false
    private var backtickFailed = 0
    private var bracketFrom = -1
    private var bracketAt = -1
    private var parenFrom = -1
    private var parenAt = -1
    private var angleFrom = -1
    private var angleAt = -1

    fun run(state: Int): Int {
        host = out.embedded.host(state)
        inner = out.embedded.inner(state)
        when (host and MarkdownLexer.BLOCK_MASK) {
            MarkdownLexer.FENCE -> fenceLine()
            MarkdownLexer.FRONT_MATTER -> frontMatterLine()
            MarkdownLexer.HTML_BLOCK -> htmlBlockLine()
            else -> normalLine(state)
        }
        return out.embedded.join(host, inner)
    }

    private fun flag(bit: Int): Boolean = host and bit != 0

    private fun setFlag(
        bit: Int,
        on: Boolean,
    ) {
        host = if (on) host or bit else host and bit.inv()
    }

    private fun isBlank(char: Char): Boolean = char == ' ' || char == '\t' || char == '\r'

    private fun skipBlanks(from: Int): Int {
        var at = from
        while (at < end && isBlank(text[at])) at += 1
        return at
    }

    /** Leading indentation width from [from] (a tab counts 4) up to [to]. */
    private fun indentWidth(
        from: Int,
        to: Int,
    ): Int {
        var width = 0
        for (at in from until to) width += if (text[at] == '\t') 4 else 1
        return width
    }

    private fun runEnd(
        from: Int,
        char: Char,
    ): Int {
        var at = from
        while (at < end && text[at] == char) at += 1
        return at
    }

    // region Blocks

    private fun languageLexer(): HighlightLexer? {
        val position = (host ushr MarkdownLexer.LANGUAGE_SHIFT) and MarkdownLexer.MAX_LANGUAGE
        return if (position == 0) null else HighlightLanguages.lexerAt(position - 1)
    }

    private fun embed(
        lexer: HighlightLexer,
        from: Int,
    ) {
        out.embeddingDepth += 1
        inner = lexer.lexLine(text, from, end, inner, out)
        out.embeddingDepth -= 1
    }

    private fun fenceLine() {
        val first = skipBlanks(lineStart)
        val fenceChar = if (flag(MarkdownLexer.TILDE)) '~' else '`'
        val length = (host ushr MarkdownLexer.LENGTH_SHIFT) and MarkdownLexer.MAX_LENGTH
        if (first < end && text[first] == fenceChar && (indentWidth(lineStart, first) <= 3 || flag(MarkdownLexer.LIST_FENCE))) {
            val runEnd = runEnd(first, fenceChar)
            if (runEnd - first >= length && skipBlanks(runEnd) == end) {
                out.span(first, runEnd, RoleCode.PUNCTUATION)
                host = host and MarkdownLexer.LIST
                inner = 0
                return
            }
        }
        val lexer = languageLexer() ?: return
        embed(lexer, lineStart)
    }

    private fun frontMatterLine() {
        val toml = flag(MarkdownLexer.TILDE)
        val marker = runEnd(lineStart, if (toml) '+' else '-') - lineStart == 3
        val dots = !toml && LexerScan.startsWith(text, lineStart, end, "...")
        if ((marker || dots) && skipBlanks(lineStart + 3) == end) {
            out.span(lineStart, lineStart + 3, RoleCode.PUNCTUATION)
            host = MarkdownLexer.NORMAL
            inner = 0
            return
        }
        val lexer = languageLexer() ?: return
        embed(lexer, lineStart)
    }

    private fun htmlBlockLine() {
        if (inner == 0 && skipBlanks(lineStart) == end) {
            host = host and MarkdownLexer.LIST
            return
        }
        embed(HtmlLexer, lineStart)
    }

    private fun normalLine(state: Int) {
        val first = skipBlanks(lineStart)
        if (first == end) {
            setFlag(MarkdownLexer.PARAGRAPH, false)
            return
        }
        val indent = indentWidth(lineStart, first)
        val paragraph = flag(MarkdownLexer.PARAGRAPH)
        val list = flag(MarkdownLexer.LIST)
        if (indent >= 4 && !paragraph && !list) return
        if (lineStart == 0 && state == 0 && first == 0 && frontMatterOpen()) return
        var at = first
        var quoted = false
        var listItem = false
        // Block quote markers and list markers may stack; then the rest is one block.
        while (at < end && !out.stopped) {
            val char = text[at]
            if (char == '>') {
                out.span(at, at + 1, RoleCode.PUNCTUATION)
                quoted = true
                at = skipBlanks(at + 1)
                continue
            }
            val markerEnd = listMarkerEnd(at)
            if (markerEnd > 0 && !thematicBreak(at)) {
                out.span(at, markerEnd, RoleCode.PUNCTUATION)
                listItem = true
                at = skipBlanks(markerEnd)
                val box = taskBoxEnd(at)
                if (box > 0) {
                    out.span(at, box, RoleCode.PUNCTUATION)
                    at = skipBlanks(box)
                }
                continue
            }
            break
        }
        if (listItem) setFlag(MarkdownLexer.LIST, true)
        if (!listItem && !quoted && indent == 0 && !paragraph) setFlag(MarkdownLexer.LIST, false)
        if (at >= end) {
            setFlag(MarkdownLexer.PARAGRAPH, false)
            return
        }
        val blockIndent = indentWidth(lineStart, first)
        val char = text[at]
        when {
            char == '#' && heading(at) -> setFlag(MarkdownLexer.PARAGRAPH, false)
            (char == '`' || char == '~') && !quoted && (blockIndent <= 3 || flag(MarkdownLexer.LIST)) && fenceOpen(at, blockIndent) -> Unit
            (char == '*' || char == '-' || char == '_') && thematicBreak(at) -> {
                out.span(at, lastSolid() + 1, RoleCode.PUNCTUATION)
                setFlag(MarkdownLexer.PARAGRAPH, false)
            }
            char == '=' && paragraph && !listItem && setextUnderline(at) -> {
                out.span(at, lastSolid() + 1, RoleCode.PUNCTUATION)
                setFlag(MarkdownLexer.PARAGRAPH, false)
            }
            (char == '|' || char == ':' || char == '-') && delimiterRow(at) -> {
                var cursor = at
                while (cursor < end) {
                    if (!isBlank(text[cursor])) out.span(cursor, cursor + 1, RoleCode.PUNCTUATION)
                    cursor += 1
                }
                setFlag(MarkdownLexer.PARAGRAPH, false)
            }
            char == '<' && !quoted && !paragraph && !listItem && blockIndent <= 3 && htmlBlockStart(at) -> {
                host = (host and MarkdownLexer.LIST) or MarkdownLexer.HTML_BLOCK
                inner = 0
                embed(HtmlLexer, lineStart)
            }
            char == '[' && !paragraph && !listItem && referenceDefinition(at) -> setFlag(MarkdownLexer.PARAGRAPH, false)
            else -> {
                inline(at)
                setFlag(MarkdownLexer.PARAGRAPH, true)
            }
        }
    }

    private fun lastSolid(): Int {
        var at = end - 1
        while (at > lineStart && isBlank(text[at])) at -= 1
        return at
    }

    /** `---` or `+++` alone on the first line of the document. */
    private fun frontMatterOpen(): Boolean {
        val char = text[lineStart]
        if (char != '-' && char != '+') return false
        if (runEnd(lineStart, char) - lineStart != 3 || skipBlanks(lineStart + 3) != end) return false
        out.span(lineStart, lineStart + 3, RoleCode.PUNCTUATION)
        val position = HighlightLanguages.position(if (char == '+') "toml" else "yaml")
        host = MarkdownLexer.FRONT_MATTER or (if (char == '+') MarkdownLexer.TILDE else 0)
        if (position in 0 until MarkdownLexer.MAX_LANGUAGE) host = host or ((position + 1) shl MarkdownLexer.LANGUAGE_SHIFT)
        inner = 0
        return true
    }

    /** The end of a list marker (`-`, `*`, `+`, `1.`, `1)`) at [at] when one starts there, else -1. */
    private fun listMarkerEnd(at: Int): Int {
        val char = text[at]
        val markerEnd =
            if (char == '-' || char == '*' || char == '+') {
                at + 1
            } else if (isDigit(char)) {
                var digits = at
                while (digits < end && digits - at < 9 && isDigit(text[digits])) digits += 1
                if (digits < end && (text[digits] == '.' || text[digits] == ')')) digits + 1 else return -1
            } else {
                return -1
            }
        return if (markerEnd == end || text[markerEnd] == ' ' || text[markerEnd] == '\t') markerEnd else -1
    }

    private fun taskBoxEnd(at: Int): Int {
        if (at + 2 >= end || text[at] != '[' || text[at + 2] != ']') return -1
        val mark = text[at + 1]
        if (mark != ' ' && mark != 'x' && mark != 'X') return -1
        return if (at + 3 == end || isBlank(text[at + 3])) at + 3 else -1
    }

    /** Three or more of one of `*`, `-`, `_` with only blanks between and after. */
    private fun thematicBreak(at: Int): Boolean {
        val char = text[at]
        if (char != '*' && char != '-' && char != '_') return false
        var count = 0
        for (cursor in at until end) {
            val current = text[cursor]
            if (current == char) {
                count += 1
            } else if (!isBlank(current)) {
                return false
            }
        }
        return count >= 3
    }

    private fun setextUnderline(at: Int): Boolean = skipBlanks(runEnd(at, '=')) == end

    /** A table delimiter row: only `|`, `-`, `:` and blanks, with at least one `|` and one `-`. */
    private fun delimiterRow(at: Int): Boolean {
        var pipe = false
        var dash = false
        for (cursor in at until end) {
            when (text[cursor]) {
                '|' -> pipe = true
                '-' -> dash = true
                ':', ' ', '\t', '\r' -> Unit
                else -> return false
            }
        }
        return pipe && dash
    }

    /** An ATX heading at [at]: `#` marks Punctuation, text Keyword, closing marks Punctuation. */
    private fun heading(at: Int): Boolean {
        val marksEnd = runEnd(at, '#')
        if (marksEnd - at > 6 || (marksEnd < end && !isBlank(text[marksEnd]))) return false
        out.span(at, marksEnd, RoleCode.PUNCTUATION)
        val contentStart = skipBlanks(marksEnd)
        var contentEnd = lastSolid() + 1
        if (contentEnd <= contentStart) return true
        var closing = contentEnd
        while (closing > contentStart && text[closing - 1] == '#') closing -= 1
        if (closing < contentEnd && (closing == contentStart || isBlank(text[closing - 1]))) {
            var textEnd = closing
            while (textEnd > contentStart && isBlank(text[textEnd - 1])) textEnd -= 1
            out.span(contentStart, textEnd, RoleCode.KEYWORD)
            out.span(closing, contentEnd, RoleCode.PUNCTUATION)
            return true
        }
        contentEnd = maxOf(contentEnd, contentStart)
        out.span(contentStart, contentEnd, RoleCode.KEYWORD)
        return true
    }

    /** A fence opening at [at]; the info string's first word picks the language. */
    private fun fenceOpen(
        at: Int,
        indent: Int,
    ): Boolean {
        val char = text[at]
        val runEnd = runEnd(at, char)
        val length = runEnd - at
        if (length < 3) return false
        if (char == '`') {
            for (cursor in runEnd until end) if (text[cursor] == '`') return false
        }
        out.span(at, runEnd, RoleCode.PUNCTUATION)
        var wordStart = skipBlanks(runEnd)
        if (wordStart < end && text[wordStart] == '{') wordStart += 1
        if (wordStart < end && text[wordStart] == '.') wordStart += 1
        var wordEnd = wordStart
        while (wordEnd < end && !isBlank(text[wordEnd]) && text[wordEnd] != '{' && text[wordEnd] != '}' && text[wordEnd] != ',' &&
            text[wordEnd] != '`'
        ) {
            wordEnd += 1
        }
        var position = -1
        if (wordEnd > wordStart && wordEnd - wordStart <= 32 && out.embeddingDepth < SpanSink.MAX_EMBEDDING_DEPTH) {
            position = HighlightLanguages.position(text.concatToString(wordStart, wordEnd))
        }
        host = (host and MarkdownLexer.LIST) or MarkdownLexer.FENCE
        if (char == '~') host = host or MarkdownLexer.TILDE
        host = host or (minOf(length, MarkdownLexer.MAX_LENGTH) shl MarkdownLexer.LENGTH_SHIFT)
        host = host or (minOf(indent, 3) shl MarkdownLexer.INDENT_SHIFT)
        if (flag(MarkdownLexer.LIST)) host = host or MarkdownLexer.LIST_FENCE
        if (position in 0 until MarkdownLexer.MAX_LANGUAGE) host = host or ((position + 1) shl MarkdownLexer.LANGUAGE_SHIFT)
        inner = 0
        return true
    }

    /** `<tag`, `</tag`, `<!`, or `<?` starting an HTML block (not an autolink). */
    private fun htmlBlockStart(at: Int): Boolean {
        if (at + 1 >= end) return false
        val next = text[at + 1]
        if (next == '!' || next == '?') return true
        val nameStart = if (next == '/') at + 2 else at + 1
        if (nameStart >= end || !isAsciiLetter(text[nameStart])) return false
        var nameEnd = nameStart
        while (nameEnd < end && (isAsciiLetter(text[nameEnd]) || isDigit(text[nameEnd]) || text[nameEnd] == '-')) nameEnd += 1
        if (nameEnd == end) return true
        val after = text[nameEnd]
        return isBlank(after) || after == '>' || (after == '/' && nameEnd + 1 < end && text[nameEnd + 1] == '>')
    }

    /** `[label]: destination "title"`. */
    private fun referenceDefinition(at: Int): Boolean {
        var close = at + 1
        while (close < end && text[close] != ']') {
            if (text[close] == '\\') close += 1
            close += 1
        }
        if (close + 1 >= end || text[close + 1] != ':' || close == at + 1) return false
        out.span(at, at + 1, RoleCode.PUNCTUATION)
        out.span(at + 1, close, RoleCode.CONSTANT)
        out.span(close, close + 2, RoleCode.PUNCTUATION)
        var cursor = skipBlanks(close + 2)
        while (cursor < end && !isBlank(text[cursor])) cursor += 1
        cursor = skipBlanks(cursor)
        if (cursor < end && (text[cursor] == '"' || text[cursor] == '\'' || text[cursor] == '(')) out.span(cursor, lastSolid() + 1, RoleCode.STRING)
        return true
    }

    // endregion

    // region Inline

    private fun inline(from: Int) {
        var at = from
        while (at < end && !out.stopped) {
            if (starCloseAt in 0 until at) starCloseAt = -1
            if (underscoreCloseAt in 0 until at) underscoreCloseAt = -1
            if (tildeCloseAt in 0 until at) tildeCloseAt = -1
            val char = text[at]
            at =
                when (char) {
                    '\\' -> escape(at)
                    '`' -> codeSpan(at)
                    '*', '_' -> emphasis(at, char)
                    '~' -> strikethrough(at)
                    '!' -> if (at + 1 < end && text[at + 1] == '[') link(at + 1, image = at) else at + 1
                    '[' -> link(at, image = -1)
                    '<' -> angle(at)
                    '|' -> {
                        out.span(at, at + 1, RoleCode.PUNCTUATION)
                        at + 1
                    }
                    '&' -> entity(at)
                    else -> at + 1
                }
        }
    }

    private fun escape(at: Int): Int {
        if (at + 1 < end) {
            val next = text[at + 1]
            if (next.code in 0x21..0x7E && !isAsciiLetter(next) && !isDigit(next)) {
                out.span(at, at + 2, RoleCode.CONSTANT)
                return at + 2
            }
        }
        return at + 1
    }

    private fun entity(at: Int): Int {
        var cursor = at + 1
        if (cursor < end && text[cursor] == '#') {
            cursor += 1
            if (cursor < end && (text[cursor] == 'x' || text[cursor] == 'X')) cursor += 1
        }
        val nameStart = cursor
        while (cursor < end && cursor - nameStart < 32 && (isAsciiLetter(text[cursor]) || isDigit(text[cursor]))) cursor += 1
        if (cursor == nameStart || cursor >= end || text[cursor] != ';') return at + 1
        out.span(at, cursor + 1, RoleCode.CONSTANT)
        return cursor + 1
    }

    /** A code span of a backtick run at [at] closed by a run of the same length; the run stays plain when unclosed. */
    private fun codeSpan(at: Int): Int {
        val runEnd = runEnd(at, '`')
        val length = runEnd - at
        if (length < 32 && backtickFailed and (1 shl length) != 0) return runEnd
        var cursor = runEnd
        while (cursor < end) {
            if (text[cursor] != '`') {
                cursor += 1
                continue
            }
            val closeEnd = runEnd(cursor, '`')
            if (closeEnd - cursor == length) {
                out.span(at, runEnd, RoleCode.PUNCTUATION)
                out.span(runEnd, cursor, RoleCode.STRING)
                out.span(cursor, closeEnd, RoleCode.PUNCTUATION)
                return closeEnd
            }
            cursor = closeEnd
        }
        if (length < 32) backtickFailed = backtickFailed or (1 shl length)
        return runEnd
    }

    private fun isWordChar(char: Char): Boolean = isAsciiLetter(char) || isDigit(char) || char.code >= 0x80

    /** A `*` or `_` run at [at]: an open emphasis's closer, an opener with a closer later on the line, or plain. */
    private fun emphasis(
        at: Int,
        char: Char,
    ): Int {
        val runEnd = runEnd(at, char)
        val star = char == '*'
        val closeAt = if (star) starCloseAt else underscoreCloseAt
        if (closeAt == at) {
            val closeLength = if (star) starCloseLength else underscoreCloseLength
            out.span(at, at + closeLength, RoleCode.PUNCTUATION)
            if (star) starCloseAt = -1 else underscoreCloseAt = -1
            return at + closeLength
        }
        if (closeAt > at) return runEnd
        val length = minOf(runEnd - at, 3)
        if (length >= (if (star) starFailedLength else underscoreFailedLength)) return runEnd
        // Left-flanking opener; an underscore also must not be inside a word.
        if (runEnd >= end || isBlank(text[runEnd])) return runEnd
        if (!star && at > lineStart && isWordChar(text[at - 1])) return runEnd
        var cursor = runEnd
        while (cursor < end) {
            if (text[cursor] != char) {
                cursor += 1
                continue
            }
            val closeEnd = runEnd(cursor, char)
            val rightFlanking = !isBlank(text[cursor - 1]) && (star || closeEnd >= end || !isWordChar(text[closeEnd]))
            if (rightFlanking && closeEnd - cursor >= length) {
                out.span(at, at + length, RoleCode.PUNCTUATION)
                if (star) {
                    starCloseAt = cursor
                    starCloseLength = length
                } else {
                    underscoreCloseAt = cursor
                    underscoreCloseLength = length
                }
                return at + length
            }
            cursor = closeEnd
        }
        if (star) starFailedLength = minOf(starFailedLength, length) else underscoreFailedLength = minOf(underscoreFailedLength, length)
        return runEnd
    }

    /** `~~text~~`. */
    private fun strikethrough(at: Int): Int {
        val runEnd = runEnd(at, '~')
        if (tildeCloseAt == at && runEnd - at == 2) {
            out.span(at, runEnd, RoleCode.PUNCTUATION)
            tildeCloseAt = -1
            return runEnd
        }
        if (runEnd - at != 2 || tildeCloseAt > at || tildeFailed || runEnd >= end || isBlank(text[runEnd])) return runEnd
        var cursor = runEnd
        while (cursor < end) {
            if (text[cursor] != '~') {
                cursor += 1
                continue
            }
            val closeEnd = runEnd(cursor, '~')
            if (closeEnd - cursor == 2 && !isBlank(text[cursor - 1])) {
                out.span(at, runEnd, RoleCode.PUNCTUATION)
                tildeCloseAt = cursor
                return runEnd
            }
            cursor = closeEnd
        }
        tildeFailed = true
        return runEnd
    }

    /** The first unescaped [char] at or after [from], remembering the search so it is not repeated; -1 when none. */
    private fun nextClose(
        from: Int,
        char: Char,
    ): Int {
        val cachedFrom: Int
        val cachedAt: Int
        when (char) {
            ']' -> {
                cachedFrom = bracketFrom
                cachedAt = bracketAt
            }
            ')' -> {
                cachedFrom = parenFrom
                cachedAt = parenAt
            }
            else -> {
                cachedFrom = angleFrom
                cachedAt = angleAt
            }
        }
        if (cachedFrom in 0..from && (cachedAt < 0 || cachedAt >= from)) return cachedAt
        var cursor = from
        var found = -1
        while (cursor < end) {
            val current = text[cursor]
            if (current == '\\') {
                cursor += 2
                continue
            }
            if (current == char) {
                found = cursor
                break
            }
            cursor += 1
        }
        when (char) {
            ']' -> {
                bracketFrom = from
                bracketAt = found
            }
            ')' -> {
                parenFrom = from
                parenAt = found
            }
            else -> {
                angleFrom = from
                angleAt = found
            }
        }
        return found
    }

    /** `[text](destination "title")` or `[text][label]` at [at], after a `!` at [image] for an image. */
    private fun link(
        at: Int,
        image: Int,
    ): Int {
        val close = nextClose(at + 1, ']')
        if (close < 0 || close + 1 >= end) return at + 1
        val next = text[close + 1]
        if (next == '(') {
            val paren = nextClose(close + 2, ')')
            if (paren < 0) return at + 1
            if (image >= 0) out.span(image, image + 1, RoleCode.PUNCTUATION)
            out.span(at, at + 1, RoleCode.PUNCTUATION)
            out.span(at + 1, close, RoleCode.STRING)
            out.span(close, close + 2, RoleCode.PUNCTUATION)
            var cursor = skipBlanks(close + 2)
            while (cursor < paren && !isBlank(text[cursor])) cursor += 1
            cursor = skipBlanks(cursor)
            if (cursor < paren && (text[cursor] == '"' || text[cursor] == '\'')) {
                var titleEnd = paren
                while (titleEnd > cursor && isBlank(text[titleEnd - 1])) titleEnd -= 1
                out.span(cursor, titleEnd, RoleCode.STRING)
            }
            out.span(paren, paren + 1, RoleCode.PUNCTUATION)
            return paren + 1
        }
        if (next == '[') {
            val labelClose = nextClose(close + 2, ']')
            if (labelClose < 0) return at + 1
            if (image >= 0) out.span(image, image + 1, RoleCode.PUNCTUATION)
            out.span(at, at + 1, RoleCode.PUNCTUATION)
            out.span(at + 1, close, RoleCode.STRING)
            out.span(close, close + 2, RoleCode.PUNCTUATION)
            out.span(close + 2, labelClose, RoleCode.CONSTANT)
            out.span(labelClose, labelClose + 1, RoleCode.PUNCTUATION)
            return labelClose + 1
        }
        return at + 1
    }

    /** An autolink `<scheme:…>` or `<a@b>`, inline HTML, or a plain `<`. */
    private fun angle(at: Int): Int {
        if (at + 1 >= end) return at + 1
        val next = text[at + 1]
        if (next == '!' && LexerScan.startsWith(text, at, end, "<!--")) {
            if (commentFailed) return at + 1
            var cursor = at + 4
            while (cursor + 2 < end && !(text[cursor] == '-' && text[cursor + 1] == '-' && text[cursor + 2] == '>')) cursor += 1
            if (cursor + 2 >= end) {
                commentFailed = true
                return at + 1
            }
            out.span(at, cursor + 3, RoleCode.COMMENT)
            return cursor + 3
        }
        val tagLike = isAsciiLetter(next) || (next == '/' && at + 2 < end && isAsciiLetter(text[at + 2]))
        if (!tagLike) return at + 1
        val close = nextClose(at + 1, '>')
        if (close < 0) return at + 1
        // An autolink has no blanks and a `:` after a scheme, or an `@`; a tag has no other `<`.
        var blank = false
        var marker = false
        for (cursor in at + 1 until close) {
            val current = text[cursor]
            if (current == '<') return at + 1
            if (isBlank(current)) blank = true
            if (current == ':' || current == '@') marker = true
        }
        if (!blank && marker) {
            out.span(at, at + 1, RoleCode.PUNCTUATION)
            out.span(close, close + 1, RoleCode.PUNCTUATION)
            return close + 1
        }
        HtmlLexer.lexLine(text, at, close + 1, 0, out)
        return close + 1
    }

    // endregion
}
