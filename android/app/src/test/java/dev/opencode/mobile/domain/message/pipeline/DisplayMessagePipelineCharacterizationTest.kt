package dev.opencode.mobile.domain.message

import dev.opencode.mobile.markdown.CodeSpan
import dev.opencode.mobile.markdown.CodeTokenRole
import dev.opencode.mobile.markdown.MarkdownBlock
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.markdown.MarkdownInline
import dev.opencode.mobile.markdown.OpenCodeMarkdownDetector
import dev.opencode.mobile.markdown.OpenCodeMarkdownParser
import dev.opencode.mobile.markdown.OpenCodeStructureOnlyHighlighter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class DisplayMessagePipelineCharacterizationTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun clearDecorationCache() {
        BlockDecorationCache.clear()
    }

    @Test
    fun smallFinalHistoryPublishesSinglePreparedFrame() =
        runTest(testDispatcher) {
            val harness = startHarness()

            harness.source.emit(MessageListUpdate.Messages(listOf(plainAssistant("hi"))))

            assertEquals(1, harness.emissions.size)
            val frame = harness.emissions.single() as DisplayMessageListUpdate.Messages
            assertEquals("hi", frame.messages.single().text)
            assertEquals(1, harness.preparer.prepareMessagesInvocations.size)
            assertEquals(
                MessagePreparationMode.Final,
                harness.preparer.prepareMessagesInvocations
                    .single()
                    .mode,
            )
        }

    @Test
    fun identicalNonStreamingSnapshot_doesNotPrepareAgain() =
        runTest(testDispatcher) {
            val harness = startHarness()
            val messages = listOf(plainAssistant("hi"))
            harness.source.emit(MessageListUpdate.Messages(messages))
            advanceUntilIdle()
            assertEquals(1, harness.preparer.prepareMessagesInvocations.size)
            assertEquals(1, harness.emissions.size)

            harness.source.emit(MessageListUpdate.Messages(messages))
            advanceUntilIdle()
            assertEquals(1, harness.preparer.prepareMessagesInvocations.size)
            assertEquals(1, harness.emissions.size)
        }

    @Test
    fun largeHistoryPublishesFormatFirstThenAtMostTwoEnrichmentFrames() =
        runTest(testDispatcher) {
            val harness = startHarness(holdDelays = true)

            // Five markdown messages: viewport publishes after 3, remaining after the rest.
            val history =
                (1..5).map { index ->
                    markdownAssistant(
                        id = "msg-$index",
                        text = "# Heading $index\n\n" + "Some prose with `code`. ".repeat(80),
                    )
                }
            require(
                history.sumOf { it.text.length } >=
                    MessageContentPreparer.HISTORY_PLAIN_FIRST_MARKDOWN_CHARACTER_THRESHOLD,
            )

            harness.source.emit(MessageListUpdate.Messages(history))

            assertEquals(
                MessagePreparationMode.HistoryPlainFirst,
                harness.preparer.prepareMessagesInvocations
                    .first()
                    .mode,
            )
            // Newest-first HistoryPlainFirst: newest message frame, then remaining history.
            assertEquals(2, harness.emissions.size)
            val newestFirst = harness.emissions[0] as DisplayMessageListUpdate.Messages
            assertTrue(newestFirst.messages.last().renderedMarkdown != null)
            val formatRest = harness.emissions[1] as DisplayMessageListUpdate.Messages
            assertTrue(formatRest.messages.all { it.renderedMarkdown != null })
            assertEquals(48L, harness.delay.pendingMs())
            harness.delay.release()
            advanceUntilIdle()

            val enrichmentFrames =
                harness.emissions
                    .filterIsInstance<DisplayMessageListUpdate.Messages>()
                    .drop(2)
            assertTrue(
                "expected at most two enrichment frames after plain-first, got ${enrichmentFrames.size}",
                enrichmentFrames.size <= 2,
            )

            val finalFrame =
                harness.emissions.filterIsInstance<DisplayMessageListUpdate.Messages>().last()
            assertEquals(5, finalFrame.messages.size)
            assertTrue(finalFrame.messages.all { it.renderedMarkdown != null })
        }

    @Test
    fun largeSingleMessageHistoryPublishesFormatFirstThenOneViewportEnrichment() =
        runTest(testDispatcher) {
            val harness = startHarness(holdDelays = true)

            val large = largeMarkdownAssistant("msg-1")
            harness.source.emit(MessageListUpdate.Messages(listOf(large)))

            // Newest-first publishes the single message, then the remaining plain-first pass.
            assertEquals(2, harness.emissions.size)
            val formatFirst = harness.emissions.last() as DisplayMessageListUpdate.Messages
            assertTrue(formatFirst.messages.single().renderedMarkdown != null)
            assertEquals(48L, harness.delay.pendingMs())
            harness.delay.release()
            advanceUntilIdle()

            val enrichmentFrames =
                harness.emissions
                    .filterIsInstance<DisplayMessageListUpdate.Messages>()
                    .drop(2)
            assertEquals(1, enrichmentFrames.size)
            assertTrue(
                enrichmentFrames
                    .single()
                    .messages
                    .single()
                    .renderedMarkdown != null,
            )
        }

    @Test
    fun streamingPublishesAfterQuietWindowUnderDeterministicClock() =
        runTest(testDispatcher) {
            val harness = startHarness(holdDelays = true)

            harness.source.emit(MessageListUpdate.Messages(listOf(plainAssistant("seed"))))
            harness.emissions.clear()
            harness.preparer.prepareMessagesInvocations.clear()

            harness.source.emit(
                MessageListUpdate.Messages(listOf(streamingAssistant("msg-1", "Hello"))),
            )
            assertTrue(harness.emissions.isEmpty())
            assertEquals(STREAMING_PREPARE_DEBOUNCE_MS, harness.delay.pendingMs())

            harness.delay.release()
            advanceUntilIdle()

            assertEquals(1, harness.emissions.size)
            val frame = harness.emissions.single() as DisplayMessageListUpdate.Messages
            assertEquals("Hello", frame.messages.single().text)
            assertTrue(frame.messages.single().isStreaming)
        }

    @Test
    fun streamingCoalescesDeltasInsideOpenWindow() =
        runTest(testDispatcher) {
            val harness = startHarness(holdDelays = true)

            harness.source.emit(MessageListUpdate.Messages(listOf(plainAssistant("seed"))))
            advanceUntilIdle()
            harness.emissions.clear()
            harness.delay.resetCallCount()

            harness.source.emit(
                MessageListUpdate.Messages(listOf(streamingAssistant("msg-1", "Hel"))),
            )
            assertEquals(STREAMING_PREPARE_DEBOUNCE_MS, harness.delay.pendingMs())
            assertTrue(harness.emissions.isEmpty())

            harness.source.emit(
                MessageListUpdate.Messages(listOf(streamingAssistant("msg-1", "Hello"))),
            )
            harness.source.emit(
                MessageListUpdate.Messages(listOf(streamingAssistant("msg-1", "Hello world"))),
            )
            assertTrue(harness.emissions.isEmpty())
            assertEquals(1, harness.delay.callCount)
            assertEquals(STREAMING_PREPARE_DEBOUNCE_MS, harness.delay.pendingMs())

            harness.delay.release()
            advanceUntilIdle()

            assertEquals(1, harness.emissions.size)
            val frame = harness.emissions.single() as DisplayMessageListUpdate.Messages
            assertEquals("Hello world", frame.messages.single().text)
            assertTrue(frame.messages.single().isStreaming)
        }

    @Test
    fun streamingFlushesWhenMaxBatchAgeExceeded() =
        runTest(testDispatcher) {
            val harness = startHarness(holdDelays = true)

            harness.source.emit(MessageListUpdate.Messages(listOf(plainAssistant("seed"))))
            harness.emissions.clear()

            harness.source.emit(
                MessageListUpdate.Messages(listOf(streamingAssistant("msg-1", "A"))),
            )
            assertEquals(STREAMING_PREPARE_DEBOUNCE_MS, harness.delay.pendingMs())
            harness.delay.release()
            advanceUntilIdle()
            assertEquals(1, harness.emissions.size)

            harness.timeSource += STREAMING_PREPARE_MAX_BATCH_MS.milliseconds
            harness.source.emit(
                MessageListUpdate.Messages(listOf(streamingAssistant("msg-1", "AB"))),
            )
            advanceUntilIdle()

            assertEquals(2, harness.emissions.size)
            val latest = harness.emissions.last() as DisplayMessageListUpdate.Messages
            assertEquals("AB", latest.messages.single().text)
        }

    @Test
    fun denseStreamingMaxBatchPublishesProgressively() =
        runTest(testDispatcher) {
            val harness = startHarness()

            harness.source.emit(MessageListUpdate.Messages(listOf(plainAssistant("seed"))))
            harness.emissions.clear()

            harness.timeSource += STREAMING_PREPARE_MAX_BATCH_MS.milliseconds
            harness.preparer.minimumPrepareDelayMs = 40L

            for (count in 1..8) {
                val text = "x".repeat(count)
                harness.source.emit(
                    MessageListUpdate.Messages(listOf(streamingAssistant("msg-1", text))),
                )
                delay(5)
            }
            advanceUntilIdle()
            // Virtualized prepare delays (minimumPrepareDelayMs) need scheduler time.
            testScheduler.advanceTimeBy(500)
            advanceUntilIdle()

            val frames = harness.emissions.filterIsInstance<DisplayMessageListUpdate.Messages>()
            assertTrue("expected progressive streaming publishes, got ${frames.size}", frames.size >= 2)
            assertEquals(
                "xxxxxxxx",
                frames
                    .last()
                    .messages
                    .single()
                    .text,
            )
            assertTrue(
                frames
                    .last()
                    .messages
                    .single()
                    .isStreaming,
            )
        }

    @Test
    fun finalizationPublishesPlaceholderThenFinal() =
        runTest(testDispatcher) {
            val harness = startHarness()

            harness.source.emit(
                MessageListUpdate.Messages(listOf(streamingAssistant("msg-1", "# Hi"))),
            )
            harness.emissions.clear()
            harness.preparer.prepareMessagesInvocations.clear()

            harness.source.emit(
                MessageListUpdate.Messages(listOf(finalAssistant("msg-1", "# Hi"))),
            )

            assertEquals(2, harness.emissions.size)
            assertEquals(
                listOf(MessagePreparationMode.FinalPlaceholder, MessagePreparationMode.Final),
                harness.preparer.prepareMessagesInvocations.map { it.mode },
            )
        }

    @Test
    fun loadFailurePublishesLoadFailedAndDropsPendingWork() =
        runTest(testDispatcher) {
            val harness = startHarness(holdDelays = true)

            harness.source.emit(MessageListUpdate.Messages(listOf(plainAssistant("seed"))))
            harness.emissions.clear()

            harness.source.emit(
                MessageListUpdate.Messages(listOf(streamingAssistant("msg-1", "pending"))),
            )
            assertTrue(harness.emissions.isEmpty())
            assertEquals(STREAMING_PREPARE_DEBOUNCE_MS, harness.delay.pendingMs())

            harness.source.emit(MessageListUpdate.LoadFailed(IllegalStateException("boom")))
            advanceUntilIdle()

            assertEquals(1, harness.emissions.size)
            assertTrue(harness.emissions.single() is DisplayMessageListUpdate.LoadFailed)

            // Cancelled debounce must not publish after release.
            if (harness.delay.hasPending()) {
                harness.delay.release()
            }
            advanceUntilIdle()
            assertEquals(1, harness.emissions.size)
        }

    @Test
    fun observationCompletionDropsPendingStreamingSnapshot() =
        runTest(testDispatcher) {
            val harness = startHarness(holdDelays = true)

            harness.source.emit(MessageListUpdate.Messages(listOf(plainAssistant("seed"))))
            harness.emissions.clear()

            harness.source.emit(
                MessageListUpdate.Messages(listOf(streamingAssistant("msg-1", "flush-me"))),
            )
            assertTrue(harness.emissions.isEmpty())

            harness.source.close()
            advanceUntilIdle()

            assertTrue(harness.emissions.isEmpty())
        }

    @Test
    fun stalePrepareDoesNotPublishAfterNewerSnapshot() =
        runTest(testDispatcher) {
            val harness = startHarness()

            harness.preparer.holdNextPrepareMessages()
            harness.source.emit(MessageListUpdate.Messages(listOf(plainAssistant("one"))))
            assertTrue(harness.preparer.hasPendingPrepareMessagesHold())
            assertTrue(harness.emissions.isEmpty())

            harness.source.emit(MessageListUpdate.Messages(listOf(plainAssistant("two"))))
            advanceUntilIdle()

            // Newer snapshot prepares without waiting on the held older prepare.
            assertEquals(1, harness.emissions.size)
            assertEquals(
                "two",
                (harness.emissions.single() as DisplayMessageListUpdate.Messages).messages.single().text,
            )

            harness.preparer.releaseNextPrepareMessages()
            advanceUntilIdle()

            // Stale generation must not emit or replace the newer frame.
            assertEquals(1, harness.emissions.size)
            assertEquals(
                "two",
                (harness.emissions.single() as DisplayMessageListUpdate.Messages).messages.single().text,
            )
        }

    @Test
    fun cancelledEnrichmentPrepareDoesNotPublishAfterNewerLiveSnapshot() =
        runTest(testDispatcher) {
            val harness = startHarness(holdDelays = true)
            // This test needs a published plain-first baseline before parking enrich.
            // Under hold-ready the UI stays Loading, so keep coverage on the classic path.
            if (MessageContentPreparer.HOLD_READY_UNTIL_NEWEST_MARKDOWN) {
                return@runTest
            }

            val large = largeMarkdownAssistant("history")
            harness.source.emit(MessageListUpdate.Messages(listOf(large)))
            // Newest-first publishes plain-first before enrichment is scheduled.
            assertEquals(2, harness.emissions.size)
            assertEquals(48L, harness.delay.pendingMs())

            harness.preparer.holdNextPrepareMessage()
            harness.delay.release()
            assertTrue(harness.preparer.hasPendingPrepareMessageHold())

            harness.source.emit(
                MessageListUpdate.Messages(listOf(streamingAssistant("live", "token"))),
            )
            advanceUntilIdle()

            val emissionsBeforeRelease = harness.emissions.size
            assertTrue(emissionsBeforeRelease >= 2)

            harness.preparer.releaseNextPrepareMessage()
            advanceUntilIdle()

            assertEquals(emissionsBeforeRelease, harness.emissions.size)
            assertFalse(harness.preparer.hasPendingPrepareMessageHold())
        }

    @Test
    fun snapshotIntakeContinuesWhilePrepareMessagesIsHeld() =
        runTest(testDispatcher) {
            val harness = startHarness()

            harness.preparer.holdNextPrepareMessages()
            harness.source.emit(MessageListUpdate.Messages(listOf(plainAssistant("one"))))
            assertTrue(harness.preparer.hasPendingPrepareMessagesHold())
            assertTrue(harness.emissions.isEmpty())

            harness.source.emit(MessageListUpdate.Messages(listOf(plainAssistant("two"))))
            advanceUntilIdle()

            assertEquals(1, harness.emissions.size)
            assertEquals(
                "two",
                (harness.emissions.single() as DisplayMessageListUpdate.Messages).messages.single().text,
            )

            harness.preparer.releaseNextPrepareMessages()
            advanceUntilIdle()
            assertEquals(1, harness.emissions.size)
        }

    @Test
    fun blockedPreparerDoesNotDelayPlainStreamingTextLane() =
        runTest(testDispatcher) {
            val harness = startHarness(holdDelays = true)

            harness.source.emit(MessageListUpdate.Messages(listOf(plainAssistant("seed"))))
            harness.emissions.clear()
            harness.preparer.prepareMessagesInvocations.clear()

            harness.preparer.holdNextPrepareMessages()
            harness.source.emit(
                MessageListUpdate.Messages(listOf(streamingAssistant("msg-1", "# Hello"))),
            )
            assertEquals(STREAMING_PREPARE_DEBOUNCE_MS, harness.delay.pendingMs())
            assertTrue(harness.emissions.isEmpty())

            harness.delay.release()
            advanceUntilIdle()

            assertEquals(1, harness.emissions.size)
            val plain = harness.emissions.single() as DisplayMessageListUpdate.Messages
            assertEquals("# Hello", plain.messages.single().text)
            assertTrue(plain.messages.single().isStreaming)
            // Structure-first paints markdown chrome without waiting on a held prepare.
            assertTrue(plain.messages.single().renderedMarkdown != null)
            assertTrue(harness.preparer.prepareStructureOnlyInvocations.isNotEmpty())
            assertTrue(
                "a held prepare must not block the structure-first text lane",
                harness.preparer.hasPendingPrepareMessagesHold() ||
                    harness.preparer.prepareMessagesInvocations.isNotEmpty(),
            )

            harness.timeSource += STREAMING_PREPARE_MAX_BATCH_MS.milliseconds
            harness.source.emit(
                MessageListUpdate.Messages(listOf(streamingAssistant("msg-1", "# Hello world"))),
            )
            advanceUntilIdle()

            assertEquals(2, harness.emissions.size)
            val latest = harness.emissions.last() as DisplayMessageListUpdate.Messages
            assertEquals("# Hello world", latest.messages.single().text)
            assertTrue(latest.messages.single().renderedMarkdown != null)

            harness.preparer.releaseNextPrepareMessages()
            advanceUntilIdle()

            // A stale prepare for "# Hello" must not erase newer plain text.
            val afterRelease = harness.emissions.last() as DisplayMessageListUpdate.Messages
            assertEquals("# Hello world", afterRelease.messages.single().text)
        }

    @Test
    fun structureFirstStreamingPublishesCodeBlockChromeBeforeColors() =
        runTest(testDispatcher) {
            val harness = startHarness(holdDelays = true)
            val fenced =
                """
                ```javascript
                const x = 1
                ```
                """.trimIndent()

            harness.source.emit(MessageListUpdate.Messages(listOf(plainAssistant("seed"))))
            harness.emissions.clear()
            harness.preparer.prepareStructureOnlyInvocations.clear()

            harness.preparer.holdNextPrepareMessages()
            harness.source.emit(
                MessageListUpdate.Messages(listOf(streamingAssistant("msg-1", fenced))),
            )
            harness.delay.release()
            advanceUntilIdle()

            assertEquals(1, harness.emissions.size)
            val frame = harness.emissions.single() as DisplayMessageListUpdate.Messages
            val doc = frame.messages.single().renderedMarkdown
            assertTrue(doc != null)
            assertTrue(
                doc!!.blocks.any { block -> block is MarkdownBlock.CodeBlock },
            )
            val code = doc.blocks.filterIsInstance<MarkdownBlock.CodeBlock>().single()
            assertEquals("javascript", code.language)
            // Structure first: the first frame has empty spans. Colors attach after it.
            assertTrue(code.spans.isEmpty())
            assertTrue(harness.preparer.prepareStructureOnlyInvocations.isNotEmpty())
        }

    @Test
    fun streamingHighlightAttachesColorsAfterTheStructureFrameAndDropsAStalePass() =
        runTest(testDispatcher) {
            val harness = startHarness(holdDelays = true)
            val fenced =
                """
                ```kotlin
                val answer = 42
                ```
                """.trimIndent()
            val colored =
                OpenCodeMarkdownParser.parse(
                    markdown = fenced,
                    codeHighlighter = OpenCodeStructureOnlyHighlighter,
                ).let { structure ->
                    val fence = structure.blocks.filterIsInstance<MarkdownBlock.CodeBlock>().single()
                    structure.copy(
                        blocks =
                            listOf(
                                fence.copy(spans = listOf(CodeSpan(0, 3, CodeTokenRole.Keyword))),
                            ),
                    )
                }
            harness.preparer.streamingHighlight = { plain ->
                if (plain == fenced) StreamingHighlight(colored, partial = false) else null
            }
            harness.preparer.holdNextHighlight()

            harness.source.emit(MessageListUpdate.Messages(listOf(streamingAssistant("msg-1", fenced))))
            harness.delay.release()
            advanceUntilIdle()

            val structureFrame = harness.emissions.single() as DisplayMessageListUpdate.Messages
            assertTrue(structureFrame.messages.single().renderedMarkdown!!.blocks.filterIsInstance<MarkdownBlock.CodeBlock>().single().spans.isEmpty())

            harness.preparer.releaseNextHighlight()
            advanceUntilIdle()

            val coloredFrame = harness.emissions.last() as DisplayMessageListUpdate.Messages
            assertEquals(
                listOf(CodeSpan(0, 3, CodeTokenRole.Keyword)),
                coloredFrame.messages.single().renderedMarkdown!!.blocks.filterIsInstance<MarkdownBlock.CodeBlock>().single().spans,
            )

            val marker =
                colored.copy(
                    blocks =
                        listOf(
                            (colored.blocks.single() as MarkdownBlock.CodeBlock).copy(
                                spans = listOf(CodeSpan(0, 1, CodeTokenRole.String)),
                            ),
                        ),
                )
            harness.preparer.streamingHighlight = { plain ->
                if (plain == fenced) StreamingHighlight(marker, partial = false) else null
            }
            harness.preparer.holdNextHighlight()
            val longer = "$fenced\nmore"
            harness.source.emit(MessageListUpdate.Messages(listOf(streamingAssistant("msg-1", longer))))
            harness.delay.release()
            advanceUntilIdle()
            // Releasing the cancelled pass must not paint its spans onto the newer text.
            harness.preparer.releaseNextHighlight()
            advanceUntilIdle()

            val latest = harness.emissions.last() as DisplayMessageListUpdate.Messages
            assertEquals(longer, latest.messages.single().text)
            val spans = latest.messages.single().renderedMarkdown!!.blocks.filterIsInstance<MarkdownBlock.CodeBlock>().single().spans
            assertTrue(spans.none { it.role == CodeTokenRole.String })
        }

    @Test
    fun carryingCodeSpansKeepsColorsOnAGrowingFence() {
        val first =
            MarkdownDocument(
                sourceHash = 1,
                blocks =
                    listOf(
                        MarkdownBlock.CodeBlock(
                            language = "kotlin",
                            code = "val a",
                            lines = listOf("val a"),
                            spans = listOf(CodeSpan(0, 3, CodeTokenRole.Keyword)),
                        ),
                    ),
            )
        val grown =
            MarkdownDocument(
                sourceHash = 2,
                blocks =
                    listOf(
                        MarkdownBlock.CodeBlock(
                            language = "kotlin",
                            code = "val answer",
                            lines = listOf("val answer"),
                            spans = emptyList(),
                        ),
                    ),
            )
        val carried = grown.carryingCodeSpans(first)
        assertEquals(listOf(CodeSpan(0, 3, CodeTokenRole.Keyword)), carried.blocks.filterIsInstance<MarkdownBlock.CodeBlock>().single().spans)

        val rewritten =
            MarkdownDocument(
                sourceHash = 3,
                blocks =
                    listOf(
                        MarkdownBlock.CodeBlock(
                            language = "kotlin",
                            code = "fun answer",
                            lines = listOf("fun answer"),
                            spans = emptyList(),
                        ),
                    ),
            )
        assertTrue(rewritten.carryingCodeSpans(first).blocks.filterIsInstance<MarkdownBlock.CodeBlock>().single().spans.isEmpty())
    }

    @Test
    fun structureFirstStreamingPreservesSiblingAwaitingTextMateUpgrade() =
        runTest(testDispatcher) {
            val harness = startHarness(holdDelays = true)
            val history =
                (1..5).map { index ->
                    markdownAssistant(
                        id = "hist-$index",
                        text = "# Heading $index\n\n" + "Some prose with `code`. ".repeat(80),
                    )
                }
            require(
                history.sumOf { it.text.length } >=
                    MessageContentPreparer.HISTORY_PLAIN_FIRST_MARKDOWN_CHARACTER_THRESHOLD,
            )

            harness.source.emit(MessageListUpdate.Messages(history))
            advanceUntilIdle()

            val formatFirst =
                harness.emissions.filterIsInstance<DisplayMessageListUpdate.Messages>().last()
            assertTrue(formatFirst.messages.all { it.awaitingTextMateUpgrade })
            assertTrue(formatFirst.messages.all { it.renderedMarkdown != null })

            harness.emissions.clear()
            val fenced =
                """
                ```javascript
                const x = 1
                ```
                """.trimIndent()
            harness.source.emit(
                MessageListUpdate.Messages(history + streamingAssistant("stream-1", fenced)),
            )
            advanceUntilIdle()
            if (harness.delay.hasPending()) {
                harness.delay.release()
                advanceUntilIdle()
            }

            val frame =
                harness.emissions.filterIsInstance<DisplayMessageListUpdate.Messages>().last()
            val siblings = frame.messages.filter { it.id.startsWith("hist-") }
            assertEquals(5, siblings.size)
            assertTrue(
                "structure-first must preserve awaitingTextMateUpgrade on non-streaming siblings",
                siblings.all { it.awaitingTextMateUpgrade && it.renderedMarkdown != null },
            )
            assertTrue(frame.messages.any { it.id == "stream-1" })
        }

    @Test
    fun largeStreamingStillFormatsMarkdownStructure() =
        runTest(testDispatcher) {
            val harness = startHarness()
            val largeText = "# " + "x".repeat(MessageContentPreparer.STREAMING_PLAIN_TEXT_CHARACTER_THRESHOLD)

            harness.source.emit(MessageListUpdate.Messages(listOf(streamingAssistant("msg-1", largeText))))
            advanceUntilIdle()
            testScheduler.advanceTimeBy(100)
            advanceUntilIdle()

            val streamingFrames = harness.emissions.filterIsInstance<DisplayMessageListUpdate.Messages>()
            assertTrue(streamingFrames.isNotEmpty())
            assertTrue(
                streamingFrames.any { frame -> frame.messages.single().renderedMarkdown != null },
            )
            assertTrue(harness.preparer.prepareStructureOnlyInvocations.isNotEmpty())
        }

    @Test
    fun largeStreamingSkipsPreparesUntilFinalization() =
        runTest(testDispatcher) {
            val harness = startHarness()
            val largeText = "# " + "x".repeat(MessageContentPreparer.STREAMING_PLAIN_TEXT_CHARACTER_THRESHOLD)

            harness.source.emit(MessageListUpdate.Messages(listOf(streamingAssistant("msg-1", largeText))))
            advanceUntilIdle()
            testScheduler.advanceTimeBy(100)
            advanceUntilIdle()

            assertTrue(
                harness.preparer.prepareMessagesInvocations.none {
                    it.mode == MessagePreparationMode.Streaming
                },
            )

            harness.emissions.clear()
            harness.preparer.prepareMessagesInvocations.clear()
            harness.source.emit(MessageListUpdate.Messages(listOf(finalAssistant("msg-1", largeText))))
            advanceUntilIdle()

            assertEquals(
                listOf(MessagePreparationMode.FinalPlaceholder, MessagePreparationMode.Final),
                harness.preparer.prepareMessagesInvocations.map { it.mode },
            )
        }

    @Test
    fun cacheSyncConfirmed_clearsFromCacheFlagBeforeInFlightPublish() =
        runTest(testDispatcher) {
            // Regression: CacheSyncConfirmed must clear sourceIsFromCache so an
            // in-flight cache-seed prepare cannot publish Syncing again.
            val harness = startHarness()
            harness.preparer.holdNextPrepareMessages()
            harness.source.emit(
                MessageListUpdate.Messages(listOf(plainAssistant("cached")), isFromCache = true),
            )
            advanceUntilIdle()
            assertTrue(harness.preparer.hasPendingPrepareMessagesHold())
            assertTrue(harness.emissions.isEmpty())

            harness.source.emit(MessageListUpdate.CacheSyncConfirmed())
            advanceUntilIdle()
            assertTrue(harness.emissions.single() is DisplayMessageListUpdate.CacheSyncConfirmed)

            harness.preparer.releaseNextPrepareMessages()
            advanceUntilIdle()

            val messages =
                harness.emissions.filterIsInstance<DisplayMessageListUpdate.Messages>()
            assertEquals(1, messages.size)
            assertFalse(messages.single().isFromCache)
            assertEquals(
                "cached",
                messages
                    .single()
                    .messages
                    .single()
                    .text,
            )
        }

    @Test
    fun networkFrameIdenticalToCacheSeed_confirmsCacheSync() =
        runTest(testDispatcher) {
            // Regression: a network frame equal to the cache-seeded one was skipped as a
            // no-op without confirming the cache, which could leave Syncing… up.
            val harness = startHarness()
            harness.source.emit(
                MessageListUpdate.Messages(listOf(plainAssistant("cached")), isFromCache = true),
            )
            advanceUntilIdle()
            val seeded = harness.emissions.filterIsInstance<DisplayMessageListUpdate.Messages>()
            assertTrue(seeded.isNotEmpty() && seeded.all { it.isFromCache })

            harness.source.emit(
                MessageListUpdate.Messages(listOf(plainAssistant("cached")), isFromCache = false),
            )
            advanceUntilIdle()

            assertTrue(harness.emissions.last() is DisplayMessageListUpdate.CacheSyncConfirmed)
        }

    private fun TestScope.startHarness(holdDelays: Boolean = false): Harness {
        val harness = Harness(holdDelays = holdDelays)
        harness.collectJob =
            backgroundScope.launch {
                harness.pipeline.observeDisplayMessages("ses-1", null).collect {
                    harness.emissions += it
                }
            }
        return harness
    }

    private class Harness(
        holdDelays: Boolean,
    ) {
        val source = ControllableSnapshotSource()
        val preparer = ControllableDisplayMessagePreparing()
        val timeSource = TestTimeSource()
        val delay = ControllableDelay(timeSource = timeSource, hold = holdDelays)
        val emissions = mutableListOf<DisplayMessageListUpdate>()
        lateinit var collectJob: Job
        val pipeline =
            DisplayMessagePipeline(
                snapshotSource = source,
                contentPreparer = preparer,
                timeSource = timeSource,
                delayMillis = delay::delay,
            )
    }

    private class ControllableDelay(
        private val timeSource: TestTimeSource,
        private val hold: Boolean,
    ) {
        private var pending: CompletableDeferred<Unit>? = null
        private var pendingDurationMs: Long? = null

        var callCount: Int = 0
            private set

        fun pendingMs(): Long? = pendingDurationMs

        fun hasPending(): Boolean = pending?.isCompleted == false

        fun resetCallCount() {
            callCount = 0
        }

        fun release() {
            pending?.complete(Unit)
        }

        suspend fun delay(ms: Long) {
            callCount += 1
            timeSource += ms.milliseconds
            if (!hold) return
            pendingDurationMs = ms
            val gate = CompletableDeferred<Unit>()
            pending = gate
            gate.await()
            pending = null
            pendingDurationMs = null
        }
    }

    private class ControllableSnapshotSource : DisplayMessageSnapshotSource {
        private val channel = Channel<MessageListUpdate>(capacity = Channel.UNLIMITED)

        override fun observeMessages(
            sessionId: String,
            directory: String?,
        ): Flow<MessageListUpdate> = channel.receiveAsFlow()

        suspend fun emit(update: MessageListUpdate) {
            channel.send(update)
        }

        fun close() {
            channel.close()
        }
    }

    private class ControllableDisplayMessagePreparing : DisplayMessagePreparing {
        data class PrepareMessagesInvocation(
            val messages: List<Message>,
            val mode: MessagePreparationMode,
        )

        val prepareMessagesInvocations = mutableListOf<PrepareMessagesInvocation>()
        val prepareStructureOnlyInvocations = mutableListOf<PrepareStructureOnlyInvocation>()
        private var prepareMessagesHold: CompletableDeferred<Unit>? = null
        private var activePrepareMessagesHold: CompletableDeferred<Unit>? = null
        private var prepareMessageHold: CompletableDeferred<Unit>? = null
        private var activePrepareMessageHold: CompletableDeferred<Unit>? = null
        private val documentSerial = AtomicInteger(1)
        var minimumPrepareDelayMs: Long? = null

        data class PrepareStructureOnlyInvocation(
            val blockID: String,
            val plain: String,
            val sessionKey: String,
        )

        fun holdNextPrepareMessages() {
            prepareMessagesHold = CompletableDeferred()
        }

        fun releaseNextPrepareMessages() {
            (activePrepareMessagesHold ?: prepareMessagesHold)?.complete(Unit)
        }

        fun hasPendingPrepareMessagesHold(): Boolean =
            prepareMessagesHold?.isCompleted == false || activePrepareMessagesHold?.isCompleted == false

        fun holdNextPrepareMessage() {
            prepareMessageHold = CompletableDeferred()
        }

        fun releaseNextPrepareMessage() {
            (activePrepareMessageHold ?: prepareMessageHold)?.complete(Unit)
        }

        fun hasPendingPrepareMessageHold(): Boolean =
            prepareMessageHold?.isCompleted == false || activePrepareMessageHold?.isCompleted == false

        override suspend fun prepareMessages(
            messages: List<Message>,
            previousById: Map<String, DisplayMessage>,
            mode: MessagePreparationMode,
        ): List<DisplayMessage> {
            prepareMessagesInvocations +=
                PrepareMessagesInvocation(
                    messages = messages,
                    mode = mode,
                )
            // Take the hold so a concurrent newer prepare is not blocked by it.
            val hold = prepareMessagesHold
            prepareMessagesHold = null
            if (hold != null) {
                activePrepareMessagesHold = hold
                hold.await()
                activePrepareMessagesHold = null
            }
            minimumPrepareDelayMs?.let { delay(it) }
            return messages.map { message ->
                prepareMessageOnWorker(
                    message = message,
                    previous = previousById[message.id],
                    mode = mode,
                )
            }
        }

        override suspend fun prepareMessage(
            message: Message,
            previous: DisplayMessage?,
            mode: MessagePreparationMode,
        ): DisplayMessage {
            val hold = prepareMessageHold
            prepareMessageHold = null
            if (hold != null) {
                activePrepareMessageHold = hold
                hold.await()
                activePrepareMessageHold = null
            }
            return prepareMessageOnWorker(
                message = message,
                previous = previous,
                mode = mode,
            )
        }

        var streamingHighlight: ((String) -> StreamingHighlight?)? = null
        val highlightInvocations = mutableListOf<String>()
        private var highlightHold: CompletableDeferred<Unit>? = null
        private var activeHighlightHold: CompletableDeferred<Unit>? = null

        fun holdNextHighlight() {
            highlightHold = CompletableDeferred()
        }

        fun releaseNextHighlight() {
            (activeHighlightHold ?: highlightHold)?.complete(Unit)
        }

        override suspend fun highlightStreamingTextBlock(
            blockID: String,
            plain: String,
            sessionKey: String,
        ): StreamingHighlight? {
            highlightInvocations += plain
            val hold = highlightHold
            highlightHold = null
            if (hold != null) {
                activeHighlightHold = hold
                hold.await()
                activeHighlightHold = null
            }
            return streamingHighlight?.invoke(plain)
        }

        override suspend fun prepareStructureOnlyTextBlock(
            blockID: String,
            plain: String,
            sessionKey: String,
        ): MarkdownDocument? {
            prepareStructureOnlyInvocations +=
                PrepareStructureOnlyInvocation(
                    blockID = blockID,
                    plain = plain,
                    sessionKey = sessionKey,
                )
            // Must never share the prepare hold — format-first is text-lane work.
            if (!OpenCodeMarkdownDetector.shouldRender(plain)) {
                return null
            }
            return OpenCodeMarkdownParser.parse(
                markdown = plain,
                codeHighlighter = OpenCodeStructureOnlyHighlighter,
            )
        }

        private fun prepareMessageOnWorker(
            message: Message,
            previous: DisplayMessage?,
            mode: MessagePreparationMode,
        ): DisplayMessage {
            if (mode == MessagePreparationMode.HistoryPlainFirst) {
                if (!OpenCodeMarkdownDetector.shouldRender(message.text)) {
                    return DisplayMessage(message = message, renderedMarkdown = null)
                }
                val document =
                    OpenCodeMarkdownParser.parse(
                        markdown = message.text,
                        codeHighlighter = OpenCodeStructureOnlyHighlighter,
                    )
                return DisplayMessage(
                    message = message,
                    renderedMarkdown = document,
                    awaitingTextMateUpgrade = true,
                )
            }
            if (mode == MessagePreparationMode.FinalPlaceholder && previous?.renderedMarkdown != null) {
                return DisplayMessage(message = message, renderedMarkdown = previous.renderedMarkdown)
            }
            val document =
                MarkdownDocument(
                    sourceHash = documentSerial.getAndIncrement(),
                    blocks =
                        listOf(
                            MarkdownBlock.Paragraph(
                                inlines = listOf(MarkdownInline.Text(message.text)),
                            ),
                        ),
                )
            return DisplayMessage(message = message, renderedMarkdown = document)
        }
    }

    private fun plainAssistant(text: String): Message =
        Message(
            id = "msg-plain",
            role = MessageRole.Assistant,
            text = text,
            parts = listOf(MessagePart.Text(text = text, isStreaming = false)),
        )

    private fun streamingAssistant(
        id: String,
        text: String,
    ): Message =
        Message(
            id = id,
            role = MessageRole.Assistant,
            text = text,
            parts = listOf(MessagePart.Text(text = text, isStreaming = true)),
        )

    private fun finalAssistant(
        id: String,
        text: String,
    ): Message =
        Message(
            id = id,
            role = MessageRole.Assistant,
            text = text,
            parts = listOf(MessagePart.Text(text = text, isStreaming = false)),
        )

    private fun largeMarkdownAssistant(id: String): Message {
        val text = "# Heading\n\nSome prose with `code`. ".repeat(400)
        require(text.length >= MessageContentPreparer.HISTORY_PLAIN_FIRST_MARKDOWN_CHARACTER_THRESHOLD)
        require(text.length >= MessageContentPreparer.HISTORY_ENRICH_VIEWPORT_CHARACTER_BUDGET)
        return markdownAssistant(id = id, text = text)
    }

    private fun markdownAssistant(
        id: String,
        text: String,
    ): Message =
        Message(
            id = id,
            role = MessageRole.Assistant,
            text = text,
            parts = listOf(MessagePart.Text(text = text, isStreaming = false)),
        )
}
