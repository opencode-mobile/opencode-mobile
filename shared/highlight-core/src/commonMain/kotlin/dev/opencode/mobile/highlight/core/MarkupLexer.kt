package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * The tag lexer shared by [HtmlLexer] and [XmlLexer].
 *
 * Roles: `<`, `</`, `>`, `/>`, `=` are Punctuation; tag names Tag; attribute names Attribute;
 * attribute values String, quotes included, with entities Constant; entities in text Constant;
 * comments Comment. `<![CDATA[` and `]]>` are Punctuation around String content. A
 * declaration (`<!DOCTYPE html>`, and `<!ENTITY …>` in a doctype's internal subset) has its
 * name Keyword and strings String; a processing instruction (`<?xml …?>`) its target Tag and
 * pseudo-attributes Attribute and String. In XML a `prefix:name` splits into two names around a
 * Punctuation `:`. Text stays plain.
 *
 * HTML only: tag names are case-insensitive; `<style>` content is [CssLexer]'s and `<script>`
 * content JavaScript, TypeScript (`type` or `lang` naming TypeScript), or JSON (`importmap`,
 * `application/json`, `ld+json`); other script types stay plain. Script and style content ends
 * at the first `</script` or `</style` followed by a space, `/`, `>`, or the line end, as in a
 * browser. `/>` closes any element, `<script />` included.
 *
 * States (non-negative): the mode in bits 0 to 3, then the open element's kind (normal, script,
 * style) and script language, so a tag or attribute value can continue on the next line and its
 * `>` knows what follows; a doctype keeps whether it is inside `[…]`. Inside script or style
 * content the embedded lexer's state joins it through [SpanSink.embedded].
 */
internal open class MarkupLexer(
    private val html: Boolean,
) : HighlightLexer() {
    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int = MarkupLine(html, text, start, end, out).run(state)

    companion object {
        const val DATA = 0
        const val TAG = 1
        const val TAG_VALUE = 2
        const val VALUE_DOUBLE = 3
        const val VALUE_SINGLE = 4
        const val END_TAG = 5
        const val COMMENT = 6
        const val DECLARATION = 7
        const val CDATA = 8
        const val INSTRUCTION = 9
        const val RAW = 10
        const val MODE_MASK = 15

        const val KIND_SHIFT = 4
        const val NORMAL = 0
        const val SCRIPT = 1
        const val STYLE = 2

        const val LANGUAGE_SHIFT = 6
        const val JAVASCRIPT = 0
        const val TYPESCRIPT = 1
        const val JSON = 2
        const val UNKNOWN = 3

        /** In a declaration: inside its `[…]` internal subset. */
        const val SUBSET = 1 shl 8

        private val javaScriptTypes =
            listOf(
                "text/javascript", "application/javascript", "module", "text/babel", "text/jsx", "jsx", "javascript", "js",
                "application/x-javascript", "text/ecmascript", "application/ecmascript",
            )
        private val typeScriptTypes = listOf("ts", "tsx", "typescript", "text/typescript", "application/typescript", "text/tsx")
        private val jsonTypes = listOf("application/json", "importmap", "application/ld+json", "speculationrules", "json")

        /** The script language a `type` or `lang` value names. */
        fun scriptLanguage(
            text: CharArray,
            from: Int,
            to: Int,
        ): Int {
            val start = LexerScan.skipBlanks(text, from, to)
            var end = to
            while (end > start && (text[end - 1] == ' ' || text[end - 1] == '\t')) end -= 1
            if (start == end) return JAVASCRIPT
            if (javaScriptTypes.any { equalsIgnoreCase(text, start, end, it) }) return JAVASCRIPT
            if (typeScriptTypes.any { equalsIgnoreCase(text, start, end, it) }) return TYPESCRIPT
            if (jsonTypes.any { equalsIgnoreCase(text, start, end, it) }) return JSON
            return UNKNOWN
        }

        /** True when `text[from, to)` is [word] (lowercase ASCII) in any case. */
        fun equalsIgnoreCase(
            text: CharArray,
            from: Int,
            to: Int,
            word: String,
        ): Boolean {
            if (to - from != word.length) return false
            for (index in word.indices) {
                if (lower(text[from + index]) != word[index]) return false
            }
            return true
        }

        fun lower(char: Char): Char = if (char in 'A'..'Z') char + 32 else char

        fun isNameStart(char: Char): Boolean = isAsciiLetter(char) || char == '_' || char == ':' || char.code >= 0x80

        fun isNamePart(char: Char): Boolean = isNameStart(char) || isDigit(char) || char == '-' || char == '.'
    }
}

