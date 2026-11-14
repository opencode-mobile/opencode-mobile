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

    @Test
    fun hiddenTextDoesNotOutweighTheRow() {
        // A cached hidden goal kickoff carries the plugin's long instructions; REST shows it as a notice.
        val hidden =
            Message(
                id = "msg_k",
                role = MessageRole.User,
                text = "A goal is now active for this session.",
                parts = listOf(MessagePart.Text(text = "A goal is now active for this session.", isSynthetic = true)),
            )
        val notice =
            Message(
                id = "msg_k",
                role = MessageRole.Notice,
                text = "",
                parts = listOf(MessagePart.Notice(id = "msg_k", notice = TimelineNotice.GoalSet("goal_1", "Goal: Ship it"))),
            )
        assertTrue(MessageSnapshotAuthority.isAheadOrEqual(remote = listOf(notice), local = listOf(hidden)))
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
