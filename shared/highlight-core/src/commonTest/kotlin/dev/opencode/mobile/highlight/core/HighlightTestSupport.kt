package dev.opencode.mobile.highlight.core

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

internal expect fun testEnvironment(name: String): String?

/**
 * Golden markup: plain text as is, each span as `«Role|text»`. A `\r` shows as `␍`, a tab as
 * `␉`, and a lone surrogate as `⟨D83D⟩`, so fixtures stay readable in raw strings.
 */
internal fun markup(
    text: String,
    spans: HighlightSpans,
): String =
    buildString {
        var at = 0
        for (index in 0 until spans.count) {
            val start = spans.start(index)
            val end = spans.end(index)
            appendVisible(text, at, start)
            append('«').append(spans.role(index).name).append('|')
            appendVisible(text, start, end)
            append('»')
            at = end
        }
        appendVisible(text, at, text.length)
    }

private fun StringBuilder.appendVisible(
    text: String,
    from: Int,
    to: Int,
) {
    var index = from
    while (index < to) {
        val char = text[index]
        when {
            char == '\r' -> append('␍')
            char == '\t' -> append('␉')
            char.isHighSurrogate() && index + 1 < to && text[index + 1].isLowSurrogate() -> {
                append(char).append(text[index + 1])
                index += 1
            }
            char.isSurrogate() -> append('⟨').append(char.code.toString(16).uppercase()).append('⟩')
            else -> append(char)
        }
        index += 1
    }
}

internal fun assertGolden(
    language: String,
    source: String,
    expected: String,
    limits: HighlightLimits = HighlightLimits.standard,
) {
    val result = HighlightCore.highlight(language, source, HighlightCancellation.Never, HighlightDeadline.never, limits)
    assertEquals(HighlightStatus.Complete, result.status, "status for $language")
    assertSpansValid(source, result.spans, limits)
    assertEquals(expected, markup(source, result.spans))
}

/** Sorted, non-overlapping, in bounds, never across a line end or past the line-length cap, and never Plain. */
internal fun assertSpansValid(
    text: String,
    spans: HighlightSpans,
    limits: HighlightLimits,
    from: Int = 0,
) {
    var previousEnd = from
    var previousRole = -1
    // Spans are sorted, so the line start is found by scanning forward once (linear on one long line).
    var scanned = 0
    var lineStart = 0
    for (index in 0 until spans.count) {
        val start = spans.start(index)
        val end = spans.end(index)
        val role = spans.roleCode(index)
        if (start < previousEnd || end <= start || end > text.length) fail("Span $index $start..<$end after $previousEnd in ${text.length}")
        if (role <= 0 || role >= HighlightRole.entries.size) fail("Span $index has role code $role")
        if (start == previousEnd && role == previousRole) fail("Span $index touches the one before with the same role")
        while (scanned < start) {
            if (text[scanned] == '\n') lineStart = scanned + 1
            scanned += 1
        }
        for (offset in start until end) {
            if (text[offset] == '\n') fail("Span $index crosses a line end at $offset")
            if (text[offset] == '\r' && offset + 1 < text.length && text[offset + 1] == '\n') fail("Span $index covers a CRLF terminator")
        }
        assertTrue(end - lineStart <= limits.maxLineLength, "Span $index ends past the line-length cap")
        previousEnd = end
        previousRole = role
    }
}

internal fun oneShot(
    language: String,
    text: String,
    limits: HighlightLimits = HighlightLimits.standard,
): IntArray {
    val result = HighlightCore.highlight(language, text, HighlightCancellation.Never, HighlightDeadline.never, limits)
    assertEquals(HighlightStatus.Complete, result.status)
    return result.spans.toIntArray()
}

/**
 * Applies an edit result to the spans the caller held before it: keeps spans before the result's
 * first line, takes the result's spans, and shifts the old spans after its last line.
 */
internal fun mergeEdit(
    session: HighlightSession,
    old: IntArray,
    result: HighlightCallResult,
    offsetShift: Int,
): IntArray {
    val changedFrom = session.lineStart(result.startLine)
    val unchangedFrom = if (result.endLine >= session.lineCount) Int.MAX_VALUE else session.lineStart(result.endLine) - offsetShift
    val merged = ArrayList<Int>(old.size + result.spans.intCount)
    var index = 0
    while (index < old.size && old[index] < changedFrom) {
        merged += old[index]
        merged += old[index + 1]
        merged += old[index + 2]
        index += 3
    }
    val fresh = result.spans.toIntArray()
    for (value in fresh) merged += value
    index = 0
    while (index < old.size) {
        if (old[index] >= unchangedFrom) {
            merged += old[index] + offsetShift
            merged += old[index + 1]
            merged += old[index + 2]
        }
        index += 3
    }
    return merged.toIntArray()
}

internal fun describe(spans: IntArray): String = spans.toList().chunked(3).joinToString(" ") { (start, length, role) -> "$start+$length:${HighlightRole.ofCode(role)}" }
