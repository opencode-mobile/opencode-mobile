package dev.opencode.mobile.highlight.core

import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource

/**
 * Asks a call in progress to stop. Read between lines and within long lines, so it must be cheap
 * and safe to read from the thread running the call while another thread sets it.
 */
interface HighlightCancellation {
    val isCancelled: Boolean

    /** For calls nobody cancels. */
    object Never : HighlightCancellation {
        override val isCancelled: Boolean
            get() = false
    }
}

/** A cancellation flag that any thread may set. */
class HighlightCancelFlag : HighlightCancellation {
    @Volatile
    override var isCancelled: Boolean = false
        private set

    fun cancel() {
        isCancelled = true
    }
}

/**
 * When a call should stop and return what it has as [HighlightStatus.Partial]. Checked on a
 * monotonic clock every few lines; a call always finishes at least one line before it looks, so
 * repeated calls with short deadlines still make progress.
 */
class HighlightDeadline private constructor(
    private val mark: TimeSource.Monotonic.ValueTimeMark?,
) {
    val hasPassed: Boolean
        get() = mark != null && mark.hasPassedNow()

    companion object {
        /** No time limit. */
        val never: HighlightDeadline = HighlightDeadline(null)

        /** [milliseconds] from now; fractions are honored, for example 0.5 for 500 microseconds. */
        fun after(milliseconds: Double): HighlightDeadline = HighlightDeadline(TimeSource.Monotonic.markNow() + (milliseconds * 1_000_000).toLong().nanoseconds)
    }
}

/** Bounds every session applies, whatever the input. */
data class HighlightLimits(
    /**
     * Characters of one line a lexer reads; past this the rest of the line stays plain and the
     * next line starts from the state the lexer had there.
     */
    val maxLineLength: Int = 20_000,
) {
    init {
        require(maxLineLength > 0) { "maxLineLength must be positive." }
    }

    companion object {
        val standard: HighlightLimits = HighlightLimits()
    }
}
