package dev.opencode.mobile.domain.message

import dev.opencode.mobile.markdown.MarkdownBlock
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.markdown.MarkdownInline
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class BlockDecorationCacheTest {
    @Before
    fun setUp() {
        BlockDecorationCache.clear()
    }

    @After
    fun tearDown() {
        BlockDecorationCache.clear()
    }

    @Test
    fun putAndGetRoundTrip() {
        val document = doc("Hello")
        BlockDecorationCache.put(
            sessionKey = "ses-1",
            blockID = "p:part-1",
            textHash = 11,
            textLength = 5,
            isStreaming = true,
            document = document,
        )
        assertSame(
            document,
            BlockDecorationCache.get(
                sessionKey = "ses-1",
                blockID = "p:part-1",
                textHash = 11,
                textLength = 5,
                isStreaming = true,
            ),
        )
    }

    @Test
    fun streamingFlagIsPartOfKey() {
        val streaming = doc("stream")
        val finalDoc = doc("final")
        BlockDecorationCache.put("ses", "b1", 1, 6, isStreaming = true, streaming)
        BlockDecorationCache.put("ses", "b1", 1, 6, isStreaming = false, finalDoc)
        assertSame(streaming, BlockDecorationCache.get("ses", "b1", 1, 6, isStreaming = true))
        assertSame(finalDoc, BlockDecorationCache.get("ses", "b1", 1, 6, isStreaming = false))
    }

    @Test
    fun contentRevisionMismatchMisses() {
        BlockDecorationCache.put("ses", "b1", textHash = 1, textLength = 3, isStreaming = false, doc("abc"))
        assertNull(BlockDecorationCache.get("ses", "b1", textHash = 2, textLength = 3, isStreaming = false))
        assertNull(BlockDecorationCache.get("ses", "b1", textHash = 1, textLength = 4, isStreaming = false))
    }

    @Test
    fun evictsOldestWhenOverCapacity() {
        repeat(40) { index ->
            BlockDecorationCache.put(
                sessionKey = "ses",
                blockID = "block-$index",
                textHash = index,
                textLength = index,
                isStreaming = false,
                document = doc("d-$index"),
            )
        }
        assertEquals(32, BlockDecorationCache.size())
        assertNull(BlockDecorationCache.get("ses", "block-0", 0, 0, isStreaming = false))
        assertEquals(
            "d-39".hashCode(),
            BlockDecorationCache.get("ses", "block-39", 39, 39, isStreaming = false)?.sourceHash,
        )
    }

    private fun doc(text: String): MarkdownDocument =
        MarkdownDocument(
            sourceHash = text.hashCode(),
            blocks = listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text(text)))),
        )
}
