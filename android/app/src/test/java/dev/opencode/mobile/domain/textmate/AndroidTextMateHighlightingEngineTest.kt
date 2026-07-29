package dev.opencode.mobile.domain.textmate

import dev.opencode.mobile.markdown.CodeBlockKey
import dev.opencode.mobile.markdown.HighlightIntent
import dev.opencode.mobile.markdown.HighlightRequest
import dev.opencode.mobile.markdown.HighlightResult
import dev.opencode.mobile.markdown.HighlightRevision
import dev.opencode.mobile.markdown.TextMateHighlightingEngine
import dev.opencode.mobile.markdown.TextMateParseObservation
import dev.opencode.mobile.markdown.TextMateParseOutcome
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
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
                    )

                (1L..24L)
                    .map { revision ->
                        async {
                            engine.highlight(request(revision))
                        }
                    }.awaitAll()

                assertEquals(1, delegate.maximumActive.get())
                assertEquals(24, delegate.completed.get())
                assertEquals(1, factoryCalls.get())
                assertTrue(factoryThreads.all { it.startsWith("textmate-owner-test") })
                assertTrue(delegate.threadNames.all { it.startsWith("textmate-owner-test") })
            } finally {
                dispatcher.close()
                executor.shutdownNow()
            }
        }

    private fun request(revision: Long): HighlightRequest =
        HighlightRequest(
            key = CodeBlockKey("block-$revision"),
            revision = HighlightRevision(revision),
            language = "kotlin",
            source = "val value = $revision",
            intent = HighlightIntent.InteractiveFinal,
            policyEpoch = 1,
        )

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
