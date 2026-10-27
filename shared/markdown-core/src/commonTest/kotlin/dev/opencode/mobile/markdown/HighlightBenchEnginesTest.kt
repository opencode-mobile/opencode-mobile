package dev.opencode.mobile.markdown

import dev.opencode.mobile.highlight.bench.HighlightBenchCallStatus
import dev.opencode.mobile.highlight.bench.HighlightBenchHighlighter
import dev.opencode.mobile.highlight.bench.HighlightBenchInlineGuard
import dev.opencode.mobile.highlight.bench.HighlightBenchProbe
import dev.opencode.mobile.highlight.bench.HighlightBenchSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HighlightBenchEnginesTest {
    @Test
    fun theLabListsTextMateFirstThenTheSemanticFallback() {
        assertEquals(listOf("textmate", "semantic-fallback"), HighlightBenchEngines.available().map { it.id })
        assertEquals("Semantic fallback", HighlightBenchEngines.engine("semantic-fallback")?.displayName)
    }

    @Test
    fun theSemanticFallbackReturnsSpansForSupportedLanguages() {
        val highlighter = SemanticFallbackHighlightBenchEngine.newHighlighter()
        val call = probe(highlighter, "kotlin", "fun main() { val a = \"text\" // note\n}\n")

        assertEquals(HighlightBenchCallStatus.Completed, call.status)
        assertEquals("Highlighted", call.outcome)
        assertTrue(call.highlighted)
        assertEquals(call.spanCount, call.spans!!.size)
        assertTrue(call.spans!!.any { it.role == CodeTokenRole.Keyword.name })

        val unsupported = probe(highlighter, "cobol", "DISPLAY 'HI'.\n")
        assertEquals("Unsupported", unsupported.outcome)
        assertFalse(unsupported.highlighted)
    }

    private fun probe(
        highlighter: HighlightBenchHighlighter,
        language: String,
        source: String,
    ) = HighlightBenchProbe.highlight(highlighter, language, source, HighlightBenchSettings.standard, HighlightBenchInlineGuard)
}
