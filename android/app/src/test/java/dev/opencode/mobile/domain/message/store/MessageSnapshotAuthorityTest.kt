package dev.opencode.mobile.domain.message

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSnapshotAuthorityTest {
    @Test
    fun shorterRemoteTextIsBehind() {
        assertFalse(
            MessageSnapshotAuthority.isAheadOrEqual(
                remote = listOf(assistant("Hi", streaming = false)),
                local = listOf(assistant("Hello", streaming = true)),
            ),
        )
    }

    @Test
    fun longerRemoteTextIsAhead() {
        assertTrue(
            MessageSnapshotAuthority.isAheadOrEqual(
                remote = listOf(assistant("Hello there", streaming = false)),
                local = listOf(assistant("Hello", streaming = true)),
            ),
        )
    }

    @Test
    fun equalTextWithRemoteFinalizedIsAhead() {
        assertTrue(
            MessageSnapshotAuthority.isAheadOrEqual(
                remote = listOf(assistant("Hello", streaming = false)),
                local = listOf(assistant("Hello", streaming = true)),
            ),
        )
    }

    @Test
    fun equalSnapshotsAreAheadOrEqual() {
        assertTrue(
            MessageSnapshotAuthority.isAheadOrEqual(
                remote = listOf(assistant("Hello", streaming = true)),
                local = listOf(assistant("Hello", streaming = true)),
            ),
        )
    }

    private fun assistant(
        text: String,
        streaming: Boolean,
    ): Message =
        Message(
            id = "msg-2",
            role = MessageRole.Assistant,
            text = text,
            parts = listOf(MessagePart.Text(text = text, isStreaming = streaming)),
        )
}
