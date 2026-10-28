package dev.opencode.mobile.highlight.core

/**
 * One language's lexer: a state machine run one line at a time. Implementations are stateless
 * objects shared by every session and thread; everything a line needs from the lines above it is
 * the [Int] state passed in.
 *
 * Rules for implementations:
 * - Read each character a bounded number of times; no regular expressions, no backtracking.
 * - Allocate nothing per character or per token.
 * - Emit spans through [SpanSink.span] in increasing order within [start, end).
 * - Stop the main loop as soon as [SpanSink.stopped] turns true; the sink sets it while a call
 *   is being cancelled or its deadline passes, and the session discards the line's end state.
 * - State 0 is the state at the top of a document. Keep states small and canonical: equal
 *   states must mean equal lexing of everything after, because an edit stops re-lexing at the
 *   first line whose end state matches its old one.
 * - A lexer that embeds another language (HTML `<script>`, Markdown fences) calls the other
 *   lexer's [lexLine] on the stretch of the line it hands over, and keeps both states in one
 *   [Int] through [SpanSink.embedded]. So any lexer may be handed part of a line, starting or
 *   ending mid-line; it lexes that stretch as if it were a whole line.
 */
internal abstract class HighlightLexer {
    /**
     * Lexes `text[start, end)`, one line without its terminator (and cut at the line-length
     * cap) or the stretch of one an embedding lexer hands over, starting in [state]. Returns
     * the state at [end].
     */
    abstract fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int
}

/**
 * Collects one call's spans into a growable packed int buffer, merging touching spans of the
 * same role, and polls cancellation and the deadline every [CHECK_INTERVAL] spans so a long line
 * can stop early. A state-only pass reuses it with [collecting] false.
 */
internal class SpanSink(
    private val cancel: HighlightCancellation,
    private val deadline: HighlightDeadline,
    /** The session's composite states, for lexers that embed another language. */
    val embedded: EmbeddedStates = EmbeddedStates(),
) {
    private var ints = IntArray(INITIAL_CAPACITY)
    var count: Int = 0
        private set

    /** False during a state-only pass: spans are counted for polling but not stored. */
    var collecting: Boolean = false

    /** True once the call must stop; lexers end their loop when they see it. */
    var stopped: Boolean = false
        private set

    var stoppedByCancel: Boolean = false
        private set

    /** The deadline is only honored once the call finished a line, so every call makes progress. */
    var deadlineArmed: Boolean = false

    /** The furthest offset any span reached on the current line, emitted or not. */
    var reached: Int = 0
        private set

    private var lineStart = 0
    private var lineEnd = 0
    private var ticks = 0

    fun beginLine(
        start: Int,
        end: Int,
    ) {
        lineStart = start
        lineEnd = end
        reached = start
    }

    fun span(
        start: Int,
        end: Int,
        role: Int,
    ) {
        if (++ticks >= CHECK_INTERVAL) {
            ticks = 0
            poll()
        }
        // Clamp defensively so a lexer bug can never produce an out-of-bounds or overlapping span.
        val from = if (start < reached) reached else start
        val to = if (end > lineEnd) lineEnd else end
        if (to <= from) return
        reached = to
        if (!collecting) return
        val last = (count - 1) * 3
        if (count > 0 && ints[last + 2] == role && ints[last] + ints[last + 1] == from) {
            ints[last + 1] = to - ints[last]
            return
        }
        if ((count + 1) * 3 > ints.size) ints = ints.copyOf(ints.size * 2)
        val at = count * 3
        ints[at] = from
        ints[at + 1] = to - from
        ints[at + 2] = role
        count += 1
    }

    /** Checks cancellation and, once armed, the deadline; returns true when the call must stop. */
    fun poll(): Boolean {
        if (stopped) return true
        if (cancel.isCancelled) {
            stopped = true
            stoppedByCancel = true
        } else if (deadlineArmed && deadline.hasPassed) {
            stopped = true
        }
        return stopped
    }

    fun spans(): HighlightSpans = if (count == 0) HighlightSpans.empty else HighlightSpans(ints, count)

    private companion object {
        const val INITIAL_CAPACITY = 3 * 256
        const val CHECK_INTERVAL = 1_024
    }
}
