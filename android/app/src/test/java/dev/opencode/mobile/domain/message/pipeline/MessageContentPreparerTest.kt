package dev.opencode.mobile.domain.message

import dev.opencode.mobile.markdown.CodeSpan
import dev.opencode.mobile.markdown.CodeTokenRole
import dev.opencode.mobile.markdown.HighlightCoreHighlightingEngine
import dev.opencode.mobile.markdown.HighlightRequest
import dev.opencode.mobile.markdown.HighlightResult
import dev.opencode.mobile.markdown.MarkdownBlock
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.markdown.MarkdownInline
import dev.opencode.mobile.markdown.TextMateHighlightingEngine
import dev.opencode.mobile.markdown.TextMateParseOutcome
import dev.opencode.mobile.markdown.emptyResult
import dev.opencode.mobile.markdown.stableMarkdownSourceHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MessageContentPreparerTest {
    private val testDispatcher = StandardTestDispatcher()

    private fun createPreparer(): MessageContentPreparer =
        MessageContentPreparer(RecordingEngine()).apply {
            computationDispatcher = testDispatcher
        }

    @Test
    fun prepareMessageReturnsNullForPlainText() =
        runTest(testDispatcher) {
            val preparer = createPreparer()
            val display =
                preparer.prepareMessage(
                    Message(id = "m1", role = MessageRole.Assistant, text = "Plain text stays native."),
                )
            assertNull(display.renderedMarkdown)
        }

    @Test
    fun prepareMessageReturnsDocumentForMarkdownText() =
        runTest(testDispatcher) {
            val preparer = createPreparer()
            val display =
                preparer.prepareMessage(
                    Message(id = "m1", role = MessageRole.Assistant, text = "# Heading"),
                )
            assertNotNull(display.renderedMarkdown)
            assertEquals(1, display.renderedMarkdown!!.blocks.size)
        }

    @Test
    fun streamingTextBlockRendersTableOnceDelimiterRowStarts() =
        runTest(testDispatcher) {
            val preparer = createPreparer()
            val plain = "Here is the summary:\n\n| File | Lines | Status |\n|"
            val structureFirst = preparer.prepareStructureOnlyTextBlock(blockID = "b1", plain = plain)
            val table = structureFirst!!.blocks.last() as MarkdownBlock.Table
            assertEquals(3, table.header.cells.size)
            assertTrue(table.rows.isEmpty())
        }

    @Test
    fun prepareMessageUsesCacheForSameMessageSnapshot() =
        runTest(testDispatcher) {
            val preparer = createPreparer()
            val markdown = "```kotlin\nval answer = 42\n```"
            val message = Message(id = "m1", role = MessageRole.Assistant, text = markdown)
            val first = preparer.prepareMessage(message)
            val second = preparer.prepareMessage(message)
            assertSame(first.renderedMarkdown, second.renderedMarkdown)
        }

    @Test
    fun prepareMessageUsesStreamingFlagInCacheKey() =
        runTest(testDispatcher) {
            val preparer = createPreparer()
            val markdown = "```kotlin\nval answer = 42\n```"
            val streamingMessage =
                Message(
                    id = "m1",
                    role = MessageRole.Assistant,
                    text = markdown,
                    parts = listOf(MessagePart.Text(text = markdown, isStreaming = true)),
                )
            val finalMessage =
                Message(
                    id = "m1",
                    role = MessageRole.Assistant,
                    text = markdown,
                )
            val streaming = preparer.prepareMessage(streamingMessage)
            val finalParse = preparer.prepareMessage(finalMessage)
            assertNotNull(streaming.renderedMarkdown)
            assertNotNull(finalParse.renderedMarkdown)
            assert(streaming.renderedMarkdown !== finalParse.renderedMarkdown)
        }

    @Test
    fun prepareMessageDoesNotTreatHashCollisionAsTheSameSnapshot() =
        runTest(testDispatcher) {
            val preparer = createPreparer()
            val first =
                preparer.prepareMessage(
                    Message(id = "m1", role = MessageRole.Assistant, text = "# Aa"),
                )
            val second =
                preparer.prepareMessage(
                    Message(id = "m1", role = MessageRole.Assistant, text = "# BB"),
                )
            assertNotNull(first.renderedMarkdown)
            assertNotNull(second.renderedMarkdown)
            assertNotSame(first.renderedMarkdown, second.renderedMarkdown)
        }

    @Test
    fun prepareMessagesReusesPreviousDocumentWhileStreaming() =
        runTest(testDispatcher) {
            val preparer = createPreparer()
            val previousDoc =
                MarkdownDocument(
                    sourceHash = "# Partial".hashCode(),
                    blocks =
                        listOf(
                            MarkdownBlock.Heading(
                                level = 1,
                                inlines = listOf(MarkdownInline.Text("Partial")),
                            ),
                        ),
                )
            val previous =
                DisplayMessage(
                    message =
                        Message(
                            id = "m1",
                            role = MessageRole.Assistant,
                            text = "# Partial",
                            parts = listOf(MessagePart.Text(text = "# Partial", isStreaming = true)),
                        ),
                    renderedMarkdown = previousDoc,
                )
            val message =
                Message(
                    id = "m1",
                    role = MessageRole.Assistant,
                    text = "# Partial",
                    parts = listOf(MessagePart.Text(text = "# Partial", isStreaming = true)),
                )
            val prepared = preparer.prepareMessages(listOf(message), mapOf("m1" to previous))
            assertSame(previousDoc, prepared.first().renderedMarkdown)
        }

    @Test
    fun prepareMessagesReusesExistingFinalDocumentWithoutComparingSourceHashes() =
        runTest(testDispatcher) {
            val preparer = createPreparer()
            val document =
                MarkdownDocument(
                    sourceHash = 17,
                    blocks = listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text("Heading")))),
                )
            val previous =
                DisplayMessage(
                    message = Message(id = "m1", role = MessageRole.Assistant, text = "# Heading"),
                    renderedMarkdown = document,
                )
            val message = Message(id = "m1", role = MessageRole.Assistant, text = "# Heading")
            val prepared = preparer.prepareMessages(listOf(message), mapOf("m1" to previous))
            assertSame(document, prepared.first().renderedMarkdown)
        }

    @Test
    fun prepareMessagesDoesNotReuseFinalDocumentWhenTextChanges() =
        runTest(testDispatcher) {
            val preparer = createPreparer()
            val previous =
                DisplayMessage(
                    message = Message(id = "m1", role = MessageRole.Assistant, text = "# Old"),
                    renderedMarkdown =
                        MarkdownDocument(
                            sourceHash = 17,
                            blocks = listOf(MarkdownBlock.Heading(1, listOf(MarkdownInline.Text("Old")))),
                        ),
                )

            val prepared =
                preparer.prepareMessages(
                    messages = listOf(Message(id = "m1", role = MessageRole.Assistant, text = "# New")),
                    previousById = mapOf("m1" to previous),
                )

            assertNotSame(previous.renderedMarkdown, prepared.single().renderedMarkdown)
            assertEquals(stableMarkdownSourceHash("# New"), prepared.single().renderedMarkdown?.sourceHash)
        }

    @Test
    fun prepareMessagesReusesStreamingDocumentAsFinalPlaceholder() =
        runTest(testDispatcher) {
            val preparer = createPreparer()
            val previousDoc =
                MarkdownDocument(
                    sourceHash = "# Partial".hashCode(),
                    blocks =
                        listOf(
                            MarkdownBlock.Heading(
                                level = 1,
                                inlines = listOf(MarkdownInline.Text("Partial")),
                            ),
                        ),
                )
            val previous =
                DisplayMessage(
                    message =
                        Message(
                            id = "m1",
                            role = MessageRole.Assistant,
                            text = "# Partial",
                            parts = listOf(MessagePart.Text(text = "# Partial", isStreaming = true)),
                        ),
                    renderedMarkdown = previousDoc,
                )
            val finalMessage =
                Message(
                    id = "m1",
                    role = MessageRole.Assistant,
                    text = "# Partial",
                    parts = listOf(MessagePart.Text(text = "# Partial", isStreaming = false)),
                )
            val placeholder =
                preparer.prepareMessages(
                    listOf(finalMessage),
                    mapOf("m1" to previous),
                    mode = MessagePreparationMode.FinalPlaceholder,
                )
            assertSame(previousDoc, placeholder.first().renderedMarkdown)
        }

    @Test
    fun prepareMessagesFinalAfterPlaceholderAttachesEngineColors() =
        runTest(testDispatcher) {
            val engine = RecordingEngine()
            val preparer =
                MessageContentPreparer(engine).apply {
                    computationDispatcher = testDispatcher
                }
            val markdown = "```kotlin\nval answer = 42\n```"
            val streamingMessage =
                Message(
                    id = "m1",
                    role = MessageRole.Assistant,
                    text = markdown,
                    parts = listOf(MessagePart.Text(text = markdown, isStreaming = true)),
                )
            val finalMessage =
                Message(
                    id = "m1",
                    role = MessageRole.Assistant,
                    text = markdown,
                    parts = listOf(MessagePart.Text(text = markdown, isStreaming = false)),
                )

            val streaming = preparer.prepareMessages(listOf(streamingMessage)).single()
            // Structure only while streaming: the fence paints without colors.
            assertTrue(engine.requests.isEmpty())
            val streamingFence = streaming.renderedMarkdown!!.codeBlock()
            assertTrue(streamingFence.spans.isEmpty())

            val placeholder =
                preparer
                    .prepareMessages(
                        listOf(finalMessage),
                        mapOf("m1" to streaming),
                        mode = MessagePreparationMode.FinalPlaceholder,
                    ).single()
            assertSame(streaming.renderedMarkdown, placeholder.renderedMarkdown)
            assertTrue(engine.requests.isEmpty())
            assertTrue(placeholder.awaitingTextMateUpgrade)

            // Pipeline commits placeholder (isStreaming=false) then Final with that as previous.
            val finalized =
                preparer
                    .prepareMessages(
                        listOf(finalMessage),
                        mapOf("m1" to placeholder),
                        mode = MessagePreparationMode.Final,
                    ).single()

            assertEquals(listOf("kotlin"), engine.requests.map { it.language })
            assertEquals("val answer = 42", engine.requests.single().source)
            assertNotSame(streaming.renderedMarkdown, finalized.renderedMarkdown)
            assertEquals(
                listOf(CodeSpan(0, "val answer = 42".length, CodeTokenRole.Keyword)),
                finalized.renderedMarkdown!!.codeBlock().spans,
            )
            assertTrue(!finalized.awaitingTextMateUpgrade)
        }

    @Test
    fun highlightStreamingTextBlockColorsFencesWithoutTheSizeGate() =
        runTest(testDispatcher) {
            val engine = RecordingEngine()
            val preparer =
                MessageContentPreparer(engine).apply {
                    computationDispatcher = testDispatcher
                }
            val markdown = "```kotlin\nval answer = 42\n```" + "x".repeat(MessageContentPreparer.STREAMING_PLAIN_TEXT_CHARACTER_THRESHOLD)
            val highlighted = preparer.highlightStreamingTextBlock(blockID = "m1", plain = markdown)

            assertEquals("kotlin", engine.requests.single().language)
            assertEquals(
                listOf(CodeSpan(0, engine.requests.single().source.length, CodeTokenRole.Keyword)),
                highlighted!!.document.codeBlock().spans,
            )
            assertTrue(!highlighted.partial)

            val streaming =
                preparer.prepareMessage(
                    Message(
                        id = "m1",
                        role = MessageRole.Assistant,
                        text = markdown,
                        parts = listOf(MessagePart.Text(text = markdown, isStreaming = true)),
                    ),
                    mode = MessagePreparationMode.Streaming,
                )
            assertNotNull(streaming.renderedMarkdown)
        }

    @Test
    fun finalPrepareColorsFencesThroughHighlightCore() =
        runTest(testDispatcher) {
            val preparer =
                MessageContentPreparer(HighlightCoreHighlightingEngine()).apply {
                    computationDispatcher = testDispatcher
                }
            val markdown = "```kotlin\nval answer = 42\n```\n\n```brainfuck\n++[>+<-]\n```"
            val final = preparer.prepareMessage(Message(id = "m1", role = MessageRole.Assistant, text = markdown))

            val fences = final.renderedMarkdown!!.blocks.filterIsInstance<MarkdownBlock.CodeBlock>()
            assertTrue(fences[0].spans.any { it.role == CodeTokenRole.Keyword })
            // Unsupported languages attach as plain text.
            assertTrue(fences[1].spans.isEmpty())
        }

    @Test
    fun finalCacheMissHighlightsOnceAndCacheHitsSkipTheEngine() =
        runTest(testDispatcher) {
            val engine = RecordingEngine()
            val preparer =
                MessageContentPreparer(engine).apply {
                    computationDispatcher = testDispatcher
                }
            val markdown = "```kotlin\nval answer = 42\n```"
            val streaming =
                Message(
                    id = "m1",
                    role = MessageRole.Assistant,
                    text = markdown,
                    parts = listOf(MessagePart.Text(text = markdown, isStreaming = true)),
                )
            val final = Message(id = "m1", role = MessageRole.Assistant, text = markdown)

            preparer.prepareMessage(streaming)
            assertTrue(engine.requests.isEmpty())

            preparer.prepareMessage(final)
            preparer.prepareMessage(final)

            assertEquals(1, engine.requests.size)
            assertEquals("kotlin", engine.requests.single().language)
        }

    @Test
    fun prepareMessagesUsesCacheForRepeatedSnapshots() =
        runTest(testDispatcher) {
            val preparer = createPreparer()
            val markdown = "```kotlin\nval answer = 42\n```"
            val message = Message(id = "m1", role = MessageRole.Assistant, text = markdown)
            val first = preparer.prepareMessages(listOf(message)).first().renderedMarkdown
            val second = preparer.prepareMessages(listOf(message)).first().renderedMarkdown
            assertSame(first, second)
        }

    @Test
    fun prepareStreamingMessageDoesNotWaitForBlockedFinalHighlighter() =
        runBlocking {
            val highlighter = BlockingEngine()
            val preparer =
                MessageContentPreparer(highlighter).apply {
                    computationDispatcher = Dispatchers.Default
                }
            val finalPreparation =
                async(Dispatchers.Default) {
                    preparer.prepareMessage(
                        Message(
                            id = "history",
                            role = MessageRole.Assistant,
                            text = "```kotlin\nval history = true\n```",
                        ),
                    )
                }

            assertTrue(highlighter.entered.await(2, TimeUnit.SECONDS))
            try {
                val streamingMarkdown = "```kotlin\nval live = true\n```"
                val streaming =
                    withTimeout(2_000) {
                        preparer.prepareMessage(
                            Message(
                                id = "live",
                                role = MessageRole.Assistant,
                                text = streamingMarkdown,
                                parts = listOf(MessagePart.Text(streamingMarkdown, isStreaming = true)),
                            ),
                        )
                    }
                assertNotNull(streaming.renderedMarkdown)
            } finally {
                highlighter.release.countDown()
            }
            finalPreparation.await()
            Unit
        }

    @Test
    fun prepareFinalPlaceholderDoesNotWaitForBlockedHighlighter() =
        runBlocking {
            val highlighter = BlockingEngine()
            val preparer =
                MessageContentPreparer(highlighter).apply {
                    computationDispatcher = Dispatchers.Default
                }
            val markdown = "```kotlin\nval live = true\n```"
            val streaming =
                preparer.prepareMessage(
                    Message(
                        id = "live",
                        role = MessageRole.Assistant,
                        text = markdown,
                        parts = listOf(MessagePart.Text(markdown, isStreaming = true)),
                    ),
                )
            assertNotNull(streaming.renderedMarkdown)

            val finalPreparation =
                async(Dispatchers.Default) {
                    preparer.prepareMessages(
                        listOf(
                            Message(
                                id = "live",
                                role = MessageRole.Assistant,
                                text = markdown,
                            ),
                        ),
                        mapOf("live" to streaming),
                        MessagePreparationMode.Final,
                    )
                }
            assertTrue(highlighter.entered.await(2, TimeUnit.SECONDS))
            try {
                val placeholder =
                    withTimeout(2_000) {
                        preparer.prepareMessages(
                            listOf(
                                Message(
                                    id = "live",
                                    role = MessageRole.Assistant,
                                    text = markdown,
                                ),
                            ),
                            mapOf("live" to streaming),
                            MessagePreparationMode.FinalPlaceholder,
                        )
                    }
                assertNotNull(placeholder.single().renderedMarkdown)
                assertTrue(placeholder.single().awaitingTextMateUpgrade)
            } finally {
                highlighter.release.countDown()
            }
            finalPreparation.await()
            Unit
        }

    @Test
    fun failedEngineResultKeepsTheStructureDocument() =
        runTest(testDispatcher) {
            val preparer =
                MessageContentPreparer(FailingEngine()).apply {
                    computationDispatcher = testDispatcher
                }
            val fenced =
                """
                ```javascript
                const x = 1
                ```
                """.trimIndent()
            val display = preparer.prepareMessage(Message(id = "m1", role = MessageRole.Assistant, text = fenced))
            // A failed fence is rejected, not attached: the code block stays plain.
            val code = display.renderedMarkdown!!.codeBlock()
            assertEquals("const x = 1", code.code)
            assertTrue(code.spans.isEmpty())
        }

    private fun MarkdownDocument.codeBlock(): MarkdownBlock.CodeBlock = blocks.filterIsInstance<MarkdownBlock.CodeBlock>().single()

    /** Colors the whole fence as one keyword and records every request. */
    private class RecordingEngine : TextMateHighlightingEngine {
        val requests = mutableListOf<HighlightRequest>()

        override suspend fun highlight(request: HighlightRequest): HighlightResult {
            requests += request
            return emptyResult(request, TextMateParseOutcome.Highlighted).copy(
                canonicalLanguage = request.language,
                spans = listOf(CodeSpan(0, request.source.length, CodeTokenRole.Keyword)),
            )
        }
    }

    private class FailingEngine : TextMateHighlightingEngine {
        override suspend fun highlight(request: HighlightRequest): HighlightResult = emptyResult(request, TextMateParseOutcome.Failed)
    }

    private class BlockingEngine : TextMateHighlightingEngine {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override suspend fun highlight(request: HighlightRequest): HighlightResult {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release highlighter" }
            return emptyResult(request, TextMateParseOutcome.NoSpans)
        }
    }
}
