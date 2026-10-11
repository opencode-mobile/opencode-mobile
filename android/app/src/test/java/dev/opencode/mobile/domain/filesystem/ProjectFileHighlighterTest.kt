package dev.opencode.mobile.domain.filesystem

import dev.opencode.mobile.markdown.CodeSpan
import dev.opencode.mobile.markdown.CodeTokenRole
import dev.opencode.mobile.markdown.HighlightRequest
import dev.opencode.mobile.markdown.HighlightResult
import dev.opencode.mobile.markdown.OpenCodeSemanticFallbackHighlighter
import dev.opencode.mobile.markdown.TextMateHighlightingEngine
import dev.opencode.mobile.markdown.TextMateParseObservation
import dev.opencode.mobile.markdown.TextMateParseOutcome
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFileHighlighterTest {
    private val source = "const greeting = \"hi\" // say hi\n"
    private val textMateSpans = listOf(CodeSpan(0, 5, CodeTokenRole.Keyword))
    private val fallbackSpans = OpenCodeSemanticFallbackHighlighter.highlight("javascript", source).spans

    @Test
    fun attachableOutcomes_useTextMateSpans() =
        runTest {
            listOf(
                TextMateParseOutcome.Highlighted,
                TextMateParseOutcome.NoSpans,
                TextMateParseOutcome.Unsupported,
                TextMateParseOutcome.Fallback,
            ).forEach { outcome ->
                val engine = FakeEngine(outcome, textMateSpans)
                val spans = highlighter(engine).highlight("src/app.js", "javascript", source)
                assertEquals(outcome.name, textMateSpans, spans)
                assertEquals(1, engine.requests.size)
            }
        }

    @Test
    fun unattachableOutcomes_useSemanticFallback() =
        runTest {
            assertTrue(fallbackSpans.isNotEmpty())
            listOf(
                TextMateParseOutcome.Failed,
                TextMateParseOutcome.Cancelled,
                TextMateParseOutcome.BudgetExceeded,
            ).forEach { outcome ->
                val engine = FakeEngine(outcome, textMateSpans)
                val spans = highlighter(engine).highlight("src/app.js", "javascript", source)
                assertEquals(outcome.name, fallbackSpans, spans)
            }
        }

    @Test
    fun wholeFileGoesToTheEngineInOnePass() =
        runTest {
            val engine = FakeEngine(TextMateParseOutcome.Highlighted, textMateSpans)
            highlighter(engine).highlight("src/app.js", "javascript", source)

            val request = engine.requests.single()
            assertEquals(source, request.source)
            assertEquals("javascript", request.language)
        }

    @Test
    fun textMateOff_skipsEngineAndUsesSemanticFallback() =
        runTest {
            val engine = FakeEngine(TextMateParseOutcome.Highlighted, textMateSpans)
            val highlighter = TextMateProjectFileHighlighter(engine, StandardTestDispatcher(testScheduler)) { false }

            assertEquals(fallbackSpans, highlighter.highlight("src/app.js", "javascript", source))
            assertTrue(engine.requests.isEmpty())
        }

    private fun TestScope.highlighter(engine: TextMateHighlightingEngine) =
        TextMateProjectFileHighlighter(engine, StandardTestDispatcher(testScheduler)) { true }
}

private class FakeEngine(
    private val outcome: TextMateParseOutcome,
    private val spans: List<CodeSpan>,
) : TextMateHighlightingEngine {
    val requests = mutableListOf<HighlightRequest>()

    override suspend fun highlight(request: HighlightRequest): HighlightResult {
        requests += request
        return HighlightResult(
            key = request.key,
            revision = request.revision,
            source = request.source,
            canonicalLanguage = request.language,
            policyEpoch = request.policyEpoch,
            spans = spans,
            outcome = outcome,
            diagnostics = emptyList(),
            observation =
                TextMateParseObservation(
                    outcome = outcome,
                    messageCharacters = request.source.length,
                    fenceCharacters = request.source.length,
                    fenceLines = 1,
                    fenceIndex = 0,
                    fenceCount = 1,
                    spanCount = spans.size,
                    totalNanoseconds = 0,
                    stages = emptyList(),
                    diagnosticKinds = emptyList(),
                ),
        )
    }
}
