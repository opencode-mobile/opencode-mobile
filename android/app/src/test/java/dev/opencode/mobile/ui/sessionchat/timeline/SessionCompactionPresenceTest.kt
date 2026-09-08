package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCompactionPresenceTest {
    @Test
    fun isActive_falseWhenNotBusy() {
        val messages =
            listOf(
                Message(
                    id = "u1",
                    role = MessageRole.User,
                    text = "",
                    parts = listOf(MessagePart.Compaction(id = "c1", auto = false)),
                ),
            )
        assertFalse(SessionCompactionPresence.isActive(messages, isSessionBusy = false))
    }

    @Test
    fun isActive_trueWhenBusyWithCompactionPart() {
        val messages =
            listOf(
                Message(
                    id = "u1",
                    role = MessageRole.User,
                    text = "",
                    parts = listOf(MessagePart.Compaction(id = "c1", auto = true)),
                ),
            )
        assertTrue(SessionCompactionPresence.isActive(messages, isSessionBusy = true))
    }

    @Test
    fun isActive_trueWhenBusyWithCompactionAssistantMode() {
        val messages =
            listOf(
                Message(
                    id = "u1",
                    role = MessageRole.User,
                    text = "prior",
                    mode = "build",
                ),
                Message(
                    id = "a1",
                    role = MessageRole.Assistant,
                    text = "summary",
                    model = "m",
                    mode = "compaction",
                ),
            )
        assertTrue(SessionCompactionPresence.isActive(messages, isSessionBusy = true))
    }

    @Test
    fun isActive_falseWhenBusyWithoutCompactionSignal() {
        val messages =
            listOf(
                Message(
                    id = "u1",
                    role = MessageRole.User,
                    text = "hi",
                    mode = "build",
                ),
                Message(
                    id = "a1",
                    role = MessageRole.Assistant,
                    text = "hello",
                    model = "m",
                    mode = "build",
                ),
            )
        assertFalse(SessionCompactionPresence.isActive(messages, isSessionBusy = true))
    }

    @Test
    fun isActive_ignoresHistoricalCompactionWhenIdle() {
        val messages =
            listOf(
                Message(
                    id = "u1",
                    role = MessageRole.User,
                    text = "",
                    parts = listOf(MessagePart.Compaction(id = "c1")),
                ),
                Message(
                    id = "a1",
                    role = MessageRole.Assistant,
                    text = "done",
                    model = "m",
                    mode = "compaction",
                ),
                Message(
                    id = "u2",
                    role = MessageRole.User,
                    text = "next",
                    mode = "build",
                ),
            )
        assertFalse(SessionCompactionPresence.isActive(messages, isSessionBusy = false))
    }

    @Test
    fun isActive_falseWhenBusyWithCompletedCompactionSnapshot() {
        val messages =
            listOf(
                Message(
                    id = "u1",
                    role = MessageRole.User,
                    text = "hello",
                    mode = "build",
                ),
                Message(
                    id = "c1",
                    role = MessageRole.Assistant,
                    text = "summary",
                    parts = listOf(MessagePart.Compaction(id = "c1", auto = false, completed = true)),
                    mode = "compaction",
                ),
            )
        assertFalse(SessionCompactionPresence.isActive(messages, isSessionBusy = true))
        assertEquals(setOf("c1"), SessionCompactionPresence.completedCompactionIds(messages))
    }
}
