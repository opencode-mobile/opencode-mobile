package dev.opencode.mobile.highlight.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * HTML, XML, CSS, PHP, and Markdown through the session: the shared properties on seeded input,
 * streaming across embedded stretches, edits that open and close `<script>`, PHP islands, and
 * fences (re-lexing still stops where states match), and bounded work on hostile lines.
 */
class WebSessionTest {
    private val never = HighlightCancellation.Never
    private val noDeadline = HighlightDeadline.never

    @Test
    fun propertiesHoldForSeededInputs() {
        val random = Random(8721)
        repeat(600) { round ->
            val language = WebGenerators.languages[round % WebGenerators.languages.size]
            val text = HighlightGenerators.text(language, random, random.nextInt(0, 600))
            val limits = if (round % 7 == 0) HighlightLimits(maxLineLength = random.nextInt(1, 30)) else HighlightLimits.standard
            HighlightPropertyChecks.checkAll(language, text, random, limits)
        }
    }

    @Test
    fun aliasesResolveToTheWebLanguages() {
        assertEquals("html", HighlightCore.canonicalLanguage("HTM"))
        assertEquals("xml", HighlightCore.canonicalLanguage("svg"))
        assertEquals("xml", HighlightCore.canonicalLanguage("plist"))
        assertEquals("css", HighlightCore.canonicalLanguage("scss"))
        assertEquals("php", HighlightCore.canonicalLanguage("PHP"))
        assertEquals("markdown", HighlightCore.canonicalLanguage("md"))
    }

    @Test
    fun streamingEmbeddedStretchesOneCharacterAtATimeMatchesOnePass() {
        val inputs =
            mapOf(
                "html" to "<p class=\"a\">x</p>\n<script type=\"module\">\nconst t = `a\n\${b}`; /* c\n*/\n</script>\n<style>\na { color: red; }\n</style>\n",
                "php" to "<ul>\n<?php foreach (\$a as \$b): ?>\n<li><?= \"x \$b\n y\" ?></li>\n<?php endforeach; ?>\n<script>var x = <?= 1 ?>;\n</script>\n",
                "markdown" to "# T\n\n```html\n<script>\nlet a = `x\ny`;\n</script>\n```\n\n````md\n```json\n{\"a\": 1}\n```\n````\ndone *x*\n",
                "css" to ".a {\n  color: red; /* x\n  y */\n  margin:\n    0;\n}\n",
                "xml" to "<a b=\"x\ny\"><![CDATA[\nz]]><!--\n--></a>\n",
            )
        for ((language, text) in inputs) {
            val expected = oneShot(language, text)
            val session = HighlightSession(language)
            var spans = session.open("", Int.MAX_VALUE, never, noDeadline).spans.toIntArray()
            for (char in text) {
                val before = session.lineCount
                val result = session.append(char.toString(), never, noDeadline)
                assertEquals(HighlightStatus.Complete, result.status)
                assertTrue(result.startLine >= before - 1)
                spans = mergeEdit(session, spans, result, 1)
            }
            assertContentEquals(expected, spans, "$language streamed ${describe(spans)}\none-shot ${describe(expected)}")
        }
    }

    @Test
    fun openingAndClosingAScriptReLexesOnlyWhatChanged() {
        checkOpenAndClose("html", List(1_000) { "<p class=\"c$it\">text $it &amp; more</p>" }, open = "<script>", close = "</script>")
    }

    @Test
    fun openingAndClosingAPhpIslandReLexesOnlyWhatChanged() {
        checkOpenAndClose("php", List(1_000) { "<p class=\"c$it\">text $it</p>" }, open = "<?php", close = "?>")
    }

    @Test
    fun openingAndClosingAFenceReLexesOnlyWhatChanged() {
        checkOpenAndClose("markdown", List(1_000) { "Line $it with *emphasis* and `code`." }, open = "```js", close = "```", closeOnOwnLine = true)
    }

    /**
     * Opening [open] at line 400 changes every line below; closing it a few lines down changes
     * every line below back; typing inside the embedded stretch, and a closed stretch inserted
     * above, stop at the edited lines because the end states match again.
     */
    private fun checkOpenAndClose(
        language: String,
        lines: List<String>,
        open: String,
        close: String,
        closeOnOwnLine: Boolean = false,
    ) {
        val session = HighlightSession(language)
        var spans = session.open(lines.joinToString("\n"), Int.MAX_VALUE, never, noDeadline).spans.toIntArray()

        val at = session.lineStart(400)
        val opened = session.replace(at, at, "$open\n", never, noDeadline)
        assertEquals(400, opened.startLine)
        assertEquals(session.lineCount, opened.endLine)
        spans = mergeEdit(session, spans, opened, open.length + 1)
        assertContentEquals(oneShot(language, session.text()), spans, "$language after opening")

        val closeAt = session.lineEnd(403)
        val closeText = if (closeOnOwnLine) "\n$close" else close
        val closed = session.replace(closeAt, closeAt, closeText, never, noDeadline)
        assertEquals(403, closed.startLine)
        assertEquals(session.lineCount, closed.endLine)
        spans = mergeEdit(session, spans, closed, closeText.length)
        assertContentEquals(oneShot(language, session.text()), spans, "$language after closing")

        val inside = session.lineStart(402) + 1
        val typed = session.replace(inside, inside, "x1 ", never, noDeadline)
        assertEquals(402, typed.startLine)
        assertEquals(403, typed.endLine)
        spans = mergeEdit(session, spans, typed, 3)
        assertContentEquals(oneShot(language, session.text()), spans, "$language after typing inside")

        val above = session.lineStart(100)
        val block = "$open\nlet y = 2;\n$close\n"
        val inserted = session.replace(above, above, block, never, noDeadline)
        assertEquals(100, inserted.startLine)
        // The three new lines and the edit's last line, which ends as before.
        assertEquals(104, inserted.endLine)
        spans = mergeEdit(session, spans, inserted, block.length)
        assertContentEquals(oneShot(language, session.text()), spans, "$language after a closed block above")
    }

