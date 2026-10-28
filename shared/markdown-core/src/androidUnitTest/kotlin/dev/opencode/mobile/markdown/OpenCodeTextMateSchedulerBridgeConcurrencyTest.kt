package dev.opencode.mobile.markdown

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenCodeTextMateSchedulerBridgeConcurrencyTest {
    @Test
    fun admitFromAnotherThreadCancelsTheRunningPump() {
        val entered = CountDownLatch(1)
        val giveUp = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val highlighter =
            object : OpenCodeCodeHighlighter {
                override fun supportsLanguage(languageOrAlias: String?): Boolean = true

                override fun highlight(
                    languageOrAlias: String?,
                    code: String,
                ): SyntaxHighlightResult = result()

                override fun highlight(
                    languageOrAlias: String?,
                    code: String,
                    metricsSink: OpenCodeTextMateMetricsSink,
                    shouldCancel: () -> Boolean,
                ): SyntaxHighlightResult {
                    // Only the first job spins like a slow tokenizer; later ones return at once.
                    if (calls.incrementAndGet() > 1) return result()
                    entered.countDown()
                    while (giveUp.count > 0) {
                        if (shouldCancel()) return result(TextMateParseOutcome.Cancelled)
                        Thread.onSpinWait()
                    }
                    return result()
                }

                private fun result(outcome: TextMateParseOutcome? = null) =
                    SyntaxHighlightResult(
                        spans = emptyList(),
                        diagnostics = emptyList(),
                        source = SyntaxHighlightSource.TextMate,
                        textMateOutcome = outcome,
                    )
            }
        val bridge = OpenCodeTextMateSchedulerBridge(engine = OpenCodeTextMateHighlightingEngine(highlighter))
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertNotNull(admit(bridge, revision = 1).ticketId)
            val pumped = executor.submit<String?> { bridge.pumpOnce() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))

            assertNotNull(admit(bridge, revision = 2).ticketId)

            val first = decode(assertNotNull(pumped.get(1, TimeUnit.SECONDS))).single()
            assertEquals(1L, first.revision.value)
            assertEquals(TextMateParseOutcome.Cancelled, first.outcome)
            assertNotNull(first.observation.cancelRequestedToStopNanoseconds)
            assertTrue(bridge.hasPendingWork())

            val second = decode(assertNotNull(executor.submit<String?> { bridge.pumpOnce() }.get(1, TimeUnit.SECONDS)))
            assertEquals(2L, second.single().revision.value)
            assertFalse(bridge.hasPendingWork())
        } finally {
            giveUp.countDown()
            executor.shutdown()
        }
    }

    private fun admit(
        bridge: OpenCodeTextMateSchedulerBridge,
        revision: Long,
    ): SchedulerAdmitEnvelope {
        val request =
            HighlightRequest(
                key = CodeBlockKey("block"),
                revision = HighlightRevision(revision),
                language = "kotlin",
                source = "val value = $revision",
                intent = HighlightIntent.InteractiveFinal,
                policyEpoch = 1,
            )
        return OpenCodeMarkdownJson.json.decodeFromString(bridge.admit(OpenCodeMarkdownJson.json.encodeToString(request)))
    }

    private fun decode(resultsJson: String): List<HighlightResult> = OpenCodeMarkdownJson.json.decodeFromString(resultsJson)
}
