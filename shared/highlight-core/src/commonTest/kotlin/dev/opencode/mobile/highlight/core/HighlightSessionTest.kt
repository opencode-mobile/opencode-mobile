package dev.opencode.mobile.highlight.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HighlightSessionTest {
    private val never = HighlightCancellation.Never
    private val noDeadline = HighlightDeadline.never

    @Test
    fun propertiesHoldForSeededInputs() {
        val random = Random(872)
        repeat(300) { round ->
            val language = HighlightGenerators.languages[round % HighlightGenerators.languages.size]
            val text = HighlightGenerators.text(language, random, random.nextInt(0, 400))
            val limits = if (round % 7 == 0) HighlightLimits(maxLineLength = random.nextInt(1, 30)) else HighlightLimits.standard
            HighlightPropertyChecks.checkAll(language, text, random, limits)
        }
    }

    @Test
    fun roleCodesMatchTheEnum() {
        assertEquals(HighlightRole.Comment.code, RoleCode.COMMENT)
        assertEquals(HighlightRole.Property.code, RoleCode.PROPERTY)
        assertEquals(HighlightRole.Punctuation.code, RoleCode.PUNCTUATION)
        assertEquals(HighlightRole.Critical.code, RoleCode.CRITICAL)
        assertEquals(HighlightRole.Critical, HighlightRole.ofCode(17))
        assertEquals(HighlightRole.Plain, HighlightRole.ofCode(99))
    }

    @Test
    fun aliasesResolveAndUnknownLanguagesArePlain() {
        assertTrue(HighlightCore.languages.containsAll(listOf("json", "diff", "typescript", "javascript", "kotlin", "swift")))
        assertEquals("typescript", HighlightCore.canonicalLanguage("TSX"))
        assertEquals("json", HighlightCore.canonicalLanguage(" JSONC "))
        assertEquals("diff", HighlightSession("patch").language)
        assertFalse(HighlightCore.supports("brainfuck"))
        val session = HighlightSession("Brainfuck")
        assertFalse(session.isSupported)
        assertEquals("brainfuck", session.language)
        val result = session.open("++[>+<-]\n.", Int.MAX_VALUE, never, noDeadline)
        assertEquals(HighlightStatus.Complete, result.status)
        assertEquals(0, result.spans.count)
        assertEquals(2, result.highlightedEndLine)
        assertEquals(session.length, result.highlightedEnd)
    }

    @Test
    fun linesAndOffsets() {
        val session = HighlightSession("json")
        session.open("a\r\nbc\n\nd", 0, never, noDeadline)
        assertEquals(4, session.lineCount)
        assertEquals(listOf(0, 3, 6, 7), (0 until 4).map { session.lineStart(it) })
        assertEquals(listOf(1, 5, 6, 8), (0 until 4).map { session.lineEnd(it) })
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 2, 3, 3), (0..8).map { session.lineAt(it) })
        assertEquals(0, session.knownStateLines)
    }

    @Test
    fun appendReLexesFromTheLastLineOnly() {
        val session = HighlightSession("json")
        session.open("{\n  \"a\": 1,\n  \"b\": \"x", Int.MAX_VALUE, never, noDeadline)
        val result = session.append("yz\",\n  \"c\": true\n}", never, noDeadline)
        assertEquals(2, result.startLine)
        assertEquals(5, result.endLine)
        assertEquals(
            "  «Property|\"b\"»«Punctuation|:» «String|\"xyz\"»«Punctuation|,»\n  «Property|\"c\"»«Punctuation|:» «Constant|true»\n«Punctuation|}»",
            markup(session.text().substring(session.lineStart(2)), shifted(result.spans, -session.lineStart(2))),
        )
    }

    @Test
    fun aMidTextEditStopsWhereLineStatesMatchAgain() {
        val lines = List(1_000) { "  {\"k$it\": $it}," }
        val session = HighlightSession("json")
        session.open("[\n" + lines.joinToString("\n") + "\n]", Int.MAX_VALUE, never, noDeadline)
        val at = session.lineStart(500) + 4
        val edit = session.replace(at, at + 1, "key", never, noDeadline)
        assertEquals(500, edit.startLine)
        assertEquals(501, edit.endLine)

        // A closed multi-line comment converges on the edit's last line.
        val block = session.replace(session.lineStart(300), session.lineStart(300), "/* a\n b\n*/", never, noDeadline)
        assertEquals(300, block.startLine)
        assertEquals(303, block.endLine)
        assertEquals(session.lineCount, session.knownStateLines)

        // An unclosed one changes every state below it.
        val open = session.replace(session.lineStart(700), session.lineStart(700), "/*", never, noDeadline)
        assertEquals(700, open.startLine)
        assertEquals(session.lineCount, open.endLine)
        assertEquals(session.lineCount, session.knownStateLines)
    }

    @Test
    fun aLineFarDownColorsAfterAStatePassWithoutColoringAbove() {
        val text = "/* header\n*/\n" + List(20_000) { "{\"k\": $it}" }.joinToString("\n")
        val session = HighlightSession("json")
        val opened = session.open(text, 0, never, noDeadline)
        assertEquals(0, opened.spans.count)
        val last = session.lineCount - 1
        val result = session.highlight(last, last + 1, never, noDeadline)
        assertEquals(HighlightStatus.Complete, result.status)
        assertEquals(session.lineCount, session.knownStateLines)
        assertEquals("«Punctuation|{»«Property|\"k\"»«Punctuation|:» «Number|19999»«Punctuation|}»", markup(text.substring(session.lineStart(last)), shifted(result.spans, -session.lineStart(last))))
    }

    @Test
    fun aCancelledCallReturnsNoSpans() {
        val flag = HighlightCancelFlag()
        flag.cancel()
        val result = HighlightCore.highlight("json", "{\"a\": 1}\n[2]", flag, noDeadline)
        assertEquals(HighlightStatus.Cancelled, result.status)
        assertEquals(0, result.spans.count)
        assertEquals(0, result.highlightedEndLine)
    }

    @Test
    fun expiredDeadlinesReturnPartialPrefixesThatAddUpToOnePass() {
        val text = HighlightGenerators.json(Random(3), 400_000)
        val expected = oneShot("json", text)
        val session = HighlightSession("json")
        session.open(text, 0, never, noDeadline)
        val collected = ArrayList<Int>()
        var line = 0
        var calls = 0
        while (line < session.lineCount) {
            val result = session.highlight(line, session.lineCount, never, HighlightDeadline.after(0.0))
            calls += 1
            assertTrue(result.highlightedEndLine > line, "call $calls made no progress at line $line")
            val done = if (result.highlightedEndLine >= session.lineCount) Int.MAX_VALUE else session.lineStart(result.highlightedEndLine)
            assertTrue(result.highlightedEnd >= minOf(done, session.length))
            result.spans.toIntArray().toList().chunked(3).filter { it[0] < done }.forEach { collected += it }
            if (result.status == HighlightStatus.Complete) break
            assertEquals(HighlightStatus.Partial, result.status)
            line = result.highlightedEndLine
        }
        assertTrue(calls > 1, "an expired deadline should stop a 400K pass early")
        assertContentEquals(expected, collected.toIntArray())
    }

    @Test
    fun aLexerFailureIsPlainForThatRequestOnly() {
        val session = HighlightSession(ExplodingLexer, "exploding", HighlightLimits.standard)
        val failed = session.open("ok\nboom\nok", Int.MAX_VALUE, never, noDeadline)
        assertEquals(HighlightStatus.Failed, failed.status)
        assertEquals(0, failed.spans.count)
        assertTrue(failed.failure!!.contains("boom"))
        assertEquals(1, session.knownStateLines)
        val first = session.highlight(0, 1, never, noDeadline)
        assertEquals(HighlightStatus.Complete, first.status)
        assertEquals(1, first.spans.count)
    }

    @Test
    fun packedSpansCopyOut() {
        val spans = HighlightCore.highlight("diff", "+a\n-b", never, noDeadline).spans
        assertEquals(2, spans.count)
        assertEquals(6, spans.intCount)
        val ints = IntArray(8)
        spans.copyInto(ints, 2)
        assertContentEquals(intArrayOf(0, 0, 0, 2, HighlightRole.Inserted.code, 3, 2, HighlightRole.Deleted.code), ints)
        assertEquals(HighlightRole.Deleted, spans.role(1))
        assertEquals(5, spans.end(1))
    }

    private fun shifted(
        spans: HighlightSpans,
        by: Int,
    ): HighlightSpans {
        val ints = spans.toIntArray()
        for (index in ints.indices step 3) ints[index] += by
        return HighlightSpans(ints, spans.count)
    }

    private object ExplodingLexer : HighlightLexer() {
        override fun lexLine(
            text: CharArray,
            start: Int,
            end: Int,
            state: Int,
            out: SpanSink,
        ): Int {
            if (LexerScan.startsWith(text, start, end, "boom")) error("boom")
            out.span(start, end, RoleCode.STRING)
            return state
        }
    }
}
