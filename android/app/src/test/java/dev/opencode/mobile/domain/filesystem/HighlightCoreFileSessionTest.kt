package dev.opencode.mobile.domain.filesystem

import dev.opencode.mobile.highlight.core.HighlightCancellation
import dev.opencode.mobile.highlight.core.HighlightCore
import dev.opencode.mobile.highlight.core.HighlightDeadline
import dev.opencode.mobile.markdown.CodeSpan
import dev.opencode.mobile.markdown.CodeTokenRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightCoreFileSessionTest {
    @Test
    fun aFileSessionOnlyOpensForSupportedLanguages() {
        assertNull(HighlightCoreFileSession.open("brainfuck", "++[>+<-]"))
        assertTrue(HighlightCoreFileSession.open("tsx", "const a = 1") != null)
    }

    @Test
    fun aFarRangeColorsAfterTheStatePassReachesItAndMatchesAOneShotHighlight() {
        val source = (1..20_000).joinToString("\n") { "  \"key$it\": [$it, true, null, \"/* not a comment */\"]," }
        val session = requireNotNull(HighlightCoreFileSession.open("json", source))

        var result = session.highlight(19_000, 19_050, 0.0)
        var calls = 1
        while (result.endLine == result.startLine && calls < 10_000) {
            result = session.highlight(19_000, 19_050, 0.0)
            calls++
        }
        assertTrue("the state-only pass needs more than one 0 ms call", calls > 1)
        assertEquals(19_000, result.startLine)
        assertTrue(result.endLine > 19_000)

        val lineStarts = listOf(0) + source.indices.filter { source[it] == '\n' }.map { it + 1 }
        val from = lineStarts[result.startLine]
        val until = lineStarts[result.endLine]
        val all = HighlightCore.highlight("json", source, HighlightCancellation.Never, HighlightDeadline.never).spans
        val expected =
            (0 until all.count)
                .filter { all.start(it) in from until until }
                .map { CodeSpan(all.start(it), all.end(it), CodeTokenRole.valueOf(all.role(it).name)) }
        assertEquals(expected, result.spans)
    }
}
