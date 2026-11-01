package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * The single-file component lexer shared by [VueLexer] and [SvelteLexer]: HTML markup with the
 * component languages' expressions, `<script>` content as JavaScript, TypeScript (`lang="ts"`),
 * or JSON, and `<style>` content as [CssLexer]'s (which tolerates `lang="scss"` and `less`).
 *
 * Markup roles match [HtmlLexer]: `<`, `</`, `>`, `/>`, `=` Punctuation; tag names Tag;
 * attribute names Attribute; plain attribute values String with entities Constant; entities in
 * text Constant; comments Comment; a doctype's name Keyword. Embedded expressions are
 * TypeScript's, as in the official grammars, between Punctuation delimiters.
 *
 * Vue: `{{ }}` interpolation in text (ending at the first `}}`, as Vue's parser does); directive
 * attributes (`v-…`, and the `:`, `@`, `#`, `.` shorthands) whose values are expressions, with
 * their quotes Punctuation; `v-if`, `v-else-if`, `v-else`, `v-for` Keyword, other `v-` names
 * Attribute, shorthand characters and the `:` and `.` separators Punctuation, arguments and
 * modifiers Attribute; a `style` attribute's value is CSS declarations.
 *
 * Svelte: `{expression}` in text, between attributes (`{name}`, `{...rest}`), as an attribute
 * value, and inside quoted values; an expression ends at its matching `}` (strings skipped,
 * braces counted across lines). Block tags (`{#if}`, `{:else if}`, `{/if}`, `{#each}`, `{@html}`,
 * `{@const}`, …) have their `#`, `:`, `/`, `@` Punctuation and their word Keyword, and the
 * `then` and `catch` of `{#await}` Keyword. Directives (`on:`, `bind:`, `use:`, `class:`,
 * `transition:`, …) color like the official grammar: the directive Keyword (`class` and `style`
 * Attribute), `:` Punctuation, the name Type (`on:`), Function (`use:`, transitions), or Variable,
 * and `|modifier` Punctuation and Function. A capitalized tag (a component) is Type.
 *
 * States (non-negative host states): the mode in bits 0 to 3, the open element's kind (normal,
 * script, style) and script language, the language of an attribute value being lexed (none,
 * expression, CSS), and in a Svelte expression the mode to return to and how many braces are
 * open inside it. Inside script or style content, an interpolation, an expression, or an
 * expression-valued attribute, the embedded lexer's state joins it through [SpanSink.embedded];
 * a closed expression drops its embedded state, so the line after it compares equal again.
 */
