package dev.opencode.mobile.lab

import dev.opencode.mobile.highlight.core.HighlightCancellation
import dev.opencode.mobile.highlight.core.HighlightCore
import dev.opencode.mobile.highlight.core.HighlightDeadline
import dev.opencode.mobile.markdown.CodeBlockKey
import dev.opencode.mobile.markdown.CodeSpan
import dev.opencode.mobile.markdown.CodeTokenRole
import dev.opencode.mobile.markdown.HighlightCoreHighlightingEngine
import dev.opencode.mobile.markdown.HighlightIntent
import dev.opencode.mobile.markdown.HighlightRequest
import dev.opencode.mobile.markdown.HighlightResult
import dev.opencode.mobile.markdown.HighlightRevision
import dev.opencode.mobile.markdown.TextMateHighlightingEngine
import dev.opencode.mobile.markdown.TextMateParseOutcome
import dev.opencode.mobile.markdown.emptyResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightingEngineTest {
    private class RecordingTextMate : TextMateHighlightingEngine {
        val languages = mutableListOf<String?>()

        override suspend fun highlight(request: HighlightRequest): HighlightResult {
            languages += request.language
            return emptyResult(request, TextMateParseOutcome.Highlighted, 0, "TextMate")
        }
    }

    private fun request(
        language: String,
        revision: Long = 1,
    ) = HighlightRequest(
        key = CodeBlockKey("fence:$language"),
        revision = HighlightRevision(revision),
        language = language,
        source = "fun main() = println(\"hi\")",
        intent = HighlightIntent.InteractiveFinal,
        policyEpoch = 0,
    )

    @Test
    fun theSwitchFollowsTheSettingPerRequestAndSendsUnsupportedLanguagesToTextMate() =
        runTest {
            val textMate = RecordingTextMate()
            val highlightCore = HighlightCoreHighlightingEngine(textMate)
            var usesHighlightCore = false
            val engine = HighlightingEngineSwitch(textMate, highlightCore) { usesHighlightCore }

            engine.highlight(request("kotlin"))
            assertEquals(listOf<String?>("kotlin"), textMate.languages)

            usesHighlightCore = true
            val kotlin = engine.highlight(request("kotlin", revision = 2))
            engine.highlight(request("rust", revision = 3))
            assertEquals(HighlightCoreHighlightingEngine.BACKEND, kotlin.observation.backend)
            assertTrue(kotlin.spans.isNotEmpty())
            assertEquals(listOf<String?>("kotlin", "rust"), textMate.languages)
            assertEquals(1, highlightCore.sessionCount)

            // Back to TextMate at once, and the sessions are freed.
            usesHighlightCore = false
            engine.highlight(request("kotlin", revision = 4))
            assertEquals(listOf<String?>("kotlin", "rust", "kotlin"), textMate.languages)
            assertEquals(0, highlightCore.sessionCount)
        }

    @Test
    fun aFileSessionOnlyOpensForSupportedLanguages() {
        assertNull(HighlightCoreFileSession.open("rust", "fn main() {}"))
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