    @Test
    fun aLineFarBelowAnOpenScriptIsScriptAfterAStatePass() {
        val text = "<script>\n" + List(20_000) { "let v$it = 'x';" }.joinToString("\n") + "\n</script>\n<p>end</p>"
        val session = HighlightSession("html")
        session.open(text, 0, never, noDeadline)
        val line = session.lineCount - 3
        val result = session.highlight(line, line + 1, never, noDeadline)
        assertEquals(HighlightStatus.Complete, result.status)
        val from = session.lineStart(line)
        val shifted = result.spans.toIntArray().also { ints -> for (index in ints.indices step 3) ints[index] -= from }
        assertEquals(
            "«Keyword|let» «Variable|v19999» «Operator|=» «String|'x'»«Punctuation|;»",
            markup(session.text().substring(from, session.lineEnd(line)), HighlightSpans(shifted, result.spans.count)),
        )
    }

    @Test
    fun deeplyNestedMarkdownFencesStayBoundedAndPlainPastTheDepthCap() {
        val text =
            buildString {
                for (level in 0 until 5_000) append(if (level % 2 == 0) "```md\n" else "~~~md\n")
                append("# deep\n")
            }
        val mark = TimeSource.Monotonic.markNow()
        val result = HighlightCore.highlight("markdown", text, never, HighlightDeadline.after(5_000.0))
        assertEquals(HighlightStatus.Complete, result.status)
        assertSpansValid(text, result.spans, HighlightLimits.standard)
        assertTrue(mark.elapsedNow() < 5.seconds, "took ${mark.elapsedNow()}")
        // Past the cap the innermost fence is plain, so the last line has no heading spans.
        val lastLine = text.length - "# deep\n".length
        assertTrue((0 until result.spans.count).none { result.spans.start(it) >= lastLine })
    }

    @Test
    fun hostileLongLinesFinishFast() {
        val limits = HighlightLimits(maxLineLength = 200_000)
        val inputs =
            listOf(
                "markdown" to "*a ".repeat(50_000),
                "markdown" to "_a ".repeat(50_000),
                "markdown" to "[".repeat(100_000) + "]",
                "markdown" to "[a](".repeat(30_000),
                "markdown" to "<a ".repeat(40_000) + ">",
                "markdown" to "<!--".repeat(30_000),
                "markdown" to buildString { var n = 1; while (length < 100_000) { append("`".repeat(n)).append('x'); n = n % 400 + 1 } },
                "markdown" to "~~a ".repeat(30_000),
                "html" to "<div class=\"a\">x</div>".repeat(6_000),
                "html" to "<a ".repeat(40_000),
                "html" to "<script>" + "x".repeat(100_000),
                "html" to "&amp;".repeat(30_000),
                "xml" to "<![CDATA[".repeat(15_000),
                "css" to "a{b:c}".repeat(20_000),
                "css" to "a:hover,".repeat(20_000),
                "css" to "\"x".repeat(50_000),
                "php" to "<?php " + "\"{\$a[\$b]} ".repeat(15_000),
                "php" to "<?php " + "<<<A ".repeat(20_000),
                "php" to "<?php " + "\$a->b::c(".repeat(15_000),
                "php" to "<?p".repeat(40_000),
            )
        for ((language, text) in inputs) {
            val mark = TimeSource.Monotonic.markNow()
            val result = HighlightCore.highlight(language, text, never, HighlightDeadline.after(2_000.0), limits)
            assertEquals(HighlightStatus.Complete, result.status, "$language ${text.take(12)}")
            assertSpansValid(text, result.spans, limits)
            assertTrue(mark.elapsedNow() < 2.seconds, "$language ${text.take(12)} took ${mark.elapsedNow()}")
        }
    }

    @Test
    fun aCompositeStateIsInternedOncePerDistinctPair() {
        // Every line of a template literal ends inside it: one pair for the whole stretch.
        val text = "<script>\nconst t = `\n" + List(5_000) { "line $it" }.joinToString("\n") + "\n`;\n</script>"
        val sink = SpanSink(never, noDeadline)
        var state = 0
        var start = 0
        val chars = text.toCharArray()
        for (line in text.split('\n')) {
            state = HtmlLexer.lexLine(chars, start, start + line.length, state, sink)
            start += line.length + 1
        }
        assertEquals(0, state)
        assertEquals(1, sink.embedded.size)
    }
}
