package dev.opencode.mobile.markdown

import dev.opencode.mobile.highlight.bench.HighlightBenchInlineGuard
import dev.opencode.mobile.highlight.bench.HighlightBenchProbe
import dev.opencode.mobile.highlight.bench.HighlightBenchSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextMateHighlightBenchEngineTest {
    @Test
    fun returnsSpansOnlyWhenAsked() {
        val engine = TextMateHighlightBenchEngine { OpenCodeBundledJoniMarkdownHighlighter.create(bundledTestResourceReader()) }
        val highlighter = engine.newHighlighter()

        val call =
            HighlightBenchProbe.highlight(highlighter, "typescript", "const a: number = 1; // one\n", HighlightBenchSettings.standard, HighlightBenchInlineGuard)

        assertEquals("Highlighted", call.outcome)
        assertTrue(call.highlighted)
        assertEquals(call.spanCount, call.spans!!.size)
        assertTrue(call.spans!!.any { it.role == CodeTokenRole.Comment.name })
    }
}
