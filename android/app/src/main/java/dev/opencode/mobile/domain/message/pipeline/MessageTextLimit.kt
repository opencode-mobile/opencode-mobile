package dev.opencode.mobile.domain.message

import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.markdown.OpenCodeMarkdownDetector
import dev.opencode.mobile.markdown.OpenCodeMarkdownParser
import dev.opencode.mobile.markdown.OpenCodeStructureOnlyHighlighter
import java.util.regex.Pattern

/** The part of a long message the timeline shows, and how many of its characters that is. */
data class MessageTextCut(
    /** The shown prefix; ends with "…" when a line was cut part way. */
    val visibleText: String,
    /**
     * Characters of the message up to the end of [visibleText], without the "…". Leading blank
     * lines the shown part skips count as shown.
     */
    val shownCharacters: Int,
    /** Whether the last shown line was cut part way, so [visibleText] ends with the cut's "…". */
    val endsMidLine: Boolean = false,
) {
    /**
     * [visibleText] as markdown detection and parsing read it: a cut line has the inline markers
     * the cut left open closed before its "…", so it renders as the whole message does up to the
     * cut (#1043). The plain shown text, Copy message, Select text and Full message keep
     * [visibleText] and the whole text.
     */
    val markdownSource: String
        get() =
            if (endsMidLine) {
                OpenCodeMarkdownParser.closeMarkersLeftOpen(visibleText.dropLast(1)) + "…"
            } else {
                visibleText
            }
}

/**
 * How much of a user's own message the timeline lays out (#1043).
 *
 * Counted from the text alone, with no layout: a line break is one line, and every
 * [CHARACTERS_PER_LINE] characters of a line are one more, so a line costs
 * `max(1, ceil(length / CHARACTERS_PER_LINE))`. A message costing at most [TOLERANCE] shows in
 * full; a longer one shows the prefix costing at most [LIMIT]. iOS has the same rule, constants
 * and test vectors.
 *
 * Characters are UTF-16 units on both platforms, so the same text is cut at the same place and
 * shows the same counts. A UTF-16 unit is what a Kotlin `String` and a Swift `String.utf16`
 * view read one at a time without decoding, so the pass stays cheap and stops early; grapheme
 * clusters would need segmenting every character read. A cut never ends inside a grapheme
 * cluster (an emoji sequence, a flag, a letter and its combining marks): only the cut line up to
 * the cut is segmented, with the platform's own rules (`\X` here, `Character` on iOS).
 */
object MessageTextLimit {
    const val CHARACTERS_PER_LINE = 40
    const val LIMIT = 25
    const val TOLERANCE = 30

    /** A word boundary closer than this to the start of the cut line is not used. */
    private const val MINIMUM_WORD_CUT = CHARACTERS_PER_LINE / 2

    /**
     * How far past the decision pass the search for the first line with content reads. A text
     * with no content that soon (a multi-MB blank paste) keeps its blank shown part rather than
     * being read in full; the whole of it is still behind Show full message.
     */
    internal const val MAXIMUM_BLANK_SKIP = 64 * 1024

    /**
     * The shown prefix of [text], or null when it shows in full.
     *
     * One pass that stops as soon as the message is known to be over [TOLERANCE]: it reads at
     * most about `TOLERANCE * CHARACTERS_PER_LINE` characters plus [TOLERANCE] line breaks,
     * whatever the length of the text. Leading blank lines count toward that decision, but the
     * shown part starts at the first line with content, so a paste that opens with many blank
     * lines never shows an empty bubble; [MessageTextCut.shownCharacters] counts the skipped
     * blank lines as shown.
     */
    fun cut(text: CharSequence): MessageTextCut? {
        val first = scan(text, start = 0, forced = false) ?: return null
        val contentStart = if (first.contentLineStart >= 0) first.contentLineStart else contentLineStart(text, first.read)
        if (contentStart <= 0 || contentStart >= text.length) return first.cut
        return scan(text, start = contentStart, forced = true)?.cut ?: first.cut
    }

    private class Scan(
        val cut: MessageTextCut,
        /** Start of the first line with a non-blank character among those read, or -1. */
        val contentLineStart: Int,
        /** Where the pass stopped reading. */
        val read: Int,
    )