internal abstract class ComponentMarkupLexer(
    private val svelte: Boolean,
) : HighlightLexer() {
    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int = ComponentLine(svelte, text, start, end, out).run(state)

    companion object {
        const val DATA = 0
        const val TAG = 1
        const val TAG_VALUE = 2
        const val VALUE_DOUBLE = 3
        const val VALUE_SINGLE = 4
        const val END_TAG = 5
        const val COMMENT = 6
        const val DECLARATION = 7
        const val RAW = 8
        const val EMBEDDED_DOUBLE = 9
        const val EMBEDDED_SINGLE = 10
        const val INTERPOLATION = 11
        const val BRACE = 12
        const val MODE_MASK = 15

        const val KIND_SHIFT = 4
        const val NORMAL = 0
        const val SCRIPT = 1
        const val STYLE = 2

        const val LANGUAGE_SHIFT = 6

        /** What the attribute whose value comes next is: plain, an expression, or CSS. */
        const val VALUE_SHIFT = 8
        const val VALUE_PLAIN = 0
        const val VALUE_EXPRESSION = 1
        const val VALUE_CSS = 2

        /** In a Svelte expression: where it returns (text, tag, a double- or single-quoted value). */
        const val RETURN_SHIFT = 10
        const val RETURN_DATA = 0
        const val RETURN_TAG = 1
        const val RETURN_DOUBLE = 2
        const val RETURN_SINGLE = 3

        /** In a Svelte expression: braces open inside it, capped at [MAX_DEPTH]. */
        const val DEPTH_SHIFT = 12
        const val MAX_DEPTH = 15

        val expressionLexer: HighlightLexer get() = CLikeLanguages.typeScript

        private val svelteDirectives =
            listOf("on", "bind", "use", "class", "style", "transition", "in", "out", "animate", "let")
    }

    /** One line of a component; a new one per line keeps the lexer stateless. */
    private class ComponentLine(
        private val svelte: Boolean,
        private val text: CharArray,
        start: Int,
        private val end: Int,
        private val out: SpanSink,
    ) {
        private var index = start
        private var mode = DATA
        private var kind = NORMAL
        private var language = MarkupLexer.JAVASCRIPT
        private var valueKind = VALUE_PLAIN
        private var returnTo = RETURN_DATA
        private var depth = 0
        private var inner = 0

        // Per line only: the attribute being lexed is `type` or `lang` of a script tag.
        private var languageAttribute = false

        fun run(state: Int): Int {
            val host = out.embedded.host(state)
            inner = out.embedded.inner(state)
            mode = host and MODE_MASK
            kind = (host ushr KIND_SHIFT) and 3
            language = (host ushr LANGUAGE_SHIFT) and 3
            valueKind = (host ushr VALUE_SHIFT) and 3
            returnTo = (host ushr RETURN_SHIFT) and 3
            depth = (host ushr DEPTH_SHIFT) and MAX_DEPTH
            while (index < end && !out.stopped) {
                when (mode) {
                    DATA -> data()
                    TAG -> tag()
                    TAG_VALUE -> tagValue()
                    VALUE_DOUBLE -> quotedValue(index, '"', index)
                    VALUE_SINGLE -> quotedValue(index, '\'', index)
                    END_TAG -> endTag()
                    COMMENT -> comment(index)
                    DECLARATION -> declaration()
                    RAW -> raw()
                    EMBEDDED_DOUBLE -> embeddedValue('"')
                    EMBEDDED_SINGLE -> embeddedValue('\'')
                    INTERPOLATION -> interpolation()
                    else -> brace(splitAwait = false)
                }
            }
            var hostState = mode or (kind shl KIND_SHIFT) or (language shl LANGUAGE_SHIFT) or (valueKind shl VALUE_SHIFT)
            if (mode == BRACE) hostState = hostState or (returnTo shl RETURN_SHIFT) or (minOf(depth, MAX_DEPTH) shl DEPTH_SHIFT)
            val embedding = mode == RAW || mode == EMBEDDED_DOUBLE || mode == EMBEDDED_SINGLE || mode == INTERPOLATION || mode == BRACE
            return if (embedding) out.embedded.join(hostState, inner) else hostState
        }

        // region Text

        private fun data() {
            var at = index
            while (at < end) {
                val char = text[at]
                if (char == '<' || (char == '&' && out.collecting)) break
                if (char == '{' && (svelte || (at + 1 < end && text[at + 1] == '{'))) break
                at += 1
            }
            index = at
            if (at >= end) return
            when (text[at]) {
                '&' -> {
                    val entityEnd = entityEnd(at, end)
                    if (entityEnd > at) out.span(at, entityEnd, RoleCode.CONSTANT)
                    index = if (entityEnd > at) entityEnd else at + 1
                }
                '{' -> if (svelte) openBrace(RETURN_DATA) else openInterpolation()
                else -> open()
            }
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

        /** Vue `{{` at [index]. */
        private fun openInterpolation() {
            out.span(index, index + 2, RoleCode.PUNCTUATION)
            index += 2
            mode = INTERPOLATION
            inner = 0
            interpolation()
        }

        /** A Vue interpolation's expression from [index] up to the first `}}`. */
        private fun interpolation() {
            val close = find(index, "}}")
            val contentEnd = if (close < 0) end else close
            if (contentEnd > index) inner = expressionLexer.lexLine(text, index, contentEnd, inner, out)
            if (out.stopped) return
            if (close < 0) {
                index = end
                return
            }
            out.span(close, close + 2, RoleCode.PUNCTUATION)
            index = close + 2
            inner = 0
            mode = DATA
        }

        // endregion

        // region Svelte expressions

        /** A Svelte `{` at [index]; a block tag's keyword when it opens in text. */
        private fun openBrace(returnMode: Int) {
            val at = index
            val sigil = if (at + 1 < end) text[at + 1] else ' '
            var splitAwait = false
            if (returnMode == RETURN_DATA && (sigil == '#' || sigil == ':' || sigil == '/' || sigil == '@') &&
                at + 2 < end && isAsciiLetter(text[at + 2])
            ) {
                var wordEnd = at + 2
                while (wordEnd < end && (isAsciiLetter(text[wordEnd]) || text[wordEnd] == '-')) wordEnd += 1
                out.span(at, at + 2, RoleCode.PUNCTUATION)
                out.span(at + 2, wordEnd, RoleCode.KEYWORD)
                splitAwait = sigil == '#' && LexerScan.equals(text, at + 2, wordEnd, "await")
                index = wordEnd
            } else {
                out.span(at, at + 1, RoleCode.PUNCTUATION)
                index = at + 1
            }
            mode = BRACE
            returnTo = returnMode
            depth = 0
            inner = 0
            brace(splitAwait)
        }

        /** A Svelte expression from [index] to its matching `}`; with [splitAwait], `then` and `catch` are Keyword. */
        private fun brace(splitAwait: Boolean) {
            while (index < end && !out.stopped) {
                val stop = braceStop(index, splitAwait)
                val contentEnd = if (stop < 0) end else stop
                if (contentEnd > index) inner = expressionLexer.lexLine(text, index, contentEnd, inner, out)
                if (out.stopped) return
                if (stop < 0) {
                    index = end
                    return
                }
                if (text[stop] != '}') {
                    // `then` or `catch` of an await block.
                    val wordEnd = stop + (if (text[stop] == 't') 4 else 5)
                    out.span(stop, wordEnd, RoleCode.KEYWORD)
                    index = wordEnd
                    inner = 0
                    continue
                }
                out.span(stop, stop + 1, RoleCode.PUNCTUATION)
                index = stop + 1
                inner = 0
                depth = 0
                mode =
                    when (returnTo) {
                        RETURN_TAG -> TAG
                        RETURN_DOUBLE -> VALUE_DOUBLE
                        RETURN_SINGLE -> VALUE_SINGLE
                        else -> DATA
                    }
                returnTo = RETURN_DATA
                return
            }
        }

        /**
         * The closing `}` of the expression at or after [from] (or a top-level `then` or `catch`
         * word with [splitAwait]), or -1 when the line ends first; counts nested braces into
         * [depth] and skips quoted strings, which end with the line.
         */
        private fun braceStop(
            from: Int,
            splitAwait: Boolean,
        ): Int {
            var at = from
            while (at < end) {
                val char = text[at]
                if (char == '{') {
                    depth += 1
                } else if (char == '}') {
                    if (depth == 0) return at
                    depth -= 1
                } else if (char == '"' || char == '\'' || char == '`') {
                    at += 1
                    while (at < end && text[at] != char) at += if (text[at] == '\\') 2 else 1
                } else if (splitAwait && depth == 0 && (char == 't' || char == 'c') && isWordStartAt(at, from)) {
                    val word = if (char == 't') "then" else "catch"
                    val wordEnd = at + word.length
                    if (LexerScan.startsWith(text, at, end, word) && (wordEnd == end || !LexerScan.isWordPart(text[wordEnd]))) return at
                }
                at += 1
            }
            if (depth > MAX_DEPTH) depth = MAX_DEPTH
            return -1
        }

        /** True when a word can start at [at]: the scan's first offset, or after neither a word character nor `.`. */
        private fun isWordStartAt(
            at: Int,
            from: Int,
        ): Boolean = at == from || (!LexerScan.isWordPart(text[at - 1]) && text[at - 1] != '.')

        // endregion

        // region Tags

        /** A `<` in text at [index]: a tag, comment, or declaration, else plain. */
        private fun open() {
            val at = index
            val next = if (at + 1 < end) text[at + 1] else '\u0000'
            when {
                next == '!' && LexerScan.startsWith(text, at, end, "<!--") -> {
                    mode = COMMENT
                    comment(at + 4, from = at)
                }
                next == '!' && at + 2 < end && isAsciiLetter(text[at + 2]) -> {
                    out.span(at, at + 2, RoleCode.PUNCTUATION)
                    index = nameEnd(at + 2)
                    out.span(at + 2, index, RoleCode.KEYWORD)
                    mode = DECLARATION
                }
                next == '/' && at + 2 < end && MarkupLexer.isNameStart(text[at + 2]) -> closeTag(at)
                MarkupLexer.isNameStart(next) && next != ':' -> {
                    out.span(at, at + 1, RoleCode.PUNCTUATION)
                    val nameEnd = nameEnd(at + 1)
                    tagName(at + 1, nameEnd)
                    kind =
                        when {
                            MarkupLexer.equalsIgnoreCase(text, at + 1, nameEnd, "script") -> SCRIPT
                            MarkupLexer.equalsIgnoreCase(text, at + 1, nameEnd, "style") -> STYLE
                            else -> NORMAL
                        }
                    language = MarkupLexer.JAVASCRIPT
                    valueKind = VALUE_PLAIN
                    index = nameEnd
                    mode = TAG
                }
                else -> index = at + 1
            }
        }

        /** `</name` at [at]. */
        private fun closeTag(at: Int) {
            out.span(at, at + 2, RoleCode.PUNCTUATION)
            val nameEnd = nameEnd(at + 2)
            tagName(at + 2, nameEnd)
            index = nameEnd
            mode = END_TAG
            kind = NORMAL
            language = MarkupLexer.JAVASCRIPT
            valueKind = VALUE_PLAIN
        }

        private fun nameEnd(from: Int): Int {
            var at = from
            while (at < end && MarkupLexer.isNamePart(text[at])) at += 1
            return at
        }

        /** A tag name: `svelte:head` splits around a Punctuation colon; a Svelte component is Type. */
        private fun tagName(
            from: Int,
            to: Int,
        ) {
            if (!out.collecting) return
            val role = if (svelte && text[from] in 'A'..'Z') RoleCode.TYPE else RoleCode.TAG
            var colon = from
            while (colon < to && text[colon] != ':') colon += 1
            if (colon < to && colon > from && colon + 1 < to) {
                out.span(from, colon, role)
                out.span(colon, colon + 1, RoleCode.PUNCTUATION)
                out.span(colon + 1, to, role)
            } else {
                out.span(from, to, role)
            }
        }

        private fun isSpace(char: Char): Boolean = char == ' ' || char == '\t' || char == '\r' || char == '\u000C'

        private fun tag() {
            val char = text[index]
            when {
                isSpace(char) -> index += 1
                char == '>' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    valueKind = VALUE_PLAIN
                    if (kind == NORMAL) {
                        mode = DATA
                    } else {
                        mode = RAW
                        inner = 0
                    }
                }
                char == '/' && index + 1 < end && text[index + 1] == '>' -> {
                    out.span(index, index + 2, RoleCode.PUNCTUATION)
                    index += 2
                    mode = DATA
                    kind = NORMAL
                    language = MarkupLexer.JAVASCRIPT
                    valueKind = VALUE_PLAIN
                }
                char == '=' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    mode = TAG_VALUE
                }
                char == '"' || char == '\'' -> {
                    languageAttribute = false
                    mode = TAG_VALUE
                }
                char == '{' && svelte -> {
                    valueKind = VALUE_PLAIN
                    openBrace(RETURN_TAG)
                }
                char == '<' || char == '/' -> index += 1
                else -> {
                    var at = index + 1
                    while (at < end) {
                        val part = text[at]
                        if (isSpace(part) || part == '=' || part == '>' || part == '"' || part == '\'' || part == '<') break
                        if (part == '/' && at + 1 < end && text[at + 1] == '>') break
                        if (part == '{' && svelte) break
                        at += 1
                    }
                    valueKind = if (svelte) svelteAttribute(index, at) else vueAttribute(index, at)
                    languageAttribute =
                        kind == SCRIPT &&
                        (MarkupLexer.equalsIgnoreCase(text, index, at, "type") || MarkupLexer.equalsIgnoreCase(text, index, at, "lang"))
                    index = at
                }
            }
        }

        /** Colors a Vue attribute name and returns what its value is. */
        private fun vueAttribute(
            from: Int,
            to: Int,
        ): Int {
            val first = text[from]
            val shorthand = first == ':' || first == '@' || first == '#' || first == '.'
            val prefixed = to - from > 2 && first == 'v' && text[from + 1] == '-'
            if (!shorthand && !prefixed) {
                out.span(from, to, RoleCode.ATTRIBUTE)
                return if (kind == NORMAL && LexerScan.equals(text, from, to, "style")) VALUE_CSS else VALUE_PLAIN
            }
            if (!out.collecting) return VALUE_EXPRESSION
            var at = from
            if (shorthand) {
                out.span(from, from + 1, RoleCode.PUNCTUATION)
                at = from + 1
            } else {
                var nameEnd = from + 2
                while (nameEnd < to && text[nameEnd] != ':' && text[nameEnd] != '.') nameEnd += 1
                val control =
                    LexerScan.equals(text, from, nameEnd, "v-if") || LexerScan.equals(text, from, nameEnd, "v-else-if") ||
                        LexerScan.equals(text, from, nameEnd, "v-else") || LexerScan.equals(text, from, nameEnd, "v-for")
                out.span(from, nameEnd, if (control) RoleCode.KEYWORD else RoleCode.ATTRIBUTE)
                at = nameEnd
            }
            // The argument and modifiers: separators Punctuation, names Attribute.
            while (at < to) {
                val char = text[at]
                if (char == ':' || char == '.' || char == '[' || char == ']') {
                    out.span(at, at + 1, RoleCode.PUNCTUATION)
                    at += 1
                    continue
                }
                var partEnd = at + 1
                while (partEnd < to && text[partEnd] != ':' && text[partEnd] != '.' && text[partEnd] != '[' && text[partEnd] != ']') partEnd += 1
                out.span(at, partEnd, RoleCode.ATTRIBUTE)
                at = partEnd
            }
            return VALUE_EXPRESSION
        }

        /** Colors a Svelte attribute name (a directive like `on:click|once`) and returns what its value is. */
        private fun svelteAttribute(
            from: Int,
            to: Int,
        ): Int {
            if (!out.collecting) return VALUE_PLAIN
            var colon = from
            while (colon < to && text[colon] != ':') colon += 1
            val directive = if (colon < to) svelteDirectives.indexOfFirst { LexerScan.equals(text, from, colon, it) } else -1
            if (directive < 0) {
                out.span(from, to, RoleCode.ATTRIBUTE)
                return VALUE_PLAIN
            }
            val name = svelteDirectives[directive]
            out.span(from, colon, if (name == "class" || name == "style") RoleCode.ATTRIBUTE else RoleCode.KEYWORD)
            out.span(colon, colon + 1, RoleCode.PUNCTUATION)
            var pipe = colon + 1
            while (pipe < to && text[pipe] != '|') pipe += 1
            val nameRole =
                when (name) {
                    "on" -> RoleCode.TYPE
                    "use", "transition", "in", "out", "animate" -> RoleCode.FUNCTION
                    else -> RoleCode.VARIABLE
                }
            out.span(colon + 1, pipe, nameRole)
            var at = pipe
            while (at < to) {
                out.span(at, at + 1, RoleCode.PUNCTUATION)
                var modifierEnd = at + 1
                while (modifierEnd < to && text[modifierEnd] != '|') modifierEnd += 1
                out.span(at + 1, modifierEnd, RoleCode.FUNCTION)
                at = modifierEnd
            }
            return VALUE_PLAIN
        }

        private fun valueLexer(): HighlightLexer = if (valueKind == VALUE_CSS) CssLexer else expressionLexer

        private fun tagValue() {
            val char = text[index]
            when {
                isSpace(char) -> index += 1
                char == '"' || char == '\'' ->
                    if (valueKind == VALUE_PLAIN) {
                        quotedValue(index + 1, char, open = index)
                    } else {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                        mode = if (char == '"') EMBEDDED_DOUBLE else EMBEDDED_SINGLE
                        inner = 0
                        embeddedValue(char)
                    }
                char == '>' -> {
                    mode = TAG
                    valueKind = VALUE_PLAIN
                }
                char == '{' && svelte -> openBrace(RETURN_TAG)
                else -> {
                    var at = index
                    while (at < end && !isSpace(text[at]) && text[at] != '>') at += 1
                    if (valueKind == VALUE_PLAIN) {
                        value(index, at, index, at)
                    } else {
                        // An unquoted value ends with its line, so its embedded state does not carry.
                        valueLexer().lexLine(text, index, at, 0, out)
                    }
                    index = at
                    mode = TAG
                    valueKind = VALUE_PLAIN
                }
            }
        }

        /** An expression or CSS value from [index] up to its closing [quote]. */
        private fun embeddedValue(quote: Char) {
            var close = index
            while (close < end && text[close] != quote) close += 1
            val contentEnd = if (close < end) close else end
            if (contentEnd > index) inner = valueLexer().lexLine(text, index, contentEnd, inner, out)
            if (out.stopped) return
            if (close >= end) {
                index = end
                return
            }
            out.span(close, close + 1, RoleCode.PUNCTUATION)
            index = close + 1
            inner = 0
            valueKind = VALUE_PLAIN
            mode = TAG
        }

        /** A quoted value whose text continues at [from]; [open] is its opening quote on this line, if any. */
        private fun quotedValue(
            from: Int,
            quote: Char,
            open: Int,
        ) {
            var close = from
            while (close < end && text[close] != quote && !(svelte && text[close] == '{')) close += 1
            val pending = if (quote == '"') VALUE_DOUBLE else VALUE_SINGLE
            when {
                close >= end -> {
                    value(open, end, from, end)
                    index = end
                    mode = pending
                }
                text[close] == quote -> {
                    value(open, close + 1, from, close)
                    index = close + 1
                    mode = TAG
                    valueKind = VALUE_PLAIN
                }
                else -> {
                    value(open, close, from, close)
                    index = close
                    mode = pending
                    openBrace(if (quote == '"') RETURN_DOUBLE else RETURN_SINGLE)
                }
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
            if (!out.collecting) return
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
            if (text[index] == '>') {
                out.span(index, index + 1, RoleCode.PUNCTUATION)
                mode = DATA
            }
            index += 1
        }

        // endregion

        // region Comments, declarations, raw content

        /** A comment whose text continues at [bodyFrom]; [from] is where its span starts. */
        private fun comment(
            bodyFrom: Int,
            from: Int = bodyFrom,
        ) {
            val close = find(bodyFrom, "-->")
            val to = if (close < 0) end else close + 3
            out.span(from, to, RoleCode.COMMENT)
            index = to
            if (close >= 0) mode = DATA
        }

        private fun declaration() {
            val char = text[index]
            when (char) {
                '>' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    mode = DATA
                }
                '"', '\'' -> {
                    var close = index + 1
                    while (close < end && text[close] != char) close += 1
                    val to = if (close < end) close + 1 else end
                    out.span(index, to, RoleCode.STRING)
                    index = to
                }
                else -> index += 1
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

        /** Script or style content up to its end tag, lexed by the embedded language. */
        private fun raw() {
            val closeName = if (kind == STYLE) "style" else "script"
            val close = rawEnd(closeName)
            val contentEnd = if (close < 0) end else close
            val lexer: HighlightLexer? =
                when {
                    kind == STYLE -> CssLexer
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

        // endregion
    }
}
