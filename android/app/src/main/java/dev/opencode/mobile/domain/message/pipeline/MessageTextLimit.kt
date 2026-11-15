package dev.opencode.mobile.domain.message

import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.markdown.OpenCodeMarkdownDetector
import dev.opencode.mobile.markdown.OpenCodeMarkdownParser
import dev.opencode.mobile.markdown.OpenCodeStructureOnlyHighlighter

/** The part of a long message the timeline shows, and how many of its characters that is. */
data class MessageTextCut(
    /** The shown prefix; ends with "…" when a line was cut part way. */
    val visibleText: String,
    /** Characters of the message kept in [visibleText], without the "…". */
    val shownCharacters: Int,
)

/**
 * How much of a user's own message the timeline lays out (#1043).
 *
 * Counted from the text alone, with no layout: a line break is one line, and every
 * [CHARACTERS_PER_LINE] characters of a line are one more, so a line costs
 * `max(1, ceil(length / CHARACTERS_PER_LINE))`. A message costing at most [TOLERANCE] shows in
 * full; a longer one shows the prefix costing at most [LIMIT]. iOS has the same rule, constants
 * and test vectors. Characters are UTF-16 units; a cut never splits a surrogate pair.
 */
object MessageTextLimit {
    const val CHARACTERS_PER_LINE = 40
    const val LIMIT = 25
    const val TOLERANCE = 30

    /** A word boundary closer than this to the start of the cut line is not used. */
    private const val MINIMUM_WORD_CUT = CHARACTERS_PER_LINE / 2

    /**
     * The shown prefix of [text], or null when it shows in full.
     *
     * One pass that stops as soon as the message is known to be over [TOLERANCE]: it reads at
     * most about `TOLERANCE * CHARACTERS_PER_LINE` characters plus [TOLERANCE] line breaks,
     * whatever the length of the text.
     */
    fun cut(text: CharSequence): MessageTextCut? {
        val length = text.length
        var read = 0
        var used = 0
        var lineStart = 0
        var lineLength = 0
        var wholeEnd = 0
        var overflowStart = -1
        var overflowBudget = 0
        var isOver = false

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
            if (used + cost <= TOLERANCE) return null
        }

        var partialStart = 0
        var partialEnd = 0
        if (overflowStart >= 0 && overflowBudget > 0) {
            partialStart = overflowStart
            val end = overflowStart + overflowBudget * CHARACTERS_PER_LINE
            partialEnd = minOf(end, read)
            val nextIsSpace = end < read && text[end].isWhitespace()
            if (!nextIsSpace) {
                var space = partialEnd - 1
                while (space >= partialStart && !text[space].isWhitespace()) space -= 1
                if (space - partialStart > MINIMUM_WORD_CUT) partialEnd = space
            }
            if (partialEnd > partialStart && partialEnd < length && Character.isHighSurrogate(text[partialEnd - 1])) {
                partialEnd -= 1
            }
            partialEnd = trimmedEnd(text, partialStart, partialEnd)
        }
        if (partialEnd <= partialStart) {
            val visibleEnd = trimmedEnd(text, 0, wholeEnd)
            return MessageTextCut(visibleText = text.substring(0, visibleEnd), shownCharacters = visibleEnd)
        }
        val visible = text.substring(0, partialStart) + text.substring(partialStart, partialEnd)
        return MessageTextCut(visibleText = "$visible…", shownCharacters = visible.length)
    }

    private fun trimmedEnd(
        text: CharSequence,
        start: Int,
        end: Int,
    ): Int {
        var trimmed = end
        while (trimmed > start && text[trimmed - 1].isWhitespace()) trimmed -= 1
        return trimmed
    }
}

/**
 * The text markdown detection, parsing and highlighting read for a message: the shown part of a
 * cut user message, so none of them ever sees the hidden rest (#1043); the whole text otherwise.
 * A cut bubble then renders like an uncut one up to the cut.
 */
val Message.markdownSourceText: String
    get() = if (role == MessageRole.User) MessageTextLimit.cut(text)?.visibleText ?: text else text

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
