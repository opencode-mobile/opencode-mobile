package dev.opencode.mobile.domain.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecorationBacklogTest {
    @Test
    fun dropsLowestPriorityUnderCountPressure() {
        val backlog = DecorationBacklog()
        repeat(DecorationBacklogPolicy.PENDING_COUNT_CAPACITY) { index ->
            assertTrue(
                backlog.tryEnqueue(
                    job(
                        blockID = "history-$index",
                        length = 10,
                        priority = 0,
                    ),
                ),
            )
        }
        assertEquals(DecorationBacklogPolicy.PENDING_COUNT_CAPACITY, backlog.size)

        assertTrue(
            backlog.tryEnqueue(
                job(
                    blockID = "streaming-block",
                    length = 10,
                    priority = 2,
                ),
            ),
        )
        assertEquals(DecorationBacklogPolicy.PENDING_COUNT_CAPACITY, backlog.size)

        val ids = generateSequence { backlog.poll() }.map { it.blockID }.toSet()
        assertTrue(ids.contains("streaming-block"))
        assertFalse(ids.contains("history-0"))
    }

    @Test
    fun dropsLowestPriorityUnderCharCostPressure() {
        val backlog = DecorationBacklog()
        val large = DecorationBacklogPolicy.PENDING_CHAR_COST_BUDGET / 2 + 1
        assertTrue(
            backlog.tryEnqueue(
                job(
                    blockID = "history-large",
                    length = large,
                    priority = 0,
                ),
            ),
        )
        assertTrue(
            backlog.tryEnqueue(
                job(
                    blockID = "streaming-large",
                    length = large,
                    priority = 2,
                ),
            ),
        )
        assertEquals(1, backlog.size)
        val kept = backlog.poll()
        assertEquals("streaming-large", kept?.blockID)
        assertNull(backlog.poll())
    }

    @Test
    fun replaceStreamingKeepsLatestCandidateOnlyPerBlock() {
        val backlog = DecorationBacklog()
        assertTrue(
            backlog.replaceStreamingJob(
                job(blockID = "block-1", length = 4, priority = 2, text = "abcd"),
            ),
        )
        assertTrue(
            backlog.replaceStreamingJob(
                job(blockID = "block-1", length = 5, priority = 2, text = "abcde"),
            ),
        )
        assertEquals(1, backlog.size)
        val job = backlog.poll()
        assertEquals(5, job?.textLength)
        assertEquals("abcde".hashCode(), job?.textHash)
        assertEquals("block-1", job?.blockID)
    }

    @Test
    fun replaceStreamingCoalescesByBlockIdNotMessage() {
        val backlog = DecorationBacklog()
        assertTrue(
            backlog.replaceStreamingJob(
                job(blockID = "block-a", length = 3, priority = 2, text = "aaa"),
            ),
        )
        assertTrue(
            backlog.replaceStreamingJob(
                job(blockID = "block-b", length = 3, priority = 2, text = "bbb"),
            ),
        )
        assertEquals(2, backlog.size)
        assertTrue(
            backlog.replaceStreamingJob(
                job(blockID = "block-a", length = 4, priority = 2, text = "aaaa"),
            ),
        )
        assertEquals(2, backlog.size)
        val jobs = generateSequence { backlog.poll() }.toList()
        assertEquals(setOf("block-a", "block-b"), jobs.map { it.blockID }.toSet())
        assertEquals(4, jobs.first { it.blockID == "block-a" }.textLength)
    }

    private fun job(
        blockID: String,
        length: Int,
        priority: Int,
        text: String = "x".repeat(length),
    ): DecorationJob =
        DecorationJob(
            generation = 1,
            sessionKey = "ses-1",
            blockID = blockID,
            textHash = text.hashCode(),
            textLength = text.length,
            isStreaming = priority == 2,
            messages =
                listOf(
                    Message(
                        id = "msg-$blockID",
                        role = MessageRole.Assistant,
                        text = text,
                        parts = listOf(MessagePart.Text(text = text, isStreaming = priority == 2)),
                    ),
                ),
            priority = priority,
        )
}
