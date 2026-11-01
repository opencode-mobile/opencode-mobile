package dev.opencode.mobile.markdown

import dev.opencode.mobile.highlight.bench.HighlightBenchCallStatus
import dev.opencode.mobile.highlight.bench.HighlightBenchCanceller
import dev.opencode.mobile.highlight.bench.HighlightBenchHighlighter
import dev.opencode.mobile.highlight.bench.HighlightBenchInlineGuard
import dev.opencode.mobile.highlight.bench.HighlightBenchProbe
import dev.opencode.mobile.highlight.bench.HighlightBenchSettings
import dev.opencode.mobile.highlight.core.HighlightRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HighlightBenchEnginesTest {
    @Test
    fun theLabListsOnlyHighlightCore() {
        assertEquals(listOf("highlight-core"), HighlightBenchEngines.available().map { it.id })
        assertEquals("highlight-core", HighlightBenchEngines.engine("highlight-core")?.displayName)
        assertNull(HighlightBenchEngines.engine("textmate"))
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

        assertTrue(engine.supportsLanguage("kotlin"))
        assertFalse(engine.supportsLanguage("brainfuck"))
        val unsupported = probe(highlighter, "brainfuck", "++[>+<-].\n")
        assertEquals("Unsupported", unsupported.outcome)
        assertFalse(unsupported.highlighted)
    }

    @Test
    fun highlightCoreStopsWhenTheCallersCancellerFires() {
        val canceller = HighlightBenchCanceller().apply { cancel() }
        val source = "val a = 1\n".repeat(5_000)
        val call =
            HighlightBenchProbe.highlight(
                HighlightCoreHighlightBenchEngine.newHighlighter(),
                "kotlin",
                source,
                HighlightBenchSettings.standard,
                HighlightBenchInlineGuard,
                canceller,
            )

        assertEquals("Cancelled", call.outcome)
        assertFalse(call.highlighted)
    }

    @Test
    fun highlightCoreRolesMapOneToOneOntoCodeTokenRoles() {
        assertEquals(CodeTokenRole.entries.map { it.name }, HighlightRole.entries.map { it.name })
    }

    private fun probe(
        highlighter: HighlightBenchHighlighter,
        language: String,
        source: String,
    ) = HighlightBenchProbe.highlight(highlighter, language, source, HighlightBenchSettings.standard, HighlightBenchInlineGuard)
}
