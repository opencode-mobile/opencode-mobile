package dev.opencode.mobile.domain.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThoughtPartChromeTest {
    @Test
    fun paintLabel_matchesStreamingSettledAndHidden() {
        assertEquals(
            "thinking",
            ThoughtPartChrome.paintLabel(
                MessagePart.Reasoning(text = "", isStreaming = true),
            ),
        )
        assertEquals(
            "thought",
            ThoughtPartChrome.paintLabel(
                MessagePart.Reasoning(text = "done", isStreaming = false),
            ),
        )
        assertEquals(
            "hidden",
            ThoughtPartChrome.paintLabel(
                MessagePart.Reasoning(text = "", isStreaming = false),
            ),
        )
        assertEquals(
            "thinking",
            ThoughtPartChrome.paintLabel(
                MessagePart.Reasoning(text = "", isStreaming = false, timeEndEpochMs = 2),
            ),
        )
    }

    @Test
    fun summarize_marksEmptyAssistantOmittedAndLastThinking() {
        val summary =
            ThoughtPartChrome.summarize(
                messages =
                    listOf(
                        Message(id = "u1", role = MessageRole.User, text = "Hi"),
                        Message(
                            id = "a1",
                            role = MessageRole.Assistant,
                            text = "",
                            parts =
                                listOf(
                                    MessagePart.Reasoning(
                                        text = "",
                                        isStreaming = true,
                                        id = "r1",
                                    ),
                                ),
                        ),
                    ),
                paintedCount = 1,
            )
        assertTrue(summary.contains("store=2"))
        assertTrue(summary.contains("painted=1"))
        assertTrue(summary.contains("omitted=0"))
        assertTrue(summary.contains("thinking=1"))
        assertTrue(summary.contains("last=thinking"))
        assertTrue(summary.contains("streaming=1"))
    }

    @Test
    fun summarize_keepsBlankCompletedReasoningAsThinking() {
        val summary =
            ThoughtPartChrome.summarize(
                messages =
                    listOf(
                        Message(id = "u1", role = MessageRole.User, text = "Hi"),
                        Message(
                            id = "a1",
                            role = MessageRole.Assistant,
                            text = "",
                            parts =
                                listOf(
                                    MessagePart.Reasoning(
                                        text = "",
                                        isStreaming = false,
                                        id = "r1",
                                        timeEndEpochMs = 2,
                                    ),
                                ),
                        ),
                    ),
                paintedCount = 1,
            )
        assertTrue(summary.contains("thinking=1"))
        assertTrue(summary.contains("omitted=0"))
        assertTrue(summary.contains("last=thinking"))
        assertTrue(summary.contains("end=1"))
    }
}
