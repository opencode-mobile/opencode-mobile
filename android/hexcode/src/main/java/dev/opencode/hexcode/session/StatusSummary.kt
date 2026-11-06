package dev.opencode.hexcode.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import java.text.BreakIterator
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Now's lines; a card saved before version 4 has Now as one string, which reads as one plain line. */
object NowLinesSerializer : JsonTransformingSerializer<List<NowLine>>(ListSerializer(NowLine.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonPrimitive && element.isString) JsonArray(listOf(JsonObject(mapOf("text" to element)))) else element
}

/** One line of Now: a paragraph, or a [bullet] of a list (its marker removed). */
@Serializable
data class NowLine(
    val text: String,
    val bullet: Boolean = false,
)

/**
 * What the home screen's status card shows (app-plan.md, Today's session on screen): a [Card] read from the
 * summary's reply, the reply as plain text when it isn't in the card's shape ([Fallback]), a new session's card
 * ([Empty]), which needs no request, or, with no card yet, [Working] for an empty reply while the agent works. Cards are
 * serializable so the last one can be kept on the phone ([SavedSummary]).
 */
@Serializable
sealed interface StatusSummary {
    /**
     * The reply in the card's shape (app-plan.md, Clipping): nothing is cut by characters; [now] keeps at most 5 lines
     * and 4 bullets, and the card's fit makes it all fit. The text keeps its inline Markdown ([SummaryText]);
     * links in Now and Next are already their labels, and with the `Link:` lines they're the card's [links].
     */
    @Serializable
    @SerialName("card")
    data class Card(
        val title: String,
        /** Now's lines in order: plain lines are paragraphs, bullets a list. */
        @Serializable(with = NowLinesSerializer::class)
        val now: List<NowLine>,
        /** Null when the reply had no `Next:` line, or said there's none. */
        val next: String?,
        /** The link tiles, each URL once; none when the reply had none. */
        val links: List<SummaryLink> = emptyList(),
    ) : StatusSummary {
        /** Now's lines as one text, a line break between lines (for tests and logs). */
        val nowText: String get() = now.joinToString("\n") { it.text }
    }

    /** A reply that isn't in the card's shape, as one line of plain text, at most 300 characters. */
    @Serializable
    @SerialName("fallback")
    data class Fallback(
        val text: String,
    ) : StatusSummary

    /** A session with nothing in it yet: [title] is the long date; Now and Next are the screen's own copy. */
    @Serializable
    @SerialName("empty")
    data class Empty(
        val title: String,
    ) : StatusSummary

    /**
     * No card yet, and the summary came back empty because the agent is working: [title] is the long date; Now ("The
     * agent is working.") and Next ("The summary updates when this run ends.") are the screen's own copy.
     */
    @Serializable
    @SerialName("working")
    data class Working(
        val title: String,
    ) : StatusSummary

    companion object {
        const val FALLBACK_MAX = 300

        private val TITLE = Regex("""^##\s+(.*)$""")
        private val NOW = Regex("""^Now:\s*(.*)$""", RegexOption.IGNORE_CASE)
        private val NEXT = Regex("""^Next:\s*(.*)$""", RegexOption.IGNORE_CASE)
        private val LINK = Regex("""^Link:\s*(.*)$""", RegexOption.IGNORE_CASE)
        private val BULLET = Regex("""^(?:[-*•]|\d+[.)])\s+(.*)$""")
        private val NONE = Regex("""^(?:none|n/a|-|—|\(none\))?\.?$""", RegexOption.IGNORE_CASE)

        /** Now's limits: lines and bullets. */
        const val NOW_LINES_MAX = 5
        const val BULLETS_MAX = 4
        private val SPACES = Regex("""\s+""")

        /** The long date in English, the title of a new session's card: "Thursday 5 November". */
        private val LONG_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)

