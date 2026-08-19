package dev.opencode.mobile.domain.message

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMessageEngineTest {
    private val store = InMemoryMessageStore(MessageDomainAdapter())
    private val engine = SessionMessageEngine(store)

    @Test
    fun partDelta_returnsChangedAndAdvancesRevision() {
        seedStreamingAssistant()
        val before = engine.revision("ses-1")
        val result =
            engine.apply(
                "ses-1",
                MessagePatch.PartDelta(messageId = "msg-1", partId = "prt-1", delta = " world"),
            )
        assertTrue(result is MessageStoreApplyResult.Changed)
        val changeSet = (result as MessageStoreApplyResult.Changed).changeSet
        assertEquals(before + 1, changeSet.sessionRevision)
        assertEquals(setOf("msg-1"), changeSet.changedMessageIds)
        assertEquals(mapOf("msg-1" to setOf("prt-1")), changeSet.changedPartIdsByMessage)
        assertTrue(engine.shouldPublishLive(changeSet))
        assertEquals("Hello world", engine.messages("ses-1").single().text)
    }

    @Test
    fun infoOnlyInsert_isNotLivePublishable() {
        seedStreamingAssistant()
        val result =
            engine.apply(
                "ses-1",
                MessagePatch.InfoUpdated(
                    buildJsonObject {
                        put("id", "msg-2")
                        put("role", "assistant")
                        put("sessionID", "ses-1")
                    },
                ),
            )
        assertTrue(result is MessageStoreApplyResult.Changed)
        val changeSet = (result as MessageStoreApplyResult.Changed).changeSet
        assertFalse(engine.shouldPublishLive(changeSet))
    }

    @Test
    fun userInsert_landsBeforeStreamingAssistant() {
        engine.replaceAll(
            "ses-1",
            listOf(
                Message(id = "msg-old-user", role = MessageRole.User, text = "Earlier"),
                Message(
                    id = "msg-old-asst",
                    role = MessageRole.Assistant,
                    text = "Done",
                    parts = listOf(MessagePart.Text(text = "Done", id = "prt-old")),
                ),
                Message(
                    id = "msg-live",
                    role = MessageRole.Assistant,
                    text = "Working",
                    parts = listOf(MessagePart.Text(text = "Working", isStreaming = true, id = "prt-live")),
                ),
            ),
        )
        engine.apply(
            "ses-1",
            MessagePatch.InfoUpdated(
                buildJsonObject {
                    put("id", "msg-new-user")
                    put("role", "user")
                    put("sessionID", "ses-1")
                },
            ),
        )
        engine.apply(
            "ses-1",
            MessagePatch.PartUpdated(
                buildJsonObject {
                    put("id", "msg-new-user:text:0")
                    put("messageID", "msg-new-user")
                    put("sessionID", "ses-1")
                    put("type", "text")
                    put("text", "Hello from CLI")
                },
                delta = null,
            ),
        )
        assertEquals(
            listOf("msg-old-user", "msg-old-asst", "msg-new-user", "msg-live"),
            engine.messages("ses-1").map { it.id },
        )
    }

    @Test
    fun partDelta_chunkFlattenEqualsSequentialAppend() {
        seedStreamingAssistant()
        val chunks = listOf(" a", "b", "c", "🚀", "d")
        for (chunk in chunks) {
            engine.apply("ses-1", MessagePatch.PartDelta("msg-1", "prt-1", chunk))
        }
        assertEquals("Hello" + chunks.joinToString(""), engine.messages("ses-1").single().text)
    }

    @Test
    fun infoUpdated_withoutId_isNoChange() {
        seedStreamingAssistant()
        val before = engine.revision("ses-1")
        val result =
            engine.apply(
                "ses-1",
                MessagePatch.InfoUpdated(
                    buildJsonObject {
                        put("role", "assistant")
                        put("sessionID", "ses-1")
                    },
                ),
            )
        assertEquals(MessageStoreApplyResult.NoChange, result)
        assertEquals(before, engine.revision("ses-1"))
        assertEquals(listOf("msg-1"), engine.messages("ses-1").map { it.id })
    }

    @Test
    fun removeUnknownMessage_isNoChange() {
        seedStreamingAssistant()
        val before = engine.revision("ses-1")
        assertEquals(
            MessageStoreApplyResult.NoChange,
            engine.apply("ses-1", MessagePatch.Removed("missing")),
        )
        assertEquals(before, engine.revision("ses-1"))
    }

    @Test
    fun partOrder_preservedAcrossDelta() {
        engine.replaceAll(
            "ses-1",
            listOf(
                Message(
                    id = "msg-1",
                    role = MessageRole.Assistant,
                    text = "AB",
                    parts =
                        listOf(
                            MessagePart.Text(text = "A", id = "prt-a"),
                            MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = "prt-tool"),
                            MessagePart.Text(text = "B", id = "prt-b"),
                        ),
                ),
            ),
        )
        engine.apply("ses-1", MessagePatch.PartDelta("msg-1", "prt-b", "!"))
        val parts = engine.messages("ses-1").single().parts
        assertEquals(3, parts.size)
        assertTrue(parts[0] is MessagePart.Text)
        assertTrue(parts[1] is MessagePart.Tool)
        assertTrue(parts[2] is MessagePart.Text)
        assertEquals("B!", (parts[2] as MessagePart.Text).text)
    }

    @Test
    fun frameAfterChange_splicesChangedMessageAndReusesSiblings() {
        engine.replaceAll(
            "ses-1",
            listOf(
                Message(id = "msg-1", role = MessageRole.User, text = "Hi"),
                Message(
                    id = "msg-2",
                    role = MessageRole.Assistant,
                    text = "Hello",
                    parts = listOf(MessagePart.Text(text = "Hello", isStreaming = true, id = "prt-1")),
                ),
            ),
        )
        val previous = engine.messages("ses-1")
        val result = engine.apply("ses-1", MessagePatch.PartDelta("msg-2", "prt-1", "!"))
        val changeSet = (result as MessageStoreApplyResult.Changed).changeSet
        val next = engine.frameAfterChange("ses-1", changeSet, previous)
        assertEquals(2, next.size)
        assertSame(previous[0], next[0])
        assertEquals("Hello!", next[1].text)
    }

    @Test
    fun frameAfterChange_rebuildsWhenChangedMessageMissingFromPrevious() {
        engine.replaceAll(
            "ses-1",
            listOf(Message(id = "msg-a", role = MessageRole.User, text = "A")),
        )
        val previous = engine.messages("ses-1")
        engine.apply(
            "ses-1",
            MessagePatch.InfoUpdated(
                buildJsonObject {
                    put("id", "msg-b")
                    put("role", "assistant")
                    put("sessionID", "ses-1")
                },
            ),
        )
        engine.apply(
            "ses-1",
            MessagePatch.PartUpdated(
                buildJsonObject {
                    put("id", "prt-b")
                    put("messageID", "msg-b")
                    put("sessionID", "ses-1")
                    put("type", "text")
                    put("text", "")
                },
                delta = null,
            ),
        )
        val result = engine.apply("ses-1", MessagePatch.PartDelta("msg-b", "prt-b", "Live"))
        val changeSet = (result as MessageStoreApplyResult.Changed).changeSet
        val next = engine.frameAfterChange("ses-1", changeSet, previous)
        assertEquals(listOf("msg-a", "msg-b"), next.map { it.id })
        assertEquals("Live", next.last().text)
    }

    @Test
    fun finishStreaming_clearsStreamingFlag() {
        seedStreamingAssistant()
        engine.apply("ses-1", MessagePatch.PartDelta("msg-1", "prt-1", " world"))
        assertTrue(engine.hasStreamingText("ses-1"))
        engine.finishStreamingText("ses-1")
        assertFalse(engine.hasStreamingText("ses-1"))
        assertEquals("Hello world", engine.messages("ses-1").single().text)
    }

    @Test
    fun finishStreaming_clearsReasoningStreamingFlag() {
        engine.replaceAll(
            "ses-1",
            listOf(
                Message(
                    id = "msg-1",
                    role = MessageRole.Assistant,
                    text = "",
                    parts =
                        listOf(
                            MessagePart.Reasoning(text = "hmm", isStreaming = true, id = "prt-r"),
                        ),
                ),
            ),
        )
        engine.apply("ses-1", MessagePatch.PartDelta("msg-1", "prt-r", " more"))
        assertTrue(engine.hasStreamingText("ses-1"))
        engine.finishStreamingText("ses-1")
        assertFalse(engine.hasStreamingText("ses-1"))
        val reasoning =
            engine.messages("ses-1").single().parts.filterIsInstance<MessagePart.Reasoning>().single()
        assertFalse(reasoning.isStreaming)
        assertEquals("hmm more", reasoning.text)
    }

    @Test
    fun revision_isMonotonicAcrossMutations() {
        seedStreamingAssistant()
        val r0 = engine.revision("ses-1")
        engine.apply("ses-1", MessagePatch.PartDelta("msg-1", "prt-1", "a"))
        val r1 = engine.revision("ses-1")
        engine.apply("ses-1", MessagePatch.PartDelta("msg-1", "prt-1", "b"))
        val r2 = engine.revision("ses-1")
        assertTrue(r1 > r0)
        assertTrue(r2 > r1)
    }

    @Test
    fun runningToolWithoutTime_stampsStartAndPreservesAcrossUpdateAndReplaceAll() {
        engine.replaceAll(
            "ses-1",
            listOf(
                Message(
                    id = "msg-1",
                    role = MessageRole.Assistant,
                    text = "",
                    parts = listOf(MessagePart.Tool(toolName = "task", status = ToolStatus.Running, id = "prt-task")),
                ),
            ),
        )
        val seeded = engine.messages("ses-1").single().parts.filterIsInstance<MessagePart.Tool>().single()
        assertTrue(seeded.timeStartEpochMs != null)
        val stamped = seeded.timeStartEpochMs

        engine.apply(
            "ses-1",
            MessagePatch.PartUpdated(
                buildJsonObject {
                    put("id", "prt-task")
                    put("messageID", "msg-1")
                    put("sessionID", "ses-1")
                    put("type", "tool")
                    put("tool", "task")
                    put(
                        "state",
                        buildJsonObject {
                            put("status", "running")
                        },
                    )
                },
                delta = null,
            ),
        )
        val updated = engine.messages("ses-1").single().parts.filterIsInstance<MessagePart.Tool>().single()
        assertEquals(stamped, updated.timeStartEpochMs)

        engine.replaceAll(
            "ses-1",
            listOf(
                Message(
                    id = "msg-1",
                    role = MessageRole.Assistant,
                    text = "",
                    parts =
                        listOf(
                            MessagePart.Tool(
                                toolName = "task",
                                status = ToolStatus.Running,
                                id = "prt-task",
                                timeStartEpochMs = 10L,
                            ),
                        ),
                ),
            ),
        )
        val reloaded = engine.messages("ses-1").single().parts.filterIsInstance<MessagePart.Tool>().single()
        assertEquals(stamped, reloaded.timeStartEpochMs)
    }

    private fun seedStreamingAssistant() {
        engine.replaceAll(
            "ses-1",
            listOf(
                Message(
                    id = "msg-1",
                    role = MessageRole.Assistant,
                    text = "Hello",
                    parts = listOf(MessagePart.Text(text = "Hello", isStreaming = true, id = "prt-1")),
                ),
            ),
        )
    }
}
