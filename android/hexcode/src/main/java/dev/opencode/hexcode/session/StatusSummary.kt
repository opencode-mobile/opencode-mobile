package dev.opencode.hexcode.session

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One line of the summary's plan. */
data class SummaryStep(
    val text: String,
    val done: Boolean,
)

/**
 * What the home screen's status card shows (app-plan.md, Today's session on screen): a [Card] read from the
 * summary's reply, the reply as plain text when it isn't in the card's shape ([Fallback]), or a new session's card
 * ([Empty]), which needs no request.
 */
sealed interface StatusSummary {
    /** The reply in the card's shape, clipped to fit: [title] 40, [now] 90, [next] 60 characters. */
    data class Card(
        val title: String,
        val now: String,
        /** Null when the reply had no `Next:` line. */
        val next: String?,
        val steps: List<SummaryStep>,
    ) : StatusSummary

    /** A reply that isn't in the card's shape, as one line of plain text, at most 300 characters. */
    data class Fallback(
        val text: String,
    ) : StatusSummary

    /** A session with nothing in it yet: [title] is the long date; Now and Next are the screen's own copy. */
    data class Empty(
        val title: String,
    ) : StatusSummary

    companion object {
        const val TITLE_MAX = 40
        const val NOW_MAX = 90
        const val NEXT_MAX = 60
        const val FALLBACK_MAX = 300

        /** The prompt asks for at most this many steps; more are left out, so the progress segments fit. */
        const val STEPS_MAX = 8

        private val TITLE = Regex("""^##\s+(.*)$""")
        private val NOW = Regex("""^Now:\s*(.*)$""", RegexOption.IGNORE_CASE)
        private val NEXT = Regex("""^Next:\s*(.*)$""", RegexOption.IGNORE_CASE)
        private val STEP = Regex("""^- \[([ xX])]\s*(.*)$""")
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
            for (line in lines) {
                val step = STEP.matchEntire(line)
                when {
                    TITLE.matches(line) -> title = title ?: TITLE.value(line)
                    NOW.matches(line) -> now = now ?: NOW.value(line)
                    NEXT.matches(line) -> next = next ?: NEXT.value(line)
                    step != null -> {
                        val text = step.groupValues[2].trim()
                        if (text.isNotEmpty()) steps += SummaryStep(text, done = step.groupValues[1] != " ")
                    }
                }
            }
            val shownTitle = title
            val shownNow = now
            if (shownTitle == null || shownNow == null) {
                return Fallback(clip(lines.joinToString(" ").replace(SPACES, " "), FALLBACK_MAX))
            }
            return Card(
                title = clip(shownTitle, TITLE_MAX),
                now = clip(shownNow, NOW_MAX),
                next = next?.let { clip(it, NEXT_MAX) },
                steps = steps.take(STEPS_MAX),
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

        /** "Thursday 5 November". */
        fun longDate(date: LocalDate): String = LONG_DATE.format(date)

        /**
         * [text] cut to at most [max] characters, as the prototype's `clip`: at the last space past half way when
         * there is one, without trailing spaces or `,.;:`, and ending in "…".
         */
        fun clip(
            text: String,
            max: Int,
        ): String {
            if (text.length <= max) return text
            val cut = text.substring(0, max - 1)
            val space = cut.lastIndexOf(' ')
            val kept = if (space > max * 0.5) cut.substring(0, space) else cut
            return kept.trimEnd { it.isWhitespace() || it in ",.;:" } + "…"
        }
    }
}