/** One line of markup; a new one per line keeps [MarkupLexer] stateless. */
private class MarkupLine(
    private val html: Boolean,
    private val text: CharArray,
    start: Int,
    private val end: Int,
    private val out: SpanSink,
) {
    private var index = start
    private var mode = MarkupLexer.DATA
    private var kind = MarkupLexer.NORMAL
    private var language = MarkupLexer.JAVASCRIPT
    private var subset = false
    private var inner = 0

    // Per line only: the attribute being lexed is `type` or `lang` of a script tag.
    private var languageAttribute = false

    fun run(state: Int): Int {
        val host = out.embedded.host(state)
        inner = out.embedded.inner(state)
        mode = host and MarkupLexer.MODE_MASK
        kind = (host ushr MarkupLexer.KIND_SHIFT) and 3
        language = (host ushr MarkupLexer.LANGUAGE_SHIFT) and 3
        subset = host and MarkupLexer.SUBSET != 0
        while (index < end && !out.stopped) {
            when (mode) {
                MarkupLexer.DATA -> data()
                MarkupLexer.TAG -> tag()
                MarkupLexer.TAG_VALUE -> tagValue()
                MarkupLexer.VALUE_DOUBLE -> quotedValue(index, '"')
                MarkupLexer.VALUE_SINGLE -> quotedValue(index, '\'')
                MarkupLexer.END_TAG -> endTag()
                MarkupLexer.COMMENT -> comment(index)
                MarkupLexer.DECLARATION -> declaration()
                MarkupLexer.CDATA -> cdata(index)
                MarkupLexer.INSTRUCTION -> instruction()
                else -> raw()
            }
        }
        val hostState =
            mode or (kind shl MarkupLexer.KIND_SHIFT) or (language shl MarkupLexer.LANGUAGE_SHIFT) or
                (if (subset) MarkupLexer.SUBSET else 0)
        return if (mode == MarkupLexer.RAW) out.embedded.join(hostState, inner) else hostState
    }

    private fun data() {
        var at = index
        while (at < end) {
            val char = text[at]
            if (char == '<' || (char == '&' && out.collecting)) break
            at += 1
        }
        index = at
        if (at >= end) return
        if (text[at] == '&') {
            val entityEnd = entityEnd(at, end)
            if (entityEnd > at) out.span(at, entityEnd, RoleCode.CONSTANT)
            index = if (entityEnd > at) entityEnd else at + 1
            return
        }
        open()
    }

    /** The end of an entity (`&name;`, `&#10;`, `&#x1F;`) at [at], or [at] when there is none. */
    private fun entityEnd(
        at: Int,
        limit: Int,
    ): Int {
        var cursor = at + 1
        if (cursor < limit && text[cursor] == '#') {
            cursor += 1
            val hex = cursor < limit && (text[cursor] == 'x' || text[cursor] == 'X')
            if (hex) cursor += 1
            val digitsStart = cursor
            while (cursor < limit && cursor - digitsStart < 8 && (if (hex) isHexDigit(text[cursor]) else isDigit(text[cursor]))) cursor += 1
            if (cursor == digitsStart) return at
        } else {
            val nameStart = cursor
            while (cursor < limit && cursor - nameStart < 32 && (isAsciiLetter(text[cursor]) || isDigit(text[cursor]))) cursor += 1
            if (cursor == nameStart) return at
        }
        return if (cursor < limit && text[cursor] == ';') cursor + 1 else at
    }

    /** A `<` in text at [index]: a tag, comment, declaration, CDATA section, or instruction, else plain. */
    private fun open() {
        val at = index
        val next = if (at + 1 < end) text[at + 1] else '\u0000'
        when {
            next == '!' && LexerScan.startsWith(text, at, end, "<!--") -> {
                mode = MarkupLexer.COMMENT
                comment(at + 4, from = at)
            }
            next == '!' && LexerScan.startsWith(text, at, end, "<![CDATA[") -> {
                out.span(at, at + 9, RoleCode.PUNCTUATION)
                mode = MarkupLexer.CDATA
                cdata(at + 9)
            }
            next == '!' && at + 2 < end && isAsciiLetter(text[at + 2]) -> {
                out.span(at, at + 2, RoleCode.PUNCTUATION)
                index = nameEnd(at + 2)
                out.span(at + 2, index, RoleCode.KEYWORD)
                mode = MarkupLexer.DECLARATION
                subset = false
            }
            next == '?' -> {
                out.span(at, at + 2, RoleCode.PUNCTUATION)
                index = nameEnd(at + 2)
                out.span(at + 2, index, RoleCode.TAG)
                mode = MarkupLexer.INSTRUCTION
            }
            next == '/' && at + 2 < end && MarkupLexer.isNameStart(text[at + 2]) -> closeTag(at)
            MarkupLexer.isNameStart(next) && next != ':' -> {
                out.span(at, at + 1, RoleCode.PUNCTUATION)
                val nameEnd = nameEnd(at + 1)
                name(at + 1, nameEnd, RoleCode.TAG)
                kind =
                    when {
                        !html -> MarkupLexer.NORMAL
                        MarkupLexer.equalsIgnoreCase(text, at + 1, nameEnd, "script") -> MarkupLexer.SCRIPT
                        MarkupLexer.equalsIgnoreCase(text, at + 1, nameEnd, "style") -> MarkupLexer.STYLE
                        else -> MarkupLexer.NORMAL
                    }
                language = MarkupLexer.JAVASCRIPT
                index = nameEnd
                mode = MarkupLexer.TAG
            }
            else -> index = at + 1
        }
    }

    /** `</name` at [at]. */
    private fun closeTag(at: Int) {
        out.span(at, at + 2, RoleCode.PUNCTUATION)
        val nameEnd = nameEnd(at + 2)
        name(at + 2, nameEnd, RoleCode.TAG)
        index = nameEnd
        mode = MarkupLexer.END_TAG
        kind = MarkupLexer.NORMAL
        language = MarkupLexer.JAVASCRIPT
    }

    private fun nameEnd(from: Int): Int {
        var at = from
        while (at < end && MarkupLexer.isNamePart(text[at])) at += 1
        return at
    }

    /** A tag or attribute name; in XML a `prefix:` splits off around a Punctuation colon. */
    private fun name(
        from: Int,
        to: Int,
        role: Int,
    ) {
        if (!html) {
            var colon = from
            while (colon < to && text[colon] != ':') colon += 1
            if (colon < to && colon > from && colon + 1 < to) {
                out.span(from, colon, role)
                out.span(colon, colon + 1, RoleCode.PUNCTUATION)
                out.span(colon + 1, to, role)
                return
            }
        }
        out.span(from, to, role)
    }

    private fun isSpace(char: Char): Boolean = char == ' ' || char == '\t' || char == '\r' || char == '\u000C'

    private fun tag() {
        val char = text[index]
        when {
            isSpace(char) -> index += 1
            char == '>' -> {
                out.span(index, index + 1, RoleCode.PUNCTUATION)
                index += 1
                if (kind == MarkupLexer.NORMAL) {
                    mode = MarkupLexer.DATA
                } else {
                    mode = MarkupLexer.RAW
                    inner = 0
                }
            }
            char == '/' && index + 1 < end && text[index + 1] == '>' -> {
                out.span(index, index + 2, RoleCode.PUNCTUATION)
                index += 2
                mode = MarkupLexer.DATA
                kind = MarkupLexer.NORMAL
                language = MarkupLexer.JAVASCRIPT
            }
            char == '=' -> {
                out.span(index, index + 1, RoleCode.PUNCTUATION)
                index += 1
                mode = MarkupLexer.TAG_VALUE
            }
            char == '"' || char == '\'' -> {
                languageAttribute = false
                mode = MarkupLexer.TAG_VALUE
            }
            char == '<' || char == '/' -> index += 1
            else -> {
                var at = index + 1
                while (at < end) {
                    val part = text[at]
                    if (isSpace(part) || part == '=' || part == '>' || part == '"' || part == '\'' || part == '<') break
                    if (part == '/' && at + 1 < end && text[at + 1] == '>') break
                    at += 1
                }
                name(index, at, RoleCode.ATTRIBUTE)
                languageAttribute =
                    kind == MarkupLexer.SCRIPT &&
                    (MarkupLexer.equalsIgnoreCase(text, index, at, "type") || MarkupLexer.equalsIgnoreCase(text, index, at, "lang"))
                index = at
            }
        }
    }

    private fun tagValue() {
        val char = text[index]
        when {
            isSpace(char) -> index += 1
            char == '"' -> quotedValue(index + 1, '"', open = index)
            char == '\'' -> quotedValue(index + 1, '\'', open = index)
            char == '>' -> mode = MarkupLexer.TAG
            else -> {
                var at = index
                while (at < end && !isSpace(text[at]) && text[at] != '>') at += 1
                value(index, at, index, at)
                index = at
                mode = MarkupLexer.TAG
            }
        }
    }

    /** A quoted value whose text continues at [from]; [open] is its opening quote on this line, if any. */
    private fun quotedValue(
        from: Int,
        quote: Char,
        open: Int = from,
    ) {
        var close = from
        while (close < end && text[close] != quote) close += 1
        if (close < end) {
            value(open, close + 1, from, close)
            index = close + 1
            mode = MarkupLexer.TAG
        } else {
            value(open, end, from, end)
            index = end
            mode = if (quote == '"') MarkupLexer.VALUE_DOUBLE else MarkupLexer.VALUE_SINGLE
        }
    }

    /** An attribute value spanning `[from, to)` with its text in `[contentFrom, contentTo)`: String with entities Constant. */
    private fun value(
        from: Int,
        to: Int,
        contentFrom: Int,
        contentTo: Int,
    ) {
        if (languageAttribute) {
            language = MarkupLexer.scriptLanguage(text, contentFrom, contentTo)
            languageAttribute = false
        }
        if (!out.collecting) {
            out.span(from, to, RoleCode.STRING)
            return
        }
        var segment = from
        var at = contentFrom
        while (at < contentTo) {
            if (text[at] == '&') {
                val entityEnd = entityEnd(at, contentTo)
                if (entityEnd > at) {
                    out.span(segment, at, RoleCode.STRING)
                    out.span(at, entityEnd, RoleCode.CONSTANT)
                    segment = entityEnd
                    at = entityEnd
                    continue
                }
            }
            at += 1
        }
        out.span(segment, to, RoleCode.STRING)
    }

    private fun endTag() {
        val char = text[index]
        if (char == '>') {
            out.span(index, index + 1, RoleCode.PUNCTUATION)
            mode = MarkupLexer.DATA
        }
        index += 1
    }

    /** A comment whose text continues at [bodyFrom]; [from] is where its span starts. */
    private fun comment(
        bodyFrom: Int,
        from: Int = bodyFrom,
    ) {
        val close = find(bodyFrom, "-->")
        val to = if (close < 0) end else close + 3
        out.span(from, to, RoleCode.COMMENT)
        index = to
        if (close >= 0) mode = MarkupLexer.DATA
    }

    private fun cdata(from: Int) {
        val close = find(from, "]]>")
        out.span(from, if (close < 0) end else close, RoleCode.STRING)
        if (close < 0) {
            index = end
        } else {
            out.span(close, close + 3, RoleCode.PUNCTUATION)
            index = close + 3
            mode = MarkupLexer.DATA
        }
    }

    /** The first offset of [needle] at or after [from] on this line, or -1. */
    private fun find(
        from: Int,
        needle: String,
    ): Int {
        var at = from
        val last = end - needle.length
        while (at <= last) {
            if (text[at] == needle[0] && LexerScan.startsWith(text, at, end, needle)) return at
            at += 1
        }
        return -1
    }

    private fun declaration() {
        val char = text[index]
        when {
            char == '>' -> {
                out.span(index, index + 1, RoleCode.PUNCTUATION)
                index += 1
                if (!subset) mode = MarkupLexer.DATA
            }
            char == '[' && !subset -> {
                out.span(index, index + 1, RoleCode.PUNCTUATION)
                index += 1
                subset = true
            }
            char == ']' && subset -> {
                out.span(index, index + 1, RoleCode.PUNCTUATION)
                index += 1
                subset = false
            }
            char == '"' || char == '\'' -> {
                var close = index + 1
                while (close < end && text[close] != char) close += 1
                val to = if (close < end) close + 1 else end
                out.span(index, to, RoleCode.STRING)
                index = to
            }
            subset && char == '<' && LexerScan.startsWith(text, index, end, "<!--") -> {
                val close = find(index + 4, "-->")
                val to = if (close < 0) end else close + 3
                out.span(index, to, RoleCode.COMMENT)
                index = to
            }
            subset && char == '<' && index + 2 < end && text[index + 1] == '!' && isAsciiLetter(text[index + 2]) -> {
                out.span(index, index + 2, RoleCode.PUNCTUATION)
                val nameEnd = nameEnd(index + 2)
                out.span(index + 2, nameEnd, RoleCode.KEYWORD)
                index = nameEnd
            }
            char == '%' && index + 1 < end && MarkupLexer.isNameStart(text[index + 1]) -> {
                val nameEnd = nameEnd(index + 1)
                val to = if (nameEnd < end && text[nameEnd] == ';') nameEnd + 1 else nameEnd
                out.span(index, to, RoleCode.CONSTANT)
                index = to
            }
            else -> index += 1
        }
    }

    private fun instruction() {
        val char = text[index]
        when {
            char == '?' && index + 1 < end && text[index + 1] == '>' -> {
                out.span(index, index + 2, RoleCode.PUNCTUATION)
                index += 2
                mode = MarkupLexer.DATA
            }
            char == '"' || char == '\'' -> {
                var close = index + 1
                while (close < end && text[close] != char) close += 1
                val to = if (close < end) close + 1 else end
                out.span(index, to, RoleCode.STRING)
                index = to
            }
            MarkupLexer.isNameStart(char) -> {
                val nameEnd = nameEnd(index)
                out.span(index, nameEnd, RoleCode.ATTRIBUTE)
                index = nameEnd
            }
            char == '=' -> {
                out.span(index, index + 1, RoleCode.PUNCTUATION)
                index += 1
            }
            else -> index += 1
        }
    }

    /** Script or style content up to its end tag, lexed by the embedded language. */
    private fun raw() {
        val closeName = if (kind == MarkupLexer.STYLE) "style" else "script"
        val close = rawEnd(closeName)
        val contentEnd = if (close < 0) end else close
        val lexer: HighlightLexer? =
            when {
                kind == MarkupLexer.STYLE -> CssLexer
                language == MarkupLexer.JAVASCRIPT -> CLikeLanguages.javaScript
                language == MarkupLexer.TYPESCRIPT -> CLikeLanguages.typeScript
                language == MarkupLexer.JSON -> JsonLexer
                else -> null
            }
        if (lexer != null && contentEnd > index) inner = lexer.lexLine(text, index, contentEnd, inner, out)
        if (out.stopped) return
        if (close < 0) {
            index = end
            return
        }
        inner = 0
        closeTag(close)
    }

    /** Where `</name` ends this element's raw content on this line, or -1. */
    private fun rawEnd(name: String): Int {
        var at = index
        val last = end - 2 - name.length
        while (at <= last) {
            if (text[at] == '<' && text[at + 1] == '/' && MarkupLexer.equalsIgnoreCase(text, at + 2, at + 2 + name.length, name)) {
                val after = at + 2 + name.length
                if (after == end || isSpace(text[after]) || text[after] == '/' || text[after] == '>') return at
            }
            at += 1
        }
        return -1
    }
}
