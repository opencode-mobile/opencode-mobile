package dev.opencode.mobile.markdown

import dev.opencode.mobile.highlight.core.HighlightCancellation
import dev.opencode.mobile.highlight.core.HighlightCore
import dev.opencode.mobile.highlight.core.HighlightDeadline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenCodeHighlightCoreCodeHighlighterTest {
    private val highlighter = OpenCodeHighlightCoreCodeHighlighter

    private fun oneShot(
        language: String,
        source: String,
    ): List<CodeSpan> {
        val spans = HighlightCore.highlight(language, source, HighlightCancellation.Never, HighlightDeadline.never).spans
        return List(spans.count) { index ->
            CodeSpan(spans.start(index), spans.end(index), CodeTokenRole.valueOf(spans.role(index).name))
        }
    }

    @Test
    fun supportedLanguagesMatchAOneShotHighlight() {
        val source = "data class User(val name: String) // note\nfun greet(user: User) = \"Hi ${'$'}{user.name}!\""
        val result = highlighter.highlight("kt", source)

        assertEquals(oneShot("kotlin", source), result.spans)
        assertTrue(result.spans.any { it.role == CodeTokenRole.Keyword })
        assertTrue(result.spans.none { it.role == CodeTokenRole.Plain })
        assertEquals(SyntaxHighlightSource.HighlightCore, result.source)
        assertEquals("kotlin", result.canonicalLanguage)
        assertTrue(result.diagnostics.isEmpty())
        assertTrue(highlighter.supportsLanguage("kt"))
        assertTrue(highlighter.supportsLanguage("JSON"))
    }

    @Test
    fun unsupportedOrMissingLanguagesStayPlain() {
        assertFalse(highlighter.supportsLanguage("brainfuck"))
        assertFalse(highlighter.supportsLanguage(null))
        for (language in listOf("brainfuck", null)) {
            val result = highlighter.highlight(language, "++[>+<-].")
            assertTrue(result.spans.isEmpty(), "$language")
            assertTrue(result.diagnostics.isEmpty(), "$language")
            assertNull(result.canonicalLanguage)
        }
    }

    @Test
    fun aLongLineHighlightsWithinTheDefaultDeadline() {
        val source = "[" + (1..3_000).joinToString(", ") { "\"item$it\"" } + "]\n{\"done\": true}"
        val result = highlighter.highlight("json", source)

        assertEquals(oneShot("json", source), result.spans)
    }

    @Test
    fun aPassedDeadlineKeepsTheHighlightedPrefixAndLeavesTheRestPlain() {
        val source = (1..5_000).joinToString("\n") { "  \"key$it\": [$it, true, null, \"value\"]," }
        val expected = oneShot("json", source)
        val result = highlighter.highlight("json", source, HighlightDeadline.after(0.0))

        assertTrue(result.spans.isNotEmpty())
        assertTrue(result.spans.size < expected.size)
        assertEquals(expected.subList(0, result.spans.size - 1), result.spans.dropLast(1))
        // The last span may be cut where the deadline stopped the line.
        val last = result.spans.last()
        val full = expected[result.spans.size - 1]
        assertEquals(full.start, last.start)
        assertEquals(full.role, last.role)
        assertTrue(last.end <= full.end)
        assertTrue(result.diagnostics.isEmpty())
    }
}
