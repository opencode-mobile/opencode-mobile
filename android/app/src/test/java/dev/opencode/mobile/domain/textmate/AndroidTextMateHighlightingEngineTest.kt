package dev.opencode.mobile.domain.textmate

import dev.opencode.mobile.markdown.CodeBlockKey
import dev.opencode.mobile.markdown.HighlightIntent
import dev.opencode.mobile.markdown.HighlightRequest
import dev.opencode.mobile.markdown.HighlightResult
import dev.opencode.mobile.markdown.HighlightRevision
import dev.opencode.mobile.markdown.MarkdownDiagnosticKind
import dev.opencode.mobile.markdown.OpenCodeCodeHighlighter
import dev.opencode.mobile.markdown.OpenCodeTextMateHighlightingEngine
import dev.opencode.mobile.markdown.OpenCodeTextMateMetricsSink
import dev.opencode.mobile.markdown.SyntaxHighlightResult
import dev.opencode.mobile.markdown.SyntaxHighlightSource
import dev.opencode.mobile.markdown.TextMateHighlightingEngine
import dev.opencode.mobile.markdown.TextMateParseObservation
import dev.opencode.mobile.markdown.TextMateParseOutcome
import dev.opencode.mobile.markdown.TextMateRequestScheduler
import dev.opencode.mobile.markdown.TextMateSchedulerLimits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AndroidTextMateHighlightingEngineTest {
    @Test
    fun concurrentFirstUseHasOneOffUiOwner() =
        runBlocking {
            val delegate = RacingEngine()
            val executor =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "textmate-owner-test")
                }
            val dispatcher = executor.asCoroutineDispatcher()
            try {
                val factoryCalls = AtomicInteger(0)
                val factoryThreads = ConcurrentLinkedQueue<String>()
                val engine =
                    AndroidTextMateHighlightingEngine(
                        delegateFactory = {
                            factoryCalls.incrementAndGet()
                            factoryThreads += Thread.currentThread().name
                            delegate
                        },
                        ownerDispatcher = dispatcher,
                        scheduler =
                            TextMateRequestScheduler(
                                limits =
                                    TextMateSchedulerLimits(
                                        maxPendingInteractive = 32,
                                        maxPendingHistory = 32,
                                    ),
                            ),
                    )

                val results =
                    (1L..24L)
                        .map { revision ->
                            async {
                                engine.highlight(request(key = "block-$revision", revision = revision))
                            }
                        }.awaitAll()

                assertEquals(1, delegate.maximumActive.get())
                assertEquals(24, delegate.completed.get())
                assertEquals(1, factoryCalls.get())
                assertTrue(factoryThreads.all { it.startsWith("textmate-owner-test") })
                assertTrue(delegate.threadNames.all { it.startsWith("textmate-owner-test") })
                assertTrue(results.all { it.outcome == TextMateParseOutcome.Highlighted })
            } finally {
                dispatcher.close()
                executor.shutdownNow()
            }
        }

    @Test
    fun latestRevisionCancelsOlderActiveWork() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val delegate =
                object : TextMateHighlightingEngine {
                    override suspend fun highlight(request: HighlightRequest): HighlightResult {
                        entered.complete(Unit)
                        release.await()
                        return HighlightResult(
                            key = request.key,
                            revision = request.revision,
                            source = request.source,
                            canonicalLanguage = "Kotlin",
                            policyEpoch = request.policyEpoch,
                            spans = emptyList(),
                            outcome = TextMateParseOutcome.Highlighted,
                            diagnostics = emptyList(),
                            observation =
                                TextMateParseObservation(
                                    outcome = TextMateParseOutcome.Highlighted,
                                    messageCharacters = request.source.length,
                                    fenceCharacters = request.source.length,
                                    fenceLines = 1,
                                    fenceIndex = 0,
                                    fenceCount = 1,
                                    spanCount = 0,
                                    totalNanoseconds = 1,
                                    stages = emptyList(),
                                    diagnosticKinds = emptyList(),
                                ),
                        )
                    }
                }
            val executor =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "textmate-owner-test")
                }
            val dispatcher = executor.asCoroutineDispatcher()
            try {
                val engine = AndroidTextMateHighlightingEngine(delegate, dispatcher)
                val first =
                    async {
                        engine.highlight(request(key = "block", revision = 1))
                    }
                withTimeout(2_000) { entered.await() }
                val second =
                    async {
                        engine.highlight(request(key = "block", revision = 2))
                    }
                delay(50)
                release.complete(Unit)
                assertEquals(TextMateParseOutcome.Cancelled, first.await().outcome)
                assertEquals(TextMateParseOutcome.Highlighted, second.await().outcome)
            } finally {
                dispatcher.close()
                executor.shutdownNow()
            }
        }

    @Test
    fun newerRevisionCancelsJobThatBlocksTheOwnerThread() =
        runBlocking {
            val highlighter = BlockingUntilCancelledHighlighter()
            val executor =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "textmate-owner-test")
                }
            val dispatcher = executor.asCoroutineDispatcher()
            try {
                val engine =
                    AndroidTextMateHighlightingEngine(
                        OpenCodeTextMateHighlightingEngine(highlighter),
                        dispatcher,
                    )
                val first =
                    async(Dispatchers.Default) {
                        engine.highlight(request(key = "block", revision = 1))
                    }
                assertTrue(highlighter.entered.await(2, TimeUnit.SECONDS))
                val second =
                    async(Dispatchers.Default) {
                        engine.highlight(request(key = "block", revision = 2))
                    }

                withTimeout(1_000) {
                    assertEquals(TextMateParseOutcome.Cancelled, first.await().outcome)
                    assertEquals(TextMateParseOutcome.Highlighted, second.await().outcome)
                }
                assertTrue(highlighter.sawCancel.get())
            } finally {
                highlighter.giveUp.countDown()
                // Graceful: a dropped queued admit would leave runBlocking waiting forever.
                executor.shutdown()
            }
        }

    @Test
    fun initializerErrorDuringHighlightDoesNotEscapePump() =
        runBlocking {
            val delegate =
                object : TextMateHighlightingEngine {
                    override suspend fun highlight(request: HighlightRequest): HighlightResult =
                        throw ExceptionInInitializerError(NullPointerException("QuantifierNode clinit"))
                }
            val executor =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "textmate-owner-test")
                }
            val dispatcher = executor.asCoroutineDispatcher()
            try {
                val engine = AndroidTextMateHighlightingEngine(delegate, dispatcher)
                val result =
                    withTimeout(2_000) {
                        engine.highlight(request(key = "block", revision = 1))
                    }
                assertEquals(TextMateParseOutcome.Failed, result.outcome)
                assertTrue(result.diagnostics.any { it.kind == MarkdownDiagnosticKind.HighlighterFallback })
            } finally {
                dispatcher.close()
                executor.shutdownNow()
            }
        }

    private fun request(
        key: String,
        revision: Long,
    ): HighlightRequest =
        HighlightRequest(
            key = CodeBlockKey(key),
            revision = HighlightRevision(revision),
            language = "kotlin",
            source = "val value = $revision",
            intent = HighlightIntent.InteractiveFinal,
            policyEpoch = 1,
        )

    /** Spins on its thread, like a real tokenizer, until the job is cancelled. Later calls return at once. */
    private class BlockingUntilCancelledHighlighter : OpenCodeCodeHighlighter {
        val entered = CountDownLatch(1)
        val giveUp = CountDownLatch(1)
        val sawCancel = AtomicBoolean(false)
        private val calls = AtomicInteger(0)

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
            if (calls.incrementAndGet() > 1) return result()
            entered.countDown()
            while (giveUp.count > 0) {
                if (shouldCancel()) {
                    sawCancel.set(true)
                    return result(TextMateParseOutcome.Cancelled)
                }
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

    private class RacingEngine : TextMateHighlightingEngine {
        val maximumActive = AtomicInteger(0)
        val completed = AtomicInteger(0)
        val threadNames = ConcurrentLinkedQueue<String>()
        private val active = AtomicInteger(0)

        override suspend fun highlight(request: HighlightRequest): HighlightResult {
            val nowActive = active.incrementAndGet()
            maximumActive.updateAndGet { current -> maxOf(current, nowActive) }
            threadNames += Thread.currentThread().name
            delay(2)
            active.decrementAndGet()
            completed.incrementAndGet()
            return HighlightResult(
                key = request.key,
                revision = request.revision,
                source = request.source,
                canonicalLanguage = "Kotlin",
                policyEpoch = request.policyEpoch,
                spans = emptyList(),
                outcome = TextMateParseOutcome.Highlighted,
                diagnostics = emptyList(),
                observation =
                    TextMateParseObservation(
                        outcome = TextMateParseOutcome.Highlighted,
                        messageCharacters = request.source.length,
                        fenceCharacters = request.source.length,
                        fenceLines = 1,
                        fenceIndex = 0,
                        fenceCount = 1,
                        spanCount = 0,
                        totalNanoseconds = 1,
                        stages = emptyList(),
                        diagnosticKinds = emptyList(),
                    ),
            )
        }
    }
}
