package dev.opencode.mobile.highlight.core

enum class HighlightStatus {
    /** Every line of the result's range is highlighted. */
    Complete,

    /** The deadline passed: text before [HighlightCallResult.highlightedEnd] is highlighted, the rest is plain. */
    Partial,

    /** The cancellation flag was set; the result has no spans. Line states already computed are kept. */
    Cancelled,

    /** The lexer threw; the whole range is plain for this request only. */
    Failed,
}

/**
 * Highlighted ranges packed three ints per span: `start, length, role code` at `3 * index`, in
 * UTF-16 offsets from the start of the session text. Spans are sorted, never overlap, never
 * touch with the same role, and never cover [HighlightRole.Plain].
 *
 * Swift copies all of them in one call with `writeInts(to:)` instead of reading
 * element by element through the bridge.
 */
class HighlightSpans internal constructor(
    private val ints: IntArray,
    val count: Int,
) {
    /** Ints in use: `3 * count`. */
    val intCount: Int
        get() = count * 3

    fun start(index: Int): Int = ints[checkIndex(index) * 3]

    fun length(index: Int): Int = ints[checkIndex(index) * 3 + 1]

    fun end(index: Int): Int = start(index) + length(index)

    fun roleCode(index: Int): Int = ints[checkIndex(index) * 3 + 2]

    fun role(index: Int): HighlightRole = HighlightRole.ofCode(roleCode(index))

    /** A copy of the packed ints, exactly [intCount] long. */
    fun toIntArray(): IntArray = ints.copyOf(intCount)

    /** Copies the packed ints into [destination] at [offset]; it needs room for [intCount]. */
    fun copyInto(
        destination: IntArray,
        offset: Int = 0,
    ) {
        ints.copyInto(destination, offset, 0, intCount)
    }

    internal fun rawInts(): IntArray = ints

    private fun checkIndex(index: Int): Int {
        if (index < 0 || index >= count) throw IndexOutOfBoundsException("Span $index of $count")
        return index
    }

    companion object {
        val empty: HighlightSpans = HighlightSpans(IntArray(0), 0)
    }
}

/**
 * What one session call produced. The result covers lines [startLine, endLine) of the session
 * text after the call; spans outside that range are unchanged by the call (an edit shifts the
 * offsets of the lines after it by the length it added or removed).
 */
class HighlightCallResult internal constructor(
    val status: HighlightStatus,
    val startLine: Int,
    val endLine: Int,
    /** Lines [startLine, highlightedEndLine) are fully highlighted. */
    val highlightedEndLine: Int,
    /**
     * Text from the start of [startLine] up to this UTF-16 offset is highlighted; past it the
     * range is plain. It can fall inside line [highlightedEndLine] when a deadline stops a long line.
     */
    val highlightedEnd: Int,
    val spans: HighlightSpans,
    /** Why the call failed; null unless [status] is [HighlightStatus.Failed]. */
    val failure: String?,
) {
    override fun toString(): String =
        "HighlightCallResult($status, lines $startLine..<$endLine, highlighted to line $highlightedEndLine / offset $highlightedEnd, " +
            "${spans.count} spans${failure?.let { ", $it" } ?: ""})"
}
