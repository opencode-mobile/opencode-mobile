package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SessionChatChildSessionLinkerTest {
    @Test
    fun link_runningSubagentWithoutId_matchesChildByDescriptionPrefix() {
        val messages = listOf(assistant("msg-1", subagent("prt-1", description = "Android dictation card")))
        val children =
            listOf(
                child("ses-other", title = "Unrelated work", createdAt = 1),
                child("ses-child", title = "Android dictation card (@general subagent)", createdAt = 2),
            )

        val linked = SessionChatChildSessionLinker.link(messages, children)

        assertEquals(listOf("ses-child"), sessionIds(linked))
    }

    @Test
    fun link_metadataIdWinsAndIsNotReusedForAnotherPart() {
        val messages =
            listOf(
                assistant("msg-1", subagent("prt-1", description = "Explore", metadataSessionId = "ses-a")),
                assistant("msg-2", subagent("prt-2", description = "Explore")),
            )
        val children =
            listOf(
                child("ses-a", title = "Explore", createdAt = 1),
                child("ses-b", title = "Explore", createdAt = 2),
            )

        val linked = SessionChatChildSessionLinker.link(messages, children)

        assertEquals(listOf("ses-a", "ses-b"), sessionIds(linked))
    }

    @Test
    fun link_parallelSameDescription_mapsInSpawnOrder() {
        val messages =
            listOf(
                assistant(
                    "msg-1",
                    subagent("prt-1", description = "Review"),
                    subagent("prt-2", description = "Review"),
                ),
            )
        val children =
            listOf(
                child("ses-late", title = "Review", createdAt = 20),
                child("ses-early", title = "Review", createdAt = 10),
            )

        val linked = SessionChatChildSessionLinker.link(messages, children)

        assertEquals(listOf("ses-early", "ses-late"), sessionIds(linked))
    }

    @Test
    fun link_resumedSubagent_usesInputSessionId() {
        val messages =
            listOf(assistant("msg-1", subagent("prt-1", description = "Continue", inputSessionId = "ses-resumed")))

        val linked = SessionChatChildSessionLinker.link(messages, emptyList())

        assertEquals(listOf("ses-resumed"), sessionIds(linked))
    }

    @Test
    fun link_noMatchingChild_leavesPartUnlinked() {
        val messages = listOf(assistant("msg-1", subagent("prt-1", description = "Explore")))

        val linked =
            SessionChatChildSessionLinker.link(
                messages,
                listOf(child("ses-other", title = "Something else", createdAt = 1)),
            )

        assertEquals(messages, linked)
    }

    @Test
    fun link_nonSubagentToolsUntouched() {
        val shell =
            MessagePart.Tool(
                toolName = "shell",
                status = ToolStatus.Running,
                id = "prt-1",
                input = buildJsonObject { put("description", "Explore") },
            )
        val messages = listOf(assistant("msg-1", shell))

        val linked =
            SessionChatChildSessionLinker.link(
                messages,
                listOf(child("ses-child", title = "Explore", createdAt = 1)),
            )

        assertSame(messages, linked)
    }

    @Test
    fun unlinkedPartIds_skipsPartsWithOwnId() {
        val messages =
            listOf(
                assistant(
                    "msg-1",
                    subagent("prt-1", description = "A", metadataSessionId = "ses-a"),
                    subagent("prt-2", description = "B", inputSessionId = "ses-b"),
                    subagent("prt-3", description = "C"),
                    subagent("prt-4", description = null),
                ),
            )

        assertEquals(setOf("prt-3"), SessionChatChildSessionLinker.unlinkedPartIds(messages))
    }

    private fun assistant(
        id: String,
        vararg parts: MessagePart,
    ) = Message(id = id, role = MessageRole.Assistant, text = "", parts = parts.toList())

    private fun subagent(
        id: String,
        description: String?,
        metadataSessionId: String? = null,
        inputSessionId: String? = null,
    ) = MessagePart.Tool(
        toolName = "subagent",
        status = ToolStatus.Running,
        id = id,
        input =
            buildJsonObject {
                put("agent", "general")
                description?.let { put("description", it) }
                inputSessionId?.let { put("sessionID", it) }
            },
        metadata = metadataSessionId?.let { sessionId -> buildJsonObject { put("sessionID", sessionId) } },
    )

    private fun child(
        id: String,
        title: String,
        createdAt: Long,
    ) = SessionChatChildSessionCandidate(id = id, title = title, createdAtEpochMs = createdAt)

    private fun sessionIds(messages: List<Message>): List<String> =
        messages
            .flatMap { it.parts }
            .filterIsInstance<MessagePart.Tool>()
            .mapNotNull { (it.metadata?.get("sessionID") as? JsonPrimitive)?.contentOrNull }
}
