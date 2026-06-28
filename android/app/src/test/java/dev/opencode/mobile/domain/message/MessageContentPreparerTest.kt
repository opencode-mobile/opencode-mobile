package dev.opencode.mobile.domain.message

import dev.opencode.mobile.markdown.CodeSpan
import dev.opencode.mobile.markdown.CodeTokenRole
import dev.opencode.mobile.markdown.MarkdownBlock
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.markdown.MarkdownInline
import dev.opencode.mobile.markdown.OpenCodeCodeHighlighter
import dev.opencode.mobile.markdown.SyntaxHighlightResult
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MessageContentPreparerTest {
    private val testDispatcher = StandardTestDispatcher()

    private fun createPreparer(): MessageContentPreparer =
        MessageContentPreparer(RecordingHighlighter()).apply {
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
    fun prepareMessagesUsesCacheForRepeatedSnapshots() =
        runTest(testDispatcher) {
            val preparer = createPreparer()
            val markdown = "```kotlin\nval answer = 42\n```"
            val message = Message(id = "m1", role = MessageRole.Assistant, text = markdown)
            val first = preparer.prepareMessages(listOf(message)).first().renderedMarkdown
            val second = preparer.prepareMessages(listOf(message)).first().renderedMarkdown
            assertSame(first, second)
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
}
