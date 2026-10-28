package dev.opencode.mobile.markdown

import dev.opencode.mobile.highlight.bench.HighlightBenchCallStatus
import dev.opencode.mobile.highlight.bench.HighlightBenchHighlighter
import dev.opencode.mobile.highlight.bench.HighlightBenchInlineGuard
import dev.opencode.mobile.highlight.bench.HighlightBenchProbe
import dev.opencode.mobile.highlight.bench.HighlightBenchSettings
import dev.opencode.mobile.highlight.core.HighlightRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HighlightBenchEnginesTest {
    @Test
    fun theLabListsTextMateFirstThenHighlightCoreThenTheSemanticFallback() {
        assertEquals(listOf("textmate", "highlight-core", "semantic-fallback"), HighlightBenchEngines.available().map { it.id })
        assertEquals("highlight-core", HighlightBenchEngines.engine("highlight-core")?.displayName)
        assertEquals("Semantic fallback", HighlightBenchEngines.engine("semantic-fallback")?.displayName)
    }

    @Test
    fun highlightCoreHighlightsItsLanguagesAndCallsTheRestUnsupported() {
        val engine = HighlightCoreHighlightBenchEngine
        val highlighter = engine.newHighlighter()
        val call = probe(highlighter, "json", "{\"a\": [1, true]}\n")

        assertEquals(HighlightBenchCallStatus.Completed, call.status)
        assertEquals("Complete", call.outcome)
        assertTrue(call.highlighted)
        assertEquals(call.spanCount, call.spans!!.size)
        assertEquals(CodeTokenRole.Property.name, call.spans!!.first { it.start == 1 }.role)
        assertTrue(engine.supportsLanguage("patch"))

        assertFalse(engine.supportsLanguage("kotlin"))
        val unsupported = probe(highlighter, "kotlin", "val a = 1\n")
        assertEquals("Unsupported", unsupported.outcome)
        assertFalse(unsupported.highlighted)
    }

    @Test
    fun highlightCoreRolesMapOneToOneOntoCodeTokenRoles() {
        assertEquals(CodeTokenRole.entries.map { it.name }, HighlightRole.entries.map { it.name })
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
