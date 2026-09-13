package dev.opencode.mobile.domain.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageWindowMergeTest {
    @Test
    fun applyTail_preservesOlderThanOverlapAndUpsertsTail() {
        val local = messages("a", "b", "c", "d", "e")
        val tail = messages("c", "d", "e")
        assertEquals(listOf("a", "b", "c", "d", "e"), MessageWindowMerge.applyTail(local, tail).map { it.id })
    }

    @Test
    fun applyTail_noOverlapResetsToTail() {
        val local = messages("a", "b", "c")
        val tail = messages("x", "y")
        assertEquals(listOf("x", "y"), MessageWindowMerge.applyTail(local, tail).map { it.id })
    }

    @Test
    fun applyTail_dropsNewerLocalDisprovedByTailAndKeepsAfterTail() {
        val local = messages("a", "b", "c", "ghost", "d", "live")
        val tail = messages("c", "d")
        assertEquals(listOf("a", "b", "c", "d", "live"), MessageWindowMerge.applyTail(local, tail).map { it.id })
    }

    @Test
    fun applyTail_emptyTailKeepsLocal() {
        val local = messages("a", "b")
        assertEquals(listOf("a", "b"), MessageWindowMerge.applyTail(local, emptyList()).map { it.id })
    }

    @Test
    fun applyTail_overlappingKeepsLocalSseRowsNewerThanRemoteTail() {
        val local = messages("old", "mid", "tail-end", "sse-new")
        val tail = messages("mid", "tail-end")
        assertEquals(
            listOf("old", "mid", "tail-end", "sse-new"),
            MessageWindowMerge.applyTail(local, tail).map { it.id },
        )
    }

    @Test
    fun applyTail_emptyLocalTakesNonemptyTail() {
        val tail = messages("x", "y")
        assertEquals(listOf("x", "y"), MessageWindowMerge.applyTail(emptyList(), tail).map { it.id })
    }

    @Test
    fun prependOlder_deduplicatesAndDoesNotOverwriteLocal() {
        val local = listOf(user("b", "live"), user("c", "live"))
        val older = listOf(user("a", "old"), user("b", "stale"))
        val merged = MessageWindowMerge.prependOlder(local, older)
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
        assertEquals("live", merged.first { it.id == "b" }.text)
    }

    @Test
    fun isTailAcceptable_ignoresOlderLocalPrefix() {
        val local = messages("old-1", "old-2") + listOf(assistant("Hello", streaming = true))
        val tail = listOf(assistant("Hello", streaming = false))
        assertTrue(MessageWindowMerge.isTailAcceptable(remote = tail, local = local))
    }

    @Test
    fun isTailAcceptable_rejectsBehindOverlapWindow() {
        val local = messages("old-1") + listOf(assistant("Hello world", streaming = true))
        val tail = listOf(assistant("Hi", streaming = false))
        assertFalse(MessageWindowMerge.isTailAcceptable(remote = tail, local = local))
    }

    private fun messages(vararg ids: String): List<Message> = ids.map { user(it, it) }

    private fun user(
        id: String,
        text: String,
    ) = Message(id = id, role = MessageRole.User, text = text)

    private fun assistant(
        text: String,
        streaming: Boolean,
    ) = Message(
        id = "msg-2",
        role = MessageRole.Assistant,
        text = text,
        parts = listOf(MessagePart.Text(text = text, isStreaming = streaming)),
    )
}
