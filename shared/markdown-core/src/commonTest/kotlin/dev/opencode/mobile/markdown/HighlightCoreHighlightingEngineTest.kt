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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HighlightCoreHighlightingEngineTest {
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
        val engine = HighlightCoreHighlightingEngine()
        for ((language, source) in samples) {
            var end = 0
            while (end < source.length) {
                end = minOf(source.length, end + 7)
                val result = runSuspend { engine.highlight(request("stream:$language", language, source.substring(0, end))) }
                assertEquals(oneShot(language, source.substring(0, end)), result.spans, "$language at $end")
            }
            val edit = engine.highlightReporting(request("stream:$language", language, source + "\n"))
            assertEquals("Edit", edit.mode, language)
        }
    }

    @Test
    fun anAppendReLexesFromTheLastChangedLineOnly() {
        val engine = HighlightCoreHighlightingEngine()
        val head = (1..40).joinToString("\n") { "val value$it = \"text\" // note" } + "\n"
        engine.highlightNow(request("fence", "kotlin", head))
        val call = engine.highlightReporting(request("fence", "kotlin", head + "fun main() {}"))

        assertEquals("Edit", call.mode)
        assertEquals(40, call.reusedLines)
        assertEquals(1, call.retokenizedLines)
        assertEquals(oneShot("kotlin", head + "fun main() {}"), call.result.spans)
    }

    @Test
    fun aMidTextEditStopsWhereStatesMatchAndShiftsTheRest() {
        val engine = HighlightCoreHighlightingEngine()
        val lines = (1..30).map { "const item$it = { name: \"n$it\", count: $it };" }
        engine.highlightNow(request("fence", "ts", lines.joinToString("\n")))
        val edited = lines.toMutableList().also { it[10] = "const renamed = { name: \"longer name\", count: 100 };" }
        val call = engine.highlightReporting(request("fence", "ts", edited.joinToString("\n")))

        assertEquals(10, call.reusedLines)
        assertEquals(1, call.retokenizedLines)
        assertEquals(19, call.suffixReusedLines)
        assertEquals(oneShot("ts", edited.joinToString("\n")), call.result.spans)

        // An edit that opens a block comment re-lexes to the end and still matches.
        edited[3] = "/* open"
        val opened = engine.highlightNow(request("fence", "ts", edited.joinToString("\n")))
        assertEquals(oneShot("ts", edited.joinToString("\n")), opened.spans)
    }

    @Test
    fun aCancelledCallReturnsNoSpansAndTheNextRequestStartsOver() {
        val engine = HighlightCoreHighlightingEngine()
        val source = samples.getValue("python")
        val cancelled = HighlightCancelFlag().also { it.cancel() }

        val result = engine.highlightNow(request("fence", "python", source), cancelled)
        assertEquals(TextMateParseOutcome.Cancelled, result.outcome)
        assertTrue(result.spans.isEmpty())
        assertEquals(0, engine.sessionCount)

        val retried = engine.highlightReporting(request("fence", "python", source))
        assertEquals("Open", retried.mode)
        assertEquals(oneShot("python", source), retried.result.spans)
    }

    @Test
    fun anOlderRevisionThanTheAppliedOneIsCancelled() {
        val engine = HighlightCoreHighlightingEngine()
        engine.highlightNow(request("fence", "json", "{\"a\": 1}", revision = 5))

        val stale = engine.highlightNow(request("fence", "json", "{\"a\": 2}", revision = 4))
        assertEquals(TextMateParseOutcome.Cancelled, stale.outcome)

        val same = engine.highlightReporting(request("fence", "json", "{\"a\": 1}", revision = 6))
        assertEquals("Reuse", same.mode)
        assertEquals(oneShot("json", "{\"a\": 1}"), same.result.spans)
    }

    @Test
    fun aPassedDeadlineReturnsAColoredPrefixAndTheNextRequestContinues() {
        val engine = HighlightCoreHighlightingEngine(deadlineMillis = 0.0)
        val source = (1..5_000).joinToString("\n") { "  \"key$it\": [$it, true, null, \"value\"]," }
        val expected = oneShot("json", source)

        val first = engine.highlightReporting(request("fence", "json", source))
        assertTrue(first.partial)
        assertEquals(TextMateParseOutcome.Partial, first.result.outcome)
        assertTrue(first.result.spans.size < expected.size)
        assertEquals(expected.subList(0, first.result.spans.size), first.result.spans)

        var call = first
        var calls = 0
        while (call.partial && calls++ < 100_000) {
            call = engine.highlightReporting(request("fence", "json", source))
            assertEquals("Continue", call.mode)
        }
        assertEquals(expected, call.result.spans)
    }

    @Test
    fun unsupportedOrMissingLanguagesComeBackPlainAndUnsupported() {
        val engine = HighlightCoreHighlightingEngine()

        val unsupported = runSuspend { engine.highlight(request("a", "brainfuck", "++[>+<-].")) }
        val none = runSuspend { engine.highlight(request("b", null, "plain")) }
        val kotlin = runSuspend { engine.highlight(request("c", "kt", "fun main() {}")) }

        for (result in listOf(unsupported, none)) {
            assertEquals(TextMateParseOutcome.Unsupported, result.outcome)
            assertTrue(result.spans.isEmpty())
            assertTrue(result.diagnostics.isEmpty())
            assertNull(result.canonicalLanguage)
        }
        assertEquals(1, engine.sessionCount, "only the supported fence keeps a session")
        assertEquals(TextMateParseOutcome.Highlighted, kotlin.outcome)
        assertEquals("kotlin", kotlin.canonicalLanguage)
        assertEquals(oneShot("kotlin", "fun main() {}"), kotlin.spans)
    }

    @Test
    fun anUnsupportedResultAttachesAsPlainText() {
        val document = OpenCodeMarkdownParser.parse("```brainfuck\n++[>+<-].\n```", OpenCodeStructureOnlyHighlighter)
        val fence = document.codeFences(ownerKey = "message", revision = HighlightRevision(1), policyEpoch = 0).single()
        val result = runSuspend { HighlightCoreHighlightingEngine().highlight(fence.request(HighlightIntent.InteractiveFinal)) }

        val attached = assertIs<MarkdownHighlightAttachment.Attached>(document.attachTextMateResult(fence, result))
        assertTrue(assertIs<MarkdownBlock.CodeBlock>(attached.document.blocks.single()).spans.isEmpty())
    }

    @Test
    fun sessionsStayWithinTheirBoundsLeastRecentlyUsedFirst() {
        val engine = HighlightCoreHighlightingEngine(maxSessions = 3, maxCharacters = 100)
        engine.highlightNow(request("a", "json", "[1]"))
        engine.highlightNow(request("b", "json", "[2]"))
        engine.highlightNow(request("c", "json", "[3]"))
        // Touching "a" makes "b" the least recently used.
        engine.highlightNow(request("a", "json", "[1]"))
        engine.highlightNow(request("d", "json", "[4]"))

        assertEquals(3, engine.sessionCount)
        assertEquals("Reuse", engine.highlightReporting(request("a", "json", "[1]")).mode)
        assertEquals("Open", engine.highlightReporting(request("b", "json", "[2]")).mode)

        // A fence over the character bound pushes out everything else but stays itself.
        engine.highlightNow(request("big", "json", "[" + "1,".repeat(60) + "1]"))
        assertEquals(1, engine.sessionCount)
    }

    @Test
    fun trimDropsSessionsByPressure() {
        val engine = HighlightCoreHighlightingEngine(maxSessions = 8)
        repeat(8) { index -> engine.highlightNow(request("k$index", "diff", "+ line $index")) }

        runSuspend { engine.trim(MemoryPressure.Normal) }
        assertEquals(8, engine.sessionCount)
        runSuspend { engine.trim(MemoryPressure.Moderate) }
        assertEquals(4, engine.sessionCount)
        assertEquals("Reuse", engine.highlightReporting(request("k7", "diff", "+ line 7")).mode)
        runSuspend { engine.trim(MemoryPressure.Critical) }
        assertEquals(0, engine.sessionCount)
    }

    @Test
    fun rolesMapOneToOneByName() {
        assertEquals(HighlightRole.entries.map { it.name }, CodeTokenRole.entries.map { it.name })
        val engine = HighlightCoreHighlightingEngine()
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
