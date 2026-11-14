package dev.opencode.hexcode.session

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
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
 * summary's reply, a reply with no shape still read as a card ([Fallback]), a new session's card
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

    /**
     * A reply with neither a title nor a `Now:` line (app-plan.md, Fallback): still a card, labelled "Status", with
     * every remaining line read as Now (bullets stay bullets, line breaks stay) and nothing cut. A card saved before
     * this has its text as one plain line.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @SerialName("fallback")
    data class Fallback(
        @JsonNames("text")
        @Serializable(with = NowLinesSerializer::class)
        val now: List<NowLine>,
        val next: String? = null,
        val links: List<SummaryLink> = emptyList(),
    ) : StatusSummary

    /**
     * A session with nothing in it yet: [title] is the long date; Now and Next are the screen's own copy. Once a
     * message went or the session runs it is [started]: Now reads "Working on your message." until the first summary
     * (app-plan.md, Empty session), never "Nothing running yet".
     */
    @Serializable
    @SerialName("empty")
    data class Empty(
        val title: String,
        val started: Boolean = false,
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
        private val BULLET = Regex("""^(?:[-*+•]|\d+[.)])\s+(.*)$""")
        private val NONE = Regex("""^(?:none|n/a|-|—|\(none\))?\.?$""", RegexOption.IGNORE_CASE)

        /** Now's limits: lines and bullets. */
        const val NOW_LINES_MAX = 5
        const val BULLETS_MAX = 4

        /** The long date in English, the title of a new session's card: "Thursday 5 November". */
        private val LONG_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)

        /**
         * Reads the summary's [reply] (app-plan.md, Reading the reply), best effort: whatever parts can be recognised
         * show, and the prompt's shape is what to expect, not a test. Code fences and blank lines are ignored.
         * Labels match in any case, bold or not, followed by `:`, `-` or `—`, with variants ([labelOf]). The title is
         * the first line starting with one to three `#`, or a short bold first line, or a short first line followed
         * by a Now line. Now is the Now line's text and every other line before Next that isn't a title or a link
         * (bullets marked `- `, `* `, `+ `, `• `, `1. ` or `1) `). Lines after Next are ignored. Links come from Link
         * lines, lines that are only a Markdown link or a bare URL, and links inside the text. With no title and no Now
         * label it's a [Fallback]: every line is Now.
         */
        fun parse(reply: String): StatusSummary {
            // A line of only #s (an empty heading) says nothing.
            val lines = reply.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("```") && !it.all { c -> c == '#' } }
            var title: String? = null
            var nowLabelled = false
            val now = mutableListOf<NowLine>()
            var next: String? = null
            var nextSeen = false
            val linkLines = mutableListOf<SummaryLink>()
            val preamble = mutableListOf<NowLine>()
            lines.forEachIndexed { index, line ->
                if (nextSeen) {
                    // After Next only links still count: chatter after the card doesn't leak in.
                    lineLink(line)?.let { if (linkLines.size < SummaryLinks.LINE_MAX) linkLines += it }
                    labelOf(line)?.takeIf { it.first == Label.Link }?.second?.let(SummaryLinks::fromLine)?.let {
                        if (linkLines.size < SummaryLinks.LINE_MAX) linkLines += it
                    }
                    return@forEachIndexed
                }
                val labelled = labelOf(line)
                when {
                    title == null && titleOf(line, index, lines) != null -> {
                        title = titleOf(line, index, lines)
                    }
                    labelled?.first == Label.Now -> {
                        nowLabelled = true
                        if (labelled.second.isNotEmpty()) now += nowLine(labelled.second)
                    }
                    labelled?.first == Label.Next -> {
                        nextSeen = true
                        next = labelled.second.takeUnless { NONE.matches(it) }
                    }
                    labelled?.first == Label.Link ->
                        SummaryLinks.fromLine(labelled.second)?.let { if (linkLines.size < SummaryLinks.LINE_MAX) linkLines += it }
                    lineLink(line) != null -> lineLink(line)?.let { if (linkLines.size < SummaryLinks.LINE_MAX) linkLines += it }
                    // Anything else is kept as a line of Now rather than dropped.
                    else ->
                        nowLine(line).also {
                            now += it
                            if (title == null) preamble += it
                        }
                }
            }
            // Chatter before the title ("Here is the summary:") isn't part of the card.
            if (title != null) now.removeAll(preamble)
            // Links left in the text become tiles, after the Link lines; the text keeps their label.
            val found = mutableListOf<SummaryLink>()
            val pulled =
                now.filter { it.text.isNotEmpty() }.map { line ->
                    val (shown, links) = SummaryLinks.pull(line.text)
                    found += links
                    line.copy(text = shown)
                }
            val (nextText, nextLinks) = next?.let(SummaryLinks::pull) ?: (null to emptyList())
            val links = SummaryLinks.distinct(linkLines + found + nextLinks)
            if (title == null && !nowLabelled) {
                // No shape at all: still a card, labelled "Status", every line Now, nothing cut.
                return Fallback(pulled, nextText, links)
            }
            return Card(title = title.orEmpty(), now = clipNow(pulled), next = nextText, links = links)
        }

        /** What a label line is for. */
        private enum class Label { Now, Next, Link }

        private val LABEL =
            Regex(
                """^\*{0,2}\s*(now|status|summary|tl;dr|next steps?|next|follow-up|suggested|links?|source)\s*\*{0,2}\s*(?::|[-—](?=\s|$))\s*\*{0,2}\s*(.*)$""",
                RegexOption.IGNORE_CASE,
            )

        /** [line] as a label and the text after it, in any case, bold or not, with `:`, `-` or `—` after it. */
        private fun labelOf(line: String): Pair<Label, String>? {
            val match = LABEL.matchEntire(line) ?: return null
            val kind =
                when (match.groupValues[1].lowercase()) {
                    "now", "status", "summary", "tl;dr" -> Label.Now
                    "link", "links", "source" -> Label.Link
                    else -> Label.Next
                }
            return kind to
                match.groupValues[2]
                    .trim()
                    .removeSuffix("**")
                    .trim()
        }

        private val HEADING = Regex("""^#{1,3}\s+(.*)$""")
        private val BOLD_LINE = Regex("""^\*\*(.+)\*\*$""")

        /** Title lines are at most this long when they aren't a heading. */
        private const val SHORT_TITLE = 60

        /**
         * [line]'s title, if it's one: a heading of one to three `#`, a short first line in bold, or a short first line
         * followed by a Now line.
         */
        private fun titleOf(
            line: String,
            index: Int,
            lines: List<String>,
        ): String? {
            HEADING.matchEntire(line)?.let { return it.groupValues[1].trim().ifEmpty { null } }
            if (index != 0 || length(line) > SHORT_TITLE + 4 || labelOf(line) != null) return null
            BOLD_LINE.matchEntire(line)?.let { if (length(it.groupValues[1]) <= SHORT_TITLE) return it.groupValues[1].trim() }
            val following = lines.getOrNull(1)?.let(::labelOf)
            if (following?.first == Label.Now && length(line) <= SHORT_TITLE && BULLET.matchEntire(line) == null) return line
            return null
        }

        private val MARKDOWN_LINK_LINE = Regex("""^!?\[([^\]]+)]\((https?://[^)\s]+)\)\.?$""")
        private val URL_LINE = Regex("""^<?(https?://\S+?)>?\.?$""")

        /** A line that is only a Markdown link or a bare URL: a link. */
        private fun lineLink(line: String): SummaryLink? {
            val text =
                BULLET
                    .matchEntire(line)
                    ?.groupValues
                    ?.get(1)
                    ?.trim() ?: line
            MARKDOWN_LINK_LINE.matchEntire(text)?.let { return SummaryLink(it.groupValues[1], it.groupValues[2]).unlessArtifact() }
            URL_LINE.matchEntire(text)?.let { return SummaryLink(SummaryLinks.host(it.groupValues[1]), it.groupValues[1]).unlessArtifact() }
            return null
        }

        /** An artifact's link is dropped: the artifact tile covers it (app-plan.md, Artifacts (M8) › Links). */
        private fun SummaryLink.unlessArtifact(): SummaryLink? = takeUnless { SummaryLinks.isArtifact(url) }

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
