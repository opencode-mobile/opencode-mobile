package dev.opencode.mobile.ui.markdown

import dev.opencode.mobile.markdown.CodeSpan
import dev.opencode.mobile.markdown.CodeTokenRole
import dev.opencode.mobile.markdown.MarkdownBlock
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.markdown.MarkdownInline
import dev.opencode.mobile.markdown.OpenCodeCodeHighlighter
import dev.opencode.mobile.markdown.OpenCodeMarkdownDetector
import dev.opencode.mobile.markdown.SyntaxHighlightResult
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class RenderedMarkdownMapperTest {
    private val testDispatcher = StandardTestDispatcher()

    private fun createMapper(): RenderedMarkdownMapper =
        RenderedMarkdownMapper(RecordingHighlighter()).apply {
            computationDispatcher = testDispatcher
        }

    @Test
    fun parseReturnsNullForPlainText() =
        runTest(testDispatcher) {
            val mapper = createMapper()
            assertNull(mapper.parse(messageId = "m1", text = "Plain text stays native.", isStreaming = false))
        }

    @Test
    fun parseReturnsDocumentForMarkdownText() =
        runTest(testDispatcher) {
            val mapper = createMapper()
            val document = mapper.parse(messageId = "m1", text = "# Heading", isStreaming = false)
            assertNotNull(document)
            assertEquals(1, document!!.blocks.size)
        }

    @Test
    fun parseUsesCacheForSameMessageSnapshot() =
        runTest(testDispatcher) {
            val mapper = createMapper()
            val markdown = "```kotlin\nval answer = 42\n```"
            val first = mapper.parse(messageId = "m1", text = markdown, isStreaming = false)
            val second = mapper.parse(messageId = "m1", text = markdown, isStreaming = false)
            assertSame(first, second)
        }

    @Test
    fun parseUsesStreamingFlagInCacheKey() =
        runTest(testDispatcher) {
            val mapper = createMapper()
            val markdown = "```kotlin\nval answer = 42\n```"
            val streaming = mapper.parse(messageId = "m1", text = markdown, isStreaming = true)
            val finalParse = mapper.parse(messageId = "m1", text = markdown, isStreaming = false)
            assertNotNull(streaming)
            assertNotNull(finalParse)
            assert(streaming !== finalParse)
        }

    @Test
    fun parseDoesNotTreatHashCollisionAsTheSameSnapshot() =
        runTest(testDispatcher) {
            val mapper = createMapper()
            val first = mapper.parse(messageId = "m1", text = "# Aa", isStreaming = false)
            val second = mapper.parse(messageId = "m1", text = "# BB", isStreaming = false)

            assertNotNull(first)
            assertNotNull(second)
            assertNotSame(first, second)
        }

    @Test
    fun enrichRowsLeavesPlainTextWithoutRenderedMarkdown() =
        runTest(testDispatcher) {
            val mapper = createMapper()
            val rows =
                listOf(
                    SessionChatMessageItem(
                        id = "m1",
                        role = MessageRole.Assistant,
                        text = "Plain text stays native.",
                        metadataLabel = "Build",
                    ),
                )
            val enriched = mapper.enrichRows(rows, previousById = emptyMap())
            assertEquals(1, enriched.size)
            assertNull(enriched.first().renderedMarkdown)
        }

    @Test
    fun enrichRowsParsesMarkdownBeforePublish() =
        runTest(testDispatcher) {
            val mapper = createMapper()
            val rows =
                listOf(
                    SessionChatMessageItem(
                        id = "m1",
                        role = MessageRole.Assistant,
                        text = "# Heading",
                        metadataLabel = "Build",
                    ),
                )
            val enriched = mapper.enrichRows(rows, previousById = emptyMap())
            assertNotNull(enriched.first().renderedMarkdown)
            assertEquals(1, enriched.first().renderedMarkdown!!.blocks.size)
        }

    @Test
    fun enrichRowsReusesPreviousDocumentWhileStreaming() =
        runTest(testDispatcher) {
            val mapper = createMapper()
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
                SessionChatMessageItem(
                    id = "m1",
                    role = MessageRole.Assistant,
                    text = "# Partial",
                    metadataLabel = "Build",
                    isStreaming = true,
                    renderedMarkdown = previousDoc,
                )
            val rows =
                listOf(
                    SessionChatMessageItem(
                        id = "m1",
                        role = MessageRole.Assistant,
                        text = "# Partial",
                        metadataLabel = "Build",
                        isStreaming = true,
                    ),
                )
            val enriched = mapper.enrichRows(rows, previousById = mapOf("m1" to previous))
            assertSame(previousDoc, enriched.first().renderedMarkdown)
        }

    @Test
    fun enrichRowsReusesExistingFinalDocumentWithoutComparingSourceHashes() =
        runTest(testDispatcher) {
            val mapper = createMapper()
            val document =
                MarkdownDocument(
                    sourceHash = 17,
                    blocks = listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text("Heading")))),
                )
            val row =
                SessionChatMessageItem(
                    id = "m1",
                    role = MessageRole.Assistant,
                    text = "# Heading",
                    metadataLabel = "Build",
                    renderedMarkdown = document,
                )

            val enriched = mapper.enrichRows(listOf(row), previousById = emptyMap())

            assertSame(document, enriched.first().renderedMarkdown)
        }

    @Test
    fun enrichRowsUsesCacheForRepeatedSnapshots() =
        runTest(testDispatcher) {
            val mapper = createMapper()
            val markdown = "```kotlin\nval answer = 42\n```"
            val row =
                SessionChatMessageItem(
                    id = "m1",
                    role = MessageRole.Assistant,
                    text = markdown,
                    metadataLabel = "Build",
                )
            val first = mapper.enrichRows(listOf(row), previousById = emptyMap()).first().renderedMarkdown
            val second = mapper.enrichRows(listOf(row), previousById = emptyMap()).first().renderedMarkdown
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
