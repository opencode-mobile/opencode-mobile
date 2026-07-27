package dev.opencode.mobile.domain.message

import dev.opencode.mobile.markdown.MarkdownBlock
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.markdown.MarkdownInline
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
            assertEquals(MessagePreparationMode.Final, harness.preparer.prepareMessagesInvocations.single().mode)
        }

    @Test
    fun largeHistoryPublishesPlainFirstThenAtMostTwoEnrichmentFrames() =
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

            assertEquals(1, harness.emissions.size)
            val plainFirst = harness.emissions.single() as DisplayMessageListUpdate.Messages
            assertTrue(plainFirst.messages.all { it.renderedMarkdown == null })
            assertEquals(MessagePreparationMode.HistoryPlainFirst, harness.preparer.prepareMessagesInvocations.single().mode)
            assertEquals(48L, harness.delay.pendingMs())

            harness.delay.release()
            advanceUntilIdle()

            val enrichmentFrames =
                harness.emissions.drop(1).filterIsInstance<DisplayMessageListUpdate.Messages>()
            assertTrue(enrichmentFrames.isNotEmpty())
            assertTrue(
                "expected at most two enrichment frames, got ${enrichmentFrames.size}",
                enrichmentFrames.size <= 2,
            )

            val finalFrame = enrichmentFrames.last()
            assertEquals(5, finalFrame.messages.size)
            assertTrue(finalFrame.messages.all { it.renderedMarkdown != null })
            // Newest-first: first enrichment frame should include the newest message styled.
            assertTrue(enrichmentFrames.first().messages.last().renderedMarkdown != null)
        }

    @Test
    fun largeSingleMessageHistoryPublishesPlainFirstThenOneViewportEnrichment() =
        runTest(testDispatcher) {
            val harness = startHarness(holdDelays = true)

            val large = largeMarkdownAssistant("msg-1")
            harness.source.emit(MessageListUpdate.Messages(listOf(large)))

            assertEquals(1, harness.emissions.size)
            assertEquals(48L, harness.delay.pendingMs())

            harness.delay.release()
            advanceUntilIdle()

            val enrichmentFrames =
                harness.emissions.drop(1).filterIsInstance<DisplayMessageListUpdate.Messages>()
            assertEquals(1, enrichmentFrames.size)
            assertTrue(enrichmentFrames.single().messages.single().renderedMarkdown != null)
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
            assertEquals("xxxxxxxx", frames.last().messages.single().text)
            assertTrue(frames.last().messages.single().isStreaming)
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

            val large = largeMarkdownAssistant("history")
            harness.preparer.holdNextPrepareMessage()
            harness.source.emit(MessageListUpdate.Messages(listOf(large)))
            assertEquals(1, harness.emissions.size)
            assertEquals(48L, harness.delay.pendingMs())

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
            assertEquals(null, plain.messages.single().renderedMarkdown)
            assertTrue(
                "decoration prepare may be held, but must not block plain",
                harness.preparer.hasPendingPrepareMessagesHold() ||
                    harness.preparer.prepareMessagesInvocations.isNotEmpty() ||
                    harness.preparer.prepareTextBlockInvocations.isNotEmpty(),
            )

            harness.timeSource += STREAMING_PREPARE_MAX_BATCH_MS.milliseconds
            harness.source.emit(
                MessageListUpdate.Messages(listOf(streamingAssistant("msg-1", "# Hello world"))),
            )
            advanceUntilIdle()

            assertEquals(2, harness.emissions.size)
            val latest = harness.emissions.last() as DisplayMessageListUpdate.Messages
            assertEquals("# Hello world", latest.messages.single().text)
            assertEquals(null, latest.messages.single().renderedMarkdown)

            harness.preparer.releaseNextPrepareMessages()
            advanceUntilIdle()

            // Stale decoration for "# Hello" must not erase newer plain text.
            val afterRelease = harness.emissions.last() as DisplayMessageListUpdate.Messages
            assertEquals("# Hello world", afterRelease.messages.single().text)
        }

    @Test
    fun largeStreamingSkipsDecorationUntilFinalization() =
        runTest(testDispatcher) {
            val harness = startHarness()
            val largeText = "# " + "x".repeat(MessageContentPreparer.STREAMING_PLAIN_TEXT_CHARACTER_THRESHOLD)

            harness.source.emit(MessageListUpdate.Messages(listOf(streamingAssistant("msg-1", largeText))))
            advanceUntilIdle()
            testScheduler.advanceTimeBy(100)
            advanceUntilIdle()

            val streamingFrames = harness.emissions.filterIsInstance<DisplayMessageListUpdate.Messages>()
            assertTrue(streamingFrames.isNotEmpty())
            assertTrue(streamingFrames.all { frame -> frame.messages.single().renderedMarkdown == null })
            assertTrue(
                harness.preparer.prepareMessagesInvocations.none {
                    it.mode == MessagePreparationMode.Streaming
                },
            )
            assertTrue(harness.preparer.prepareTextBlockInvocations.isEmpty())

            harness.emissions.clear()
            harness.preparer.prepareMessagesInvocations.clear()
            harness.source.emit(MessageListUpdate.Messages(listOf(finalAssistant("msg-1", largeText))))
            advanceUntilIdle()

            assertEquals(
                listOf(MessagePreparationMode.FinalPlaceholder, MessagePreparationMode.Final),
                harness.preparer.prepareMessagesInvocations.map { it.mode },
            )
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

        fun pendingMs(): Long? = pendingDurationMs

        fun hasPending(): Boolean = pending?.isCompleted == false

        fun release() {
            pending?.complete(Unit)
        }

        suspend fun delay(ms: Long) {
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
        val prepareTextBlockInvocations = mutableListOf<PrepareTextBlockInvocation>()
        private var prepareMessagesHold: CompletableDeferred<Unit>? = null
        private var activePrepareMessagesHold: CompletableDeferred<Unit>? = null
        private var prepareMessageHold: CompletableDeferred<Unit>? = null
        private var activePrepareMessageHold: CompletableDeferred<Unit>? = null
        private val documentSerial = AtomicInteger(1)
        var minimumPrepareDelayMs: Long? = null

        data class PrepareTextBlockInvocation(
            val blockID: String,
            val plain: String,
            val isStreaming: Boolean,
            val sessionKey: String,
            val mode: MessagePreparationMode,
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

        override suspend fun prepareTextBlock(
            blockID: String,
            plain: String,
            isStreaming: Boolean,
            sessionKey: String,
            mode: MessagePreparationMode,
        ): MarkdownDocument? {
            prepareTextBlockInvocations +=
                PrepareTextBlockInvocation(
                    blockID = blockID,
                    plain = plain,
                    isStreaming = isStreaming,
                    sessionKey = sessionKey,
                    mode = mode,
                )
            // Share the prepareMessages hold so characterization can block decoration
            // without delaying the plain text lane.
            val hold = prepareMessagesHold
            prepareMessagesHold = null
            if (hold != null) {
                activePrepareMessagesHold = hold
                hold.await()
                activePrepareMessagesHold = null
            }
            minimumPrepareDelayMs?.let { delay(it) }
            if (mode == MessagePreparationMode.Streaming &&
                plain.length >= MessageContentPreparer.STREAMING_PLAIN_TEXT_CHARACTER_THRESHOLD
            ) {
                return null
            }
            return MarkdownDocument(
                sourceHash = documentSerial.getAndIncrement(),
                blocks =
                    listOf(
                        MarkdownBlock.Paragraph(
                            inlines = listOf(MarkdownInline.Text(plain)),
                        ),
                    ),
            )
        }

        private fun prepareMessageOnWorker(
            message: Message,
            previous: DisplayMessage?,
            mode: MessagePreparationMode,
        ): DisplayMessage {
            if (mode == MessagePreparationMode.HistoryPlainFirst) {
                return DisplayMessage(message = message, renderedMarkdown = null)
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
