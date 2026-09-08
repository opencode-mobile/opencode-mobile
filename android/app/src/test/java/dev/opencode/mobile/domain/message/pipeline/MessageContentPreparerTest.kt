package dev.opencode.mobile.domain.message

import dev.opencode.mobile.domain.preferences.MarkdownHighlightPreferences
import dev.opencode.mobile.domain.textmate.TextMateLogbookRecorder
import dev.opencode.mobile.markdown.CodeSpan
import dev.opencode.mobile.markdown.CodeTokenRole
import dev.opencode.mobile.markdown.MarkdownBlock
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.markdown.MarkdownInline
import dev.opencode.mobile.markdown.OpenCodeCodeHighlighter
import dev.opencode.mobile.markdown.OpenCodeTextMateHighlightingEngine
import dev.opencode.mobile.markdown.SyntaxHighlightResult
import dev.opencode.mobile.markdown.TextMateParseObservation
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
        MessageContentPreparer(OpenCodeTextMateHighlightingEngine(RecordingHighlighter())).apply {
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
                    highlightEpoch = MarkdownHighlightPreferences.epoch,
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
                    highlightEpoch = MarkdownHighlightPreferences.epoch,
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
                    highlightEpoch = MarkdownHighlightPreferences.epoch,
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
    fun prepareMessagesFinalAfterPlaceholderUpgradesToPreferredHighlighter() =
        runTest(testDispatcher) {
            val prior = MarkdownHighlightPreferences.textMateEnabled
            MarkdownHighlightPreferences.updateTextMateEnabled(true)
            try {
                val highlighter = RecordingHighlighter()
                val preparer =
                    MessageContentPreparer(OpenCodeTextMateHighlightingEngine(highlighter)).apply {
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
                assertEquals(0, highlighter.calls) // semantic fallback while streaming
                assertNotNull(streaming.renderedMarkdown)

                val placeholder =
                    preparer
                        .prepareMessages(
                            listOf(finalMessage),
                            mapOf("m1" to streaming),
                            mode = MessagePreparationMode.FinalPlaceholder,
                        ).single()
                assertSame(streaming.renderedMarkdown, placeholder.renderedMarkdown)
                assertEquals(0, highlighter.calls)
                assertTrue(placeholder.awaitingTextMateUpgrade)

                // Pipeline commits placeholder (isStreaming=false) then Final with that as previous.
                val finalized =
                    preparer
                        .prepareMessages(
                            listOf(finalMessage),
                            mapOf("m1" to placeholder),
                            mode = MessagePreparationMode.Final,
                        ).single()

                assertEquals(1, highlighter.calls)
                assertNotSame(streaming.renderedMarkdown, finalized.renderedMarkdown)
                assertTrue(!finalized.awaitingTextMateUpgrade)
            } finally {
                MarkdownHighlightPreferences.updateTextMateEnabled(prior)
            }
        }

    @Test
    fun finalTextMateCacheMissLogsOnceWhileStreamingAndCacheHitsStayQuiet() =
        runTest(testDispatcher) {
            val prior = MarkdownHighlightPreferences.textMateEnabled
            MarkdownHighlightPreferences.updateTextMateEnabled(true)
            try {
                val logbook = RecordingLogbook()
                val preparer =
                    MessageContentPreparer(OpenCodeTextMateHighlightingEngine(RecordingHighlighter()), logbook).apply {
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
                assertTrue(logbook.observations.isEmpty())

                preparer.prepareMessage(final)
                preparer.prepareMessage(final)

                assertEquals(1, logbook.observations.size)
                assertEquals("kotlin", logbook.observations.single().requestedLanguage)
                assertEquals(markdown.length, logbook.observations.single().messageCharacters)
                assertEquals("val answer = 42".length, logbook.observations.single().fenceCharacters)
            } finally {
                MarkdownHighlightPreferences.updateTextMateEnabled(prior)
            }
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
            val prior = MarkdownHighlightPreferences.textMateEnabled
            MarkdownHighlightPreferences.updateTextMateEnabled(true)
            try {
                val highlighter = BlockingHighlighter()
                val preparer =
                    MessageContentPreparer(OpenCodeTextMateHighlightingEngine(highlighter)).apply {
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
            } finally {
                MarkdownHighlightPreferences.updateTextMateEnabled(prior)
            }
        }

    @Test
    fun prepareFinalPlaceholderDoesNotWaitForBlockedHighlighter() =
        runBlocking {
            val prior = MarkdownHighlightPreferences.textMateEnabled
            MarkdownHighlightPreferences.updateTextMateEnabled(true)
            try {
                val highlighter = BlockingHighlighter()
                val preparer =
                    MessageContentPreparer(OpenCodeTextMateHighlightingEngine(highlighter)).apply {
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
            } finally {
                MarkdownHighlightPreferences.updateTextMateEnabled(prior)
            }
        }

    @Test
    fun prepareTextBlock_highlighterThrow_fallsBackWithoutThrowing() =
        runTest(testDispatcher) {
            val prior = MarkdownHighlightPreferences.textMateEnabled
            MarkdownHighlightPreferences.updateTextMateEnabled(true)
            try {
                val preparer =
                    MessageContentPreparer(OpenCodeTextMateHighlightingEngine(ThrowingHighlighter())).apply {
                        computationDispatcher = testDispatcher
                    }
                val fenced =
                    """
                    ```javascript
                    const x = 1
                    ```
                    """.trimIndent()
                val document =
                    preparer.prepareTextBlock(
                        blockID = "block-1",
                        plain = fenced,
                        isStreaming = false,
                        sessionKey = "ses-1",
                        mode = MessagePreparationMode.Final,
                    )
                // TextMate engine catches highlighter failures and keeps a semantic
                // document rather than throwing into the pipeline.
                assertNotNull(document)
                assertTrue(document!!.blocks.any { it is MarkdownBlock.CodeBlock })
                assertTrue(
                    document.diagnostics.any { diagnostic ->
                        diagnostic.message.contains("fallback", ignoreCase = true) ||
                            diagnostic.kind.name.contains("Fallback", ignoreCase = true)
                    },
                )
            } finally {
                MarkdownHighlightPreferences.updateTextMateEnabled(prior)
            }
        }

    private class ThrowingHighlighter : OpenCodeCodeHighlighter {
        override fun supportsLanguage(languageOrAlias: String?): Boolean = true

        override fun highlight(
            languageOrAlias: String?,
            code: String,
        ): SyntaxHighlightResult {
            error("injected highlighter failure")
        }
    }

    private class RecordingHighlighter : OpenCodeCodeHighlighter {
        var calls: Int = 0

        override fun supportsLanguage(languageOrAlias: String?): Boolean = true

        override fun highlight(
            languageOrAlias: String?,
            code: String,
        ): SyntaxHighlightResult {
            calls += 1
            return SyntaxHighlightResult(
                spans = listOf(CodeSpan(0, code.length, CodeTokenRole.Keyword)),
                diagnostics = emptyList(),
            )
        }
    }

    private class RecordingLogbook : TextMateLogbookRecorder {
        val observations = mutableListOf<TextMateParseObservation>()

        override fun submit(observations: List<TextMateParseObservation>) {
            this.observations += observations
        }
    }

    private class BlockingHighlighter : OpenCodeCodeHighlighter {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun supportsLanguage(languageOrAlias: String?): Boolean = true

        override fun highlight(
            languageOrAlias: String?,
            code: String,
        ): SyntaxHighlightResult {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release highlighter" }
            return SyntaxHighlightResult(spans = emptyList(), diagnostics = emptyList())
        }
    }
}