    /**
     * Counts [text] from [start]. Null when it fits [TOLERANCE], unless [forced]: then the part
     * costing at most [LIMIT] is returned even when the rest would fit.
     */
    private fun scan(
        text: CharSequence,
        start: Int,
        forced: Boolean,
    ): Scan? {
        val length = text.length
        var read = start
        var used = 0
        var lineStart = start
        var lineLength = 0
        var wholeEnd = start
        var overflowStart = -1
        var overflowBudget = 0
        var isOver = false
        var contentLineStart = -1

        fun lineCost(characters: Int): Int = maxOf(1, (characters + CHARACTERS_PER_LINE - 1) / CHARACTERS_PER_LINE)

        // Records the line that crosses LIMIT: the cut keeps what fits of it.
        fun markOverflowIfNeeded(cost: Int) {
            if (overflowStart >= 0) return
            if (used + cost <= LIMIT) {
                wholeEnd = read
            } else {
                overflowStart = lineStart
                overflowBudget = LIMIT - used
            }
        }

        while (read < length) {
            val character = text[read]
            if (character == '\r' && read + 1 < length && text[read + 1] == '\n') {
                // CRLF is one line break, as on iOS where "\r\n" is one Character.
                read += 1
                continue
            }
            if (character == '\n') {
                val cost = lineCost(lineLength)
                markOverflowIfNeeded(cost)
                used += cost
                if (used > TOLERANCE) {
                    isOver = true
                    break
                }
                read += 1
                lineStart = read
                lineLength = 0
            } else {
                if (contentLineStart < 0 && !isBlank(character)) contentLineStart = lineStart
                read += 1
                lineLength += 1
                val cost = lineCost(lineLength)
                if (used + cost > TOLERANCE) {
                    markOverflowIfNeeded(cost)
                    isOver = true
                    break
                }
            }
        }
        if (!isOver) {
            val cost = lineCost(lineLength)
            markOverflowIfNeeded(cost)
            if (!forced && used + cost <= TOLERANCE) return null
        }

        var partialStart = start
        var partialEnd = start
        if (overflowStart >= 0 && overflowBudget > 0) {
            partialStart = overflowStart
            val end = overflowStart + overflowBudget * CHARACTERS_PER_LINE
            partialEnd = minOf(end, read)
            val nextIsSpace = end < read && isBlank(text[end])
            if (!nextIsSpace) {
                var space = partialEnd - 1
                while (space >= partialStart && !isBlank(text[space])) space -= 1
                if (space - partialStart > MINIMUM_WORD_CUT) partialEnd = space
            }
            partialEnd = graphemeFloor(text, partialStart, partialEnd)
            partialEnd = trimmedEnd(text, partialStart, partialEnd)
        }
        val cut =
            if (partialEnd <= partialStart) {
                val visibleEnd = trimmedEnd(text, start, wholeEnd)
                MessageTextCut(visibleText = text.substring(start, visibleEnd), shownCharacters = visibleEnd)
            } else {
                MessageTextCut(
                    visibleText = text.substring(start, partialEnd) + "…",
                    shownCharacters = partialEnd,
                    endsMidLine = true,
                )
            }
        return Scan(cut, contentLineStart, read)
    }

    /**
     * Start of the first line of [text] with a non-blank character, looking from [from] for at
     * most [MAXIMUM_BLANK_SKIP] characters; the length if none.
     */
    private fun contentLineStart(
        text: CharSequence,
        from: Int,
    ): Int {
        var lineStart = from
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart -= 1
        var index = from
        val end = minOf(text.length, from + MAXIMUM_BLANK_SKIP)
        while (index < end) {
            val character = text[index]
            if (character == '\n') {
                lineStart = index + 1
            } else if (!isBlank(character)) {
                return lineStart
            }
            index += 1
        }
        return text.length
    }

    private fun trimmedEnd(
        text: CharSequence,
        start: Int,
        end: Int,
    ): Int {
        var trimmed = end
        while (trimmed > start && isBlank(text[trimmed - 1])) trimmed -= 1
        return trimmed
    }

    private val graphemeCluster: Pattern = Pattern.compile("\\X")

    /**
     * The last grapheme cluster boundary at or before [end] in the line starting at [lineStart]
     * (a boundary, since a line break ends every cluster). Reads only from [lineStart] to one
     * code point past [end]: whether [end] is a boundary depends on what comes before it and the
     * code point at it.
     */
    private fun graphemeFloor(
        text: CharSequence,
        lineStart: Int,
        end: Int,
    ): Int {
        if (end <= lineStart || end >= text.length) return end
        val window = text.subSequence(lineStart, minOf(text.length, end + 2)).toString()
        val matcher = graphemeCluster.matcher(window)
        val target = end - lineStart
        var boundary = 0
        while (matcher.find()) {
            if (matcher.end() > target) break
            boundary = matcher.end()
        }
        return lineStart + boundary
    }

    /**
     * Blank characters, spelt out so iOS counts the same ones: Kotlin's `Char.isWhitespace()`
     * set (Java whitespace plus space separators).
     */
    internal fun isBlank(character: Char): Boolean =
        when (character.code) {
            in 0x09..0x0D, in 0x1C..0x20, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
            else -> false
        }
}

/**
 * The text markdown detection, parsing and highlighting read for a message: the shown part of a
 * cut user message ([MessageTextCut.markdownSource]), so none of them ever sees the hidden rest
 * (#1043); the whole text otherwise. A cut bubble then renders like an uncut one up to the cut.
 */
val Message.markdownSourceText: String
    get() = if (role == MessageRole.User) MessageTextLimit.cut(text)?.markdownSource ?: text else text

/**
 * Every markdown detection and parse in the message pipeline goes through here. It also lets a
 * test see what they are given, to check that the hidden part of a cut message never reaches
 * them (#1043). Nothing observes outside tests.
 */
internal object MarkdownInputProbe {
    enum class Stage { Detect, Parse }

    @Volatile
    var observer: ((Stage, String) -> Unit)? = null

    fun shouldRender(text: String): Boolean {
        observer?.invoke(Stage.Detect, text)
        return OpenCodeMarkdownDetector.shouldRender(text)
    }

    fun parseStructure(markdown: String): MarkdownDocument {
        observer?.invoke(Stage.Parse, markdown)
        return OpenCodeMarkdownParser.parse(markdown = markdown, codeHighlighter = OpenCodeStructureOnlyHighlighter)
    }
}
