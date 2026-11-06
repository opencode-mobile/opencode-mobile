package dev.opencode.hexcode.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.BreakIterator
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One line of the summary's plan. */
@Serializable
data class SummaryStep(
    val text: String,
    val done: Boolean,
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
     * The reply in the card's shape, clipped to fit: [title] 40, [now] 300, [next] 60 and each step 40 characters
     * shown. The text keeps its inline Markdown ([SummaryText]); links in Now and Next are already their labels, and
     * with the `Link:` lines they're the card's [links].
     */
    @Serializable
    @SerialName("card")
    data class Card(
        val title: String,
        val now: String,
        /** Null when the reply had no `Next:` line. */
        val next: String?,
        val steps: List<SummaryStep>,
        /** The link tiles, each URL once; none when the reply had none. */
        val links: List<SummaryLink> = emptyList(),
    ) : StatusSummary

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
        const val TITLE_MAX = 40
        const val NOW_MAX = 300
        const val NEXT_MAX = 60
        const val FALLBACK_MAX = 300
        const val STEP_MAX = 40

        /** The prompt asks for at most this many steps; more are left out, so the progress segments fit. */
        const val STEPS_MAX = 8

        private val TITLE = Regex("""^##\s+(.*)$""")
        private val NOW = Regex("""^Now:\s*(.*)$""", RegexOption.IGNORE_CASE)
        private val NEXT = Regex("""^Next:\s*(.*)$""", RegexOption.IGNORE_CASE)
        private val STEP = Regex("""^- \[([ xX])]\s*(.*)$""")
        private val LINK = Regex("""^Link:\s*(.*)$""", RegexOption.IGNORE_CASE)
        private val SPACES = Regex("""\s+""")

        /** The long date in English, the title of a new session's card: "Thursday 5 November". */
        private val LONG_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)

        /**
         * Reads the summary's [reply]. Code fences and blank lines are ignored; the first `## ` line is the title,
         * the first `Now:` line the sentence, the first `Next:` line what follows, and `- [x]` / `- [ ]` lines the
         * steps, in order. Any other line is ignored. Without a title or a `Now:` line, the reply is a [Fallback].
         */
        fun parse(reply: String): StatusSummary {
            val lines = reply.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("```") }
            var title: String? = null
            var now: String? = null
            var next: String? = null
            val steps = mutableListOf<SummaryStep>()
            val linkLines = mutableListOf<SummaryLink>()
            for (line in lines) {
                val step = STEP.matchEntire(line)
                when {
                    TITLE.matches(line) -> title = title ?: TITLE.value(line)
                    NOW.matches(line) -> now = now ?: NOW.value(line)
                    NEXT.matches(line) -> next = next ?: NEXT.value(line)
                    LINK.matches(line) ->
                        if (linkLines.size < SummaryLinks.LINE_MAX) {
                            LINK.value(line)?.let(SummaryLinks::fromLine)?.let { linkLines += it }
                        }
                    step != null -> {
                        val text = step.groupValues[2].trim()
                        if (text.isNotEmpty()) steps += SummaryStep(SummaryText.clip(text, STEP_MAX), done = step.groupValues[1] != " ")
                    }
                }
            }
            val shownTitle = title
            val shownNow = now
            if (shownTitle == null || shownNow == null) {
                val text = lines.filterNot { LINK.matches(it) }.joinToString(" ").replace(SPACES, " ")
                return Fallback(clip(text, FALLBACK_MAX))
            }
            // Links left in Now or Next become tiles, after the Link lines; the sentence keeps their label.
            val (nowText, nowLinks) = SummaryLinks.pull(shownNow)
            val (nextText, nextLinks) = next?.let(SummaryLinks::pull) ?: (null to emptyList())
            return Card(
                title = SummaryText.clip(shownTitle, TITLE_MAX),
                now = SummaryText.clip(nowText, NOW_MAX),
                next = nextText?.let { SummaryText.clip(it, NEXT_MAX) },
                steps = steps.take(STEPS_MAX),
                links = SummaryLinks.distinct(linkLines + nowLinks + nextLinks),
            )
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
