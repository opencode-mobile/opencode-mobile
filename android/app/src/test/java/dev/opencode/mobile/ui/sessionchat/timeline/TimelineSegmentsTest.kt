package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.TimelineSegments
import dev.opencode.mobile.domain.message.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** The display pipeline's sole-text-block rule agrees with the blocks the timeline builds. */
class TimelineSegmentsTest {
    private fun text(
        id: String?,
        value: String = "text $id",
    ) = MessagePart.Text(text = value, id = id)

    private fun tool(
        id: String?,
        name: String = "bash",
        status: ToolStatus = ToolStatus.Completed,
    ) = MessagePart.Tool(toolName = name, status = status, id = id)

    private val cases: List<Pair<MessageRole, List<MessagePart>>> =
        listOf(
            MessageRole.Assistant to listOf(text("a")),
            MessageRole.Assistant to listOf(text("a"), text("b")),
            MessageRole.Assistant to listOf(text("a"), tool("t1"), text("b")),
            MessageRole.Assistant to listOf(text("a"), tool("t1", name = "todowrite"), text("b")),
            MessageRole.User to listOf(text("a"), tool("t1"), text("b")),
            MessageRole.Assistant to listOf(text(null)),
            MessageRole.Assistant to listOf(text("a"), tool(null)),
            MessageRole.Assistant to listOf(tool("r1", name = "read"), text("blank", value = "  "), tool("r2", name = "grep"), text("b")),
            MessageRole.Assistant to listOf(tool("r1", name = "read"), MessagePart.Reasoning(text = "thought", id = null), tool("r2", name = "glob")),
            MessageRole.Assistant to listOf(text("a"), MessagePart.Reasoning(text = "thought", id = "th")),
            MessageRole.Assistant to listOf(MessagePart.Compaction(id = null, completed = true), text("a")),
            MessageRole.Assistant to listOf(tool("t1")),
            MessageRole.Assistant to emptyList(),
        )

    @Test
    fun hasSoleTextBlock_matchesBuiltTextBlocks() {
        for ((role, parts) in cases) {
            for (fallback in listOf("fallback", " ")) {
                val built =
                    SessionChatTimelineBlocks.buildOrFallback(
                        role = role,
                        parts = parts,
                        messageId = "m1",
                        fallbackText = fallback,
                    )
                val expected = built.count { it is SessionChatTimelineBlock.Text } == 1
                assertEquals(
                    "role=$role parts=$parts fallback='$fallback'",
                    expected,
                    TimelineSegments.hasSoleTextBlock(role, parts, fallback),
                )
            }
        }
    }
}
