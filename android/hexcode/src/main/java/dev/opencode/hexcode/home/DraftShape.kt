package dev.opencode.hexcode.home

/** The running text's type sizes, largest first (app-plan.md, Long drafts). */
enum class DraftSize {
    /** `draft` (24), up to 150 characters. */
    Draft,

    /** 21 (`nowMedium`'s size at the draft's weight). */
    Medium,

    /** 19 (`nowSmall`'s). */
    Small,

    /** 17 (`nowFit`'s): the smallest. */
    Fit,
}

/** How the draft shows (app-plan.md, Long drafts). */
sealed interface DraftShape {
    /** Running text at [size]. */
    data class Running(
        val size: DraftSize,
    ) : DraftShape

    /**
     * A single chunk longer than [WINDOW_LINES] lines at 21, or too long even at 17: a 7-line window at 21 resting on
     * the newest words, which scrolls back with its top edge fading, and "Read all · N words" under it.
     */
    data object Window : DraftShape

    /** One row per chunk, at most 8 visible. */
    data object Rows : DraftShape

    companion object {
        /** Running text starts at `draft` up to this many characters, else at the next size down. */
        const val DRAFT_CHARACTERS = 150

        /** More chunks than this are rows whatever their length. */
        const val RUNNING_CHUNKS = 3

        /** The long single chunk's window: this many lines at 21. */
        const val WINDOW_LINES = 7

        /**
         * The draft's shape (app-plan.md, Long drafts): rows for more than [RUNNING_CHUNKS] chunks; a single chunk
         * [beyondWindow] (longer than [WINDOW_LINES] lines at 21) is the [Window]; else running text at the largest
         * size from the start that [fits] (measured as drawn); if none does, rows for 2 or more chunks, and the
         * [Window] for a single chunk. A single chunk never becomes a list.
         */
        fun choose(
            chunks: Int,
            characters: Int,
            beyondWindow: () -> Boolean = { false },
            fits: (DraftSize) -> Boolean,
        ): DraftShape {
            if (chunks > RUNNING_CHUNKS) return Rows
            if (chunks <= 1 && characters > DRAFT_CHARACTERS && beyondWindow()) return Window
            val start = if (characters <= DRAFT_CHARACTERS) DraftSize.Draft else DraftSize.Medium
            for (size in DraftSize.entries.drop(start.ordinal)) if (fits(size)) return Running(size)
            return if (chunks >= 2) Rows else Window
        }
    }
}

/** Words as the reader counts them ("Read all · 235 words"): runs of letters between spaces. */
object Words {
    fun count(text: String): Int = text.split(Regex("""\s+""")).count { it.isNotEmpty() }
}

/** The You line after a send (app-plan.md, Just sent): the whole message up to this many lines, then "Read all". */
const val YOU_LINES = 3