        /**
         * Reads the summary's [reply] (app-plan.md, Reading the reply). Code fences and blank lines are ignored. The
         * first `## ` line is the title. The first `Now:` line and the lines after it, up to the next `Next:`,
         * `Link:` or `## ` line, are Now: a line starting `- `, `* `, `• `, `1. ` or `1) ` is a bullet, any other a
         * plain line. The first `Next:` line is the follow-up, unless it says there's none. `Link:` lines are links.
         * Any other line, such as chatter after the card, is ignored. Without a title or Now, it's a [Fallback].
         */
        fun parse(reply: String): StatusSummary {
            val lines = reply.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("```") }
            var title: String? = null
            var now: MutableList<NowLine>? = null
            var inNow = false
            var nextSeen = false
            var next: String? = null
            val linkLines = mutableListOf<SummaryLink>()
            for (line in lines) {
                // A later `Now:` line ends Now too, and is ignored: the first of each label wins.
                val label = TITLE.matches(line) || NOW.matches(line) || NEXT.matches(line) || LINK.matches(line)
                if (inNow && !label) {
                    now?.add(nowLine(line))
                    continue
                }
                inNow = false
                when {
                    TITLE.matches(line) -> title = title ?: TITLE.value(line)
                    NOW.matches(line) && now == null -> {
                        now = mutableListOf()
                        NOW.value(line)?.let { now.add(nowLine(it)) }
                        inNow = true
                    }
                    NEXT.matches(line) && !nextSeen -> {
                        nextSeen = true
                        next = NEXT.value(line)?.takeUnless { NONE.matches(it) }
                    }
                    LINK.matches(line) ->
                        if (linkLines.size < SummaryLinks.LINE_MAX) {
                            LINK.value(line)?.let(SummaryLinks::fromLine)?.let { linkLines += it }
                        }
                }
            }
            val shownTitle = title
            val shownNow = now?.filter { it.text.isNotEmpty() }
            if (shownTitle == null || shownNow.isNullOrEmpty()) {
                val text = lines.filterNot { LINK.matches(it) }.joinToString(" ").replace(SPACES, " ")
                return Fallback(clip(text, FALLBACK_MAX))
            }
            // Links left in Now or Next become tiles, after the Link lines; the text keeps their label.
            val nowLinks = mutableListOf<SummaryLink>()
            val pulled =
                shownNow.map { line ->
                    val (text, found) = SummaryLinks.pull(line.text)
                    nowLinks += found
                    line.copy(text = text)
                }
            val (nextText, nextLinks) = next?.let(SummaryLinks::pull) ?: (null to emptyList())
            return Card(
                title = shownTitle,
                now = clipNow(pulled),
                next = nextText,
                links = SummaryLinks.distinct(linkLines + nowLinks + nextLinks),
            )
        }

        /** One line of Now: a bullet without its marker, or a plain line. */
        private fun nowLine(line: String): NowLine {
            val bullet = BULLET.matchEntire(line)
            return if (bullet != null) NowLine(bullet.groupValues[1].trim(), bullet = true) else NowLine(line)
        }

        /**
         * Now's limits (app-plan.md, Clipping): at most [NOW_LINES_MAX] lines and [BULLETS_MAX] bullets, extra ones
         * dropped. Now isn't cut by characters: the prompt keeps it short, and the card's fit makes it fit.
         */
        fun clipNow(lines: List<NowLine>): List<NowLine> {
            val kept = mutableListOf<NowLine>()
            var bullets = 0
            for (line in lines) {
                if (kept.size == NOW_LINES_MAX) break
                if (line.bullet && bullets == BULLETS_MAX) continue
                if (line.bullet) bullets += 1
                kept += line
            }
            return kept
        }

        /** The text after a line's label, or null when there is none. */
        private fun Regex.value(line: String): String? =
            matchEntire(line)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.ifEmpty { null }

        /** A new session's card, titled with [date] in full. */
        fun empty(date: LocalDate): Empty = Empty(longDate(date))

        /** The card for an empty reply while the agent works, before any other card, titled with [date] in full. */
        fun working(date: LocalDate): Working = Working(longDate(date))

        /** "Thursday 5 November". */
        fun longDate(date: LocalDate): String = LONG_DATE.format(date)

        /**
         * [text] cut to at most [max] characters, as the prototype's `clip`: at the last space past half way when
         * there is one, without trailing spaces or `,.;:`, and ending in "…". A character is what the user sees as one
         * (a grapheme cluster), so an emoji or an accented letter is never split.
         */
        fun clip(
            text: String,
            max: Int,
        ): String {
            val graphemes = graphemes(text)
            if (graphemes.size <= max) return text
            var cut = graphemes.take(max - 1)
            val space = cut.lastIndexOf(" ")
            if (space > max * 0.5) cut = cut.take(space)
            cut = cut.dropLastWhile { it.isBlank() || it in TRAILING }
            return cut.joinToString("") + "…"
        }

        /** How many characters [text] has as the user sees them (grapheme clusters), as [clip] counts. */
        fun length(text: String): Int = graphemes(text).size

        private val TRAILING = setOf(",", ".", ";", ":")

        /** [text] split into what the user sees as single characters. */
        private fun graphemes(text: String): List<String> {
            val breaks = BreakIterator.getCharacterInstance(Locale.ROOT)
            breaks.setText(text)
            val parts = mutableListOf<String>()
            var start = breaks.first()
            var end = breaks.next()
            while (end != BreakIterator.DONE) {
                parts += text.substring(start, end)
                start = end
                end = breaks.next()
            }
            return parts
        }
    }
}
