package dev.opencode.mobile.highlight.core

import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The properties every input must satisfy; shared by the property tests and the randomized run. */
internal object HighlightPropertyChecks {
    fun checkAll(
        language: String,
        text: String,
        random: Random,
        limits: HighlightLimits = HighlightLimits.standard,
    ) {
        val expected = oneShot(language, text, limits)
        val session = HighlightSession(language, limits)
        session.open(text, Int.MAX_VALUE, HighlightCancellation.Never, HighlightDeadline.never).also {
            assertSpansValid(text, it.spans, limits)
        }
        checkStreaming(language, text, expected, random, limits)
        checkEdit(language, text, random, limits)
        checkRange(language, text, expected, random, limits)
    }

    /** Appending in chunks, merging each result into the spans held so far, equals one pass. */
    fun checkStreaming(
        language: String,
        text: String,
        expected: IntArray,
        random: Random,
        limits: HighlightLimits,
    ) {
        val session = HighlightSession(language, limits)
        var spans = session.open("", Int.MAX_VALUE, HighlightCancellation.Never, HighlightDeadline.never).spans.toIntArray()
        for (chunk in HighlightGenerators.chunks(text, random)) {
            val before = session.lineCount
            val result = session.append(chunk, HighlightCancellation.Never, HighlightDeadline.never)
            assertEquals(HighlightStatus.Complete, result.status)
            assertTrue(result.startLine >= before - 1, "append re-lexed from line ${result.startLine}, text had $before lines")
            spans = mergeEdit(session, spans, result, chunk.length)
        }
        assertEquals(text, session.text())
        assertContentEquals(expected, spans, "streamed ${describe(spans)}\none-shot ${describe(expected)}")
    }

    /** An edit anywhere, merged into the old spans, equals a fresh pass over the new text. */
    fun checkEdit(
        language: String,
        text: String,
        random: Random,
        limits: HighlightLimits,
    ) {
        val session = HighlightSession(language, limits)
        val old = session.open(text, Int.MAX_VALUE, HighlightCancellation.Never, HighlightDeadline.never).spans.toIntArray()
        val start = random.nextInt(0, text.length + 1)
        val end = random.nextInt(start, minOf(text.length, start + 40) + 1)
        val insert = HighlightGenerators.text(language, random, random.nextInt(0, 30))
        val result = session.replace(start, end, insert, HighlightCancellation.Never, HighlightDeadline.never)
        assertEquals(HighlightStatus.Complete, result.status)
        val edited = text.substring(0, start) + insert + text.substring(end)
        assertEquals(edited, session.text())
        val merged = mergeEdit(session, old, result, insert.length - (end - start))
        val fresh = oneShot(language, edited, limits)
        assertContentEquals(fresh, merged, "edited ${describe(merged)}\nfresh ${describe(fresh)}")
    }

    /** A line range far down, colored after a state-only pass, equals the same lines of one pass. */
    fun checkRange(
        language: String,
        text: String,
        expected: IntArray,
        random: Random,
        limits: HighlightLimits,
    ) {
        val session = HighlightSession(language, limits)
        session.open(text, 0, HighlightCancellation.Never, HighlightDeadline.never)
        val startLine = random.nextInt(0, session.lineCount)
        val endLine = random.nextInt(startLine, session.lineCount + 1)
        if (random.nextBoolean()) session.computeStates(startLine, HighlightCancellation.Never, HighlightDeadline.never)
        val result = session.highlight(startLine, endLine, HighlightCancellation.Never, HighlightDeadline.never)
        assertEquals(HighlightStatus.Complete, result.status)
        val from = session.lineStart(startLine)
        val to = if (endLine >= session.lineCount) Int.MAX_VALUE else session.lineStart(endLine)
        val slice = expected.toList().chunked(3).filter { it[0] in from until to }.flatten().toIntArray()
        assertContentEquals(slice, result.spans.toIntArray(), "lines $startLine..<$endLine")
    }
}
