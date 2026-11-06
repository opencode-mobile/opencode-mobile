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

    /** A single chunk too long even at 17: running text at 17 that scrolls inside the draft area, kept at its end. */
    data object Scrolling : DraftShape

    /** One row per chunk, at most 8 visible. */
    data object Rows : DraftShape

    companion object {
        /** Running text starts at `draft` up to this many characters, else at the next size down. */
        const val DRAFT_CHARACTERS = 150

        /** More chunks than this are rows whatever their length. */
        const val RUNNING_CHUNKS = 3

        /**
         * The draft's shape: rows for more than [RUNNING_CHUNKS] chunks; else running text at the largest size from
         * the start that [fits] (measured as drawn); if none does, rows for 2 or more chunks, and for a single chunk
         * running text that scrolls. A single chunk never becomes a list.
         */
        fun choose(
            chunks: Int,
            characters: Int,
            fits: (DraftSize) -> Boolean,
        ): DraftShape {
            if (chunks > RUNNING_CHUNKS) return Rows
            val start = if (characters <= DRAFT_CHARACTERS) DraftSize.Draft else DraftSize.Medium
            for (size in DraftSize.entries.drop(start.ordinal)) if (fits(size)) return Running(size)
            return if (chunks >= 2) Rows else Scrolling
        }
    }
}
