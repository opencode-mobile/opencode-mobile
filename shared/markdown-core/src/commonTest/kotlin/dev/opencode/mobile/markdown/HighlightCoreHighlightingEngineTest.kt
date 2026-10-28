package dev.opencode.mobile.markdown

import dev.opencode.mobile.highlight.core.HighlightCancelFlag
import dev.opencode.mobile.highlight.core.HighlightCancellation
import dev.opencode.mobile.highlight.core.HighlightCore
import dev.opencode.mobile.highlight.core.HighlightDeadline
import dev.opencode.mobile.highlight.core.HighlightRole
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HighlightCoreHighlightingEngineTest {
    private class RecordingFallback : TextMateHighlightingEngine {
        val requests = mutableListOf<HighlightRequest>()
        val trims = mutableListOf<MemoryPressure>()

        override suspend fun highlight(request: HighlightRequest): HighlightResult {
            requests += request
            return emptyResult(request, TextMateParseOutcome.Highlighted, 0, null)
                .copy(spans = listOf(CodeSpan(0, 1, CodeTokenRole.Keyword)))
        }

        override suspend fun trim(level: MemoryPressure) {
            trims += level
        }
    }

    private val fallback = RecordingFallback()
    private var nextRevision = 0L

    private fun request(
        key: String,
        language: String?,
        source: String,
        revision: Long = ++nextRevision,
    ) = HighlightRequest(
        key = CodeBlockKey(key),
        revision = HighlightRevision(revision),
        language = language,
        source = source,
        intent = HighlightIntent.InteractiveFinal,
        policyEpoch = 0,
    )

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
    fun streamedRevisionsEqualAOneShotHighlightInEveryLanguage() {
        val engine = HighlightCoreHighlightingEngine(fallback)
        for ((language, source) in samples) {
            var result: HighlightResult? = null
            var end = 0
            while (end < source.length) {
                end = minOf(source.length, end + 7)
                result = runSuspend { engine.highlight(request("stream:$language", language, source.substring(0, end))) }
                assertEquals(oneShot(language, source.substring(0, end)), result.spans, "$language at $end")
            }
            assertEquals("Edit", result!!.observation.incrementalMode, language)
            assertEquals(HighlightCoreHighlightingEngine.BACKEND, result.observation.backend)
        }
        assertTrue(fallback.requests.isEmpty())
    }

    @Test
    fun anAppendReLexesFromTheLastChangedLineOnly() {
        val engine = HighlightCoreHighlightingEngine(fallback)
        val head = (1..40).joinToString("\n") { "val value$it = \"text\" // note" } + "\n"
        engine.highlightNow(request("fence", "kotlin", head))
        val result = engine.highlightNow(request("fence", "kotlin", head + "fun main() {}"))

        assertEquals("Edit", result.observation.incrementalMode)
        assertEquals(40, result.observation.reusedLineCount)
        assertEquals(1, result.observation.retokenizedLineCount)
        assertEquals(oneShot("kotlin", head + "fun main() {}"), result.spans)
    }

    @Test
    fun aMidTextEditStopsWhereStatesMatchAndShiftsTheRest() {
        val engine = HighlightCoreHighlightingEngine(fallback)
        val lines = (1..30).map { "const item$it = { name: \"n$it\", count: $it };" }
        engine.highlightNow(request("fence", "ts", lines.joinToString("\n")))
        val edited = lines.toMutableList().also { it[10] = "const renamed = { name: \"longer name\", count: 100 };" }
        val result = engine.highlightNow(request("fence", "ts", edited.joinToString("\n")))

        assertEquals(10, result.observation.reusedLineCount)
        assertEquals(1, result.observation.retokenizedLineCount)
        assertEquals(19, result.observation.suffixReusedLineCount)
        assertEquals(oneShot("ts", edited.joinToString("\n")), result.spans)

        // An edit that opens a block comment re-lexes to the end and still matches.
        edited[3] = "/* open"
        val opened = engine.highlightNow(request("fence", "ts", edited.joinToString("\n")))
        assertEquals(oneShot("ts", edited.joinToString("\n")), opened.spans)
    }

    @Test
    fun aCancelledCallReturnsNoSpansAndTheNextRequestStartsOver() {
        val engine = HighlightCoreHighlightingEngine(fallback)
        val source = samples.getValue("python")
        val cancelled = HighlightCancelFlag().also { it.cancel() }

        val result = engine.highlightNow(request("fence", "python", source), cancelled)
        assertEquals(TextMateParseOutcome.Cancelled, result.outcome)
        assertTrue(result.spans.isEmpty())
        assertEquals(0, engine.sessionCount)

        val retried = engine.highlightNow(request("fence", "python", source))
        assertEquals("Open", retried.observation.incrementalMode)
        assertEquals(oneShot("python", source), retried.spans)
    }

    @Test
    fun anOlderRevisionThanTheAppliedOneIsCancelled() {
        val engine = HighlightCoreHighlightingEngine(fallback)
        engine.highlightNow(request("fence", "json", "{\"a\": 1}", revision = 5))

        val stale = engine.highlightNow(request("fence", "json", "{\"a\": 2}", revision = 4))
        assertEquals(TextMateParseOutcome.Cancelled, stale.outcome)

        val same = engine.highlightNow(request("fence", "json", "{\"a\": 1}", revision = 6))
        assertEquals("Reuse", same.observation.incrementalMode)
        assertEquals(oneShot("json", "{\"a\": 1}"), same.spans)
    }

    @Test
    fun aPassedDeadlineReturnsAColoredPrefixAndTheNextRequestContinues() {
        val engine = HighlightCoreHighlightingEngine(fallback, deadlineMillis = 0.0)
        val source = (1..5_000).joinToString("\n") { "  \"key$it\": [$it, true, null, \"value\"]," }
        val expected = oneShot("json", source)

        val first = engine.highlightNow(request("fence", "json", source))
        assertEquals("Deadline", first.observation.schedulingReason)
        assertTrue(first.spans.size < expected.size)
        assertEquals(expected.subList(0, first.spans.size), first.spans)

        var result = first
        var calls = 0
        while (result.observation.schedulingReason == "Deadline" && calls++ < 100_000) {
            result = engine.highlightNow(request("fence", "json", source))
            assertEquals("Continue", result.observation.incrementalMode)
        }
        assertEquals(expected, result.spans)
    }

    @Test
    fun unsupportedLanguagesGoToTheFallbackEngine() {
        val engine = HighlightCoreHighlightingEngine(fallback)

        val cobol = runSuspend { engine.highlight(request("a", "cobol", "DISPLAY \"HI\".")) }
        val none = runSuspend { engine.highlight(request("b", null, "plain")) }
        val kotlin = runSuspend { engine.highlight(request("c", "kt", "fun main() {}")) }

        assertEquals(listOf("a", "b"), fallback.requests.map { it.key.value })
        assertEquals(listOf(CodeSpan(0, 1, CodeTokenRole.Keyword)), cobol.spans)
        assertEquals(listOf(CodeSpan(0, 1, CodeTokenRole.Keyword)), none.spans)
        assertEquals("kotlin", kotlin.canonicalLanguage)
        assertEquals(oneShot("kotlin", "fun main() {}"), kotlin.spans)
    }

    @Test
    fun sessionsStayWithinTheirBoundsLeastRecentlyUsedFirst() {
        val engine = HighlightCoreHighlightingEngine(fallback, maxSessions = 3, maxCharacters = 100)
        engine.highlightNow(request("a", "json", "[1]"))
        engine.highlightNow(request("b", "json", "[2]"))
        engine.highlightNow(request("c", "json", "[3]"))
        // Touching "a" makes "b" the least recently used.
        engine.highlightNow(request("a", "json", "[1]"))
        engine.highlightNow(request("d", "json", "[4]"))

        assertEquals(3, engine.sessionCount)
        assertEquals("Reuse", engine.highlightNow(request("a", "json", "[1]")).observation.incrementalMode)
        assertEquals("Open", engine.highlightNow(request("b", "json", "[2]")).observation.incrementalMode)

        // A fence over the character bound pushes out everything else but stays itself.
        engine.highlightNow(request("big", "json", "[" + "1,".repeat(60) + "1]"))
        assertEquals(1, engine.sessionCount)
    }

    @Test
    fun trimDropsSessionsByPressureAndTrimsTheFallback() {
        val engine = HighlightCoreHighlightingEngine(fallback, maxSessions = 8)
        repeat(8) { index -> engine.highlightNow(request("k$index", "diff", "+ line $index")) }

        runSuspend { engine.trim(MemoryPressure.Normal) }
        assertEquals(8, engine.sessionCount)
        runSuspend { engine.trim(MemoryPressure.Moderate) }
        assertEquals(4, engine.sessionCount)
        assertEquals("Reuse", engine.highlightNow(request("k7", "diff", "+ line 7")).observation.incrementalMode)
        runSuspend { engine.trim(MemoryPressure.Critical) }
        assertEquals(0, engine.sessionCount)
        assertEquals(listOf(MemoryPressure.Normal, MemoryPressure.Moderate, MemoryPressure.Critical), fallback.trims)
    }

    @Test
    fun rolesMapOneToOneByName() {
        assertEquals(HighlightRole.entries.map { it.name }, CodeTokenRole.entries.map { it.name })
        val engine = HighlightCoreHighlightingEngine(fallback)
        val source = "@@ -1 +1 @@ fun main\n-old\n+new"
        val spans = HighlightCore.highlight("diff", source, HighlightCancellation.Never, HighlightDeadline.never).spans
        val result = engine.highlightNow(request("diff", "diff", source))
        assertEquals(List(spans.count) { spans.role(it).name }, result.spans.map { it.role.name })
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            },
        )
        return requireNotNull(outcome).getOrThrow()
    }

    private companion object {
        val samples =
            mapOf(
                "typescript" to
                    """
                    /** Docs */
                    export async function load<T>(url: string): Promise<T> {
                      const text = `value: ${'$'}{url.length} items`;
                      return JSON.parse(text) as T; // done
                    }
                    """.trimIndent(),
                "kotlin" to
                    """
                    data class User(val name: String, val age: Int = 3)
                    /* block
                       comment */
                    fun greet(user: User) = "Hi ${'$'}{user.name}!"
                    """.trimIndent(),
                "swift" to
                    """
                    struct Point { var x: Double; let y = 2.5 }
                    let text = ""${'"'}
                    multi \(value) line
                    ""${'"'}
                    func run() async throws -> Int { return 0x1F }
                    """.trimIndent(),
                "python" to
                    """
                    @decorator
                    def handler(event, *args, **kwargs):
                        ""${'"'}Doc string
                        over lines""${'"'}
                        return f"{event!r} done" if args else None  # tail
                    """.trimIndent(),
                "json" to "{\n  \"name\": \"demo\",\n  \"list\": [1, 2.5e3, true, null],\n  \"nested\": {\"a\": \"\\u0041\"}\n}",
                "diff" to "diff --git a/f b/f\n--- a/f\n+++ b/f\n@@ -1,2 +1,2 @@ fun\n-old line\n+new line\n context",
                "javascript" to "const re = /ab+c/g; // regex\nlet s = 'one' + \"two\";\nclass A extends B { #x = 1 }",
            )
    }
}
