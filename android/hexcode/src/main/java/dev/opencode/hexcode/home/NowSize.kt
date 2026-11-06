package dev.opencode.hexcode.home

import dev.opencode.hexcode.session.StatusSummary

/**
 * The Now sentence's type size (app-plan.md, Now size; the prototype's `nowTier` and `fitNow`): it steps down with
 * the sentence's length, then on down while the card doesn't fit between the top bar and the dock, to [Fit].
 */
enum class NowSize {
    /** Up to 90 characters: `display`. */
    Display,

    /** Up to 150: `nowLarge`. */
    Large,

    /** Up to 220: `nowMedium`. */
    Medium,

    /** Longer: `nowSmall`. */
    Small,

    /** Only when the card doesn't fit otherwise: `nowFit`. */
    Fit,
    ;

    /** The next size down, or null at [Fit]. */
    val smaller: NowSize? get() = entries.getOrNull(ordinal + 1)

    companion object {
        /** The size for a Now sentence of [characters] characters, before fitting. */
        fun forLength(characters: Int): NowSize =
            when {
                characters <= 90 -> Display
                characters <= 150 -> Large
                characters <= 220 -> Medium
                else -> Small
            }

        /** The size for [text], counted in characters as the user sees them, as clipping counts. */
        fun forText(text: String): NowSize = forLength(StatusSummary.length(text))

        /**
         * The first size from [start] down to [Fit] at which the card [fits], and whether it does; at [Fit] when none
         * does, and then the sentence ends in "…".
         */
        fun fit(
            start: NowSize,
            fits: (NowSize) -> Boolean,
        ): Fitted {
            var size = start
            while (true) {
                if (fits(size)) return Fitted(size, fits = true)
                size = size.smaller ?: return Fitted(Fit, fits = false)
            }
        }
    }

    /** What [fit] chose. */
    data class Fitted(
        val size: NowSize,
        val fits: Boolean,
    )
}
