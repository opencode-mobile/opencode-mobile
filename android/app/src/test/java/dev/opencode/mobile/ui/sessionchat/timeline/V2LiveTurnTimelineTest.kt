package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.data.remote.dto.V2InboxItemDto
import dev.opencode.mobile.data.remote.dto.V2SessionMessageDto
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.message.DisplayMessage
import dev.opencode.mobile.domain.message.InMemoryMessageStore
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageDomainAdapter
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessagePatch
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.markdown.MarkdownDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live v2 turns end to end: wire events (shaped like the captured `/api/event`
 * stream) through the event adapter and message store into display order and
 * the chip timeline merge, then a REST reload over the live state.
 */
class V2LiveTurnTimelineTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val events = EventDomainAdapter(json)
    private val adapter = MessageDomainAdapter()
    private val store = InMemoryMessageStore(adapter)

    @Test
    fun multiStepTurn_keepsEachStepTextInItsOwnMessageAndParagraph() {
        apply(prompt("u1", "Explore the project", at = 1_000))
        apply(step("a1", at = 1_100))
        apply(text("a1", "Let me look around the project.", at = 1_110))
        apply(tool("a1", "call_glob", "glob", at = 1_120))
        apply(stepEnded("a1", "tool-calls", at = 1_130))
        apply(step("a2", at = 1_200))
        apply(tool("a2", "call_read", "read", at = 1_210))
        apply(stepEnded("a2", "tool-calls", at = 1_220))
        apply(step("a3", at = 1_300))
        apply(text("a3", "`handler` is defined in `src/app.ts`.", at = 1_310))
        apply(stepEnded("a3", "stop", at = 1_320))
        apply(idle(at = 1_330))

        val messages = store.messages(SESSION)
        assertEquals(listOf("u1", "a1", "a2", "a3"), messages.map { it.id })
        assertEquals(listOf("a1:text:0", "call_glob"), messages[1].parts.map { it.partId() })
        assertEquals("Let me look around the project.", messages[1].text)
        assertEquals("`handler` is defined in `src/app.ts`.", messages[3].text)

        val merged = chipBodies(messages)
        assertEquals(listOf("u1", "a1"), merged.map { it.message.id })
        val paragraphs = merged[1].blocks.filterIsInstance<SessionChatTimelineBlock.StepParagraph>()
        assertEquals(
            listOf("Let me look around the project.", "`handler` is defined in `src/app.ts`."),
            paragraphs.map { it.text },
        )
        assertEquals(paragraphs.size, paragraphs.map { it.blockID }.toSet().size)
        // Each paragraph keeps the markdown its own message was decorated with.
        assertEquals(listOf(documentFor("a1"), documentFor("a3")), paragraphs.map { it.renderedMarkdown })
    }

    @Test
    fun secondTurn_staysBelowFirstReplyAndUnmerged() {
        apply(prompt("u1", "First", at = 1_000))
        apply(step("a1", at = 1_100))
        apply(text("a1", "## Reply one", at = 1_110))
        apply(stepEnded("a1", "stop", at = 1_120))
        apply(idle(at = 1_130))
        apply(prompt("u2", "Second", at = 2_000))
        apply(step("a2", at = 2_100))
        apply(text("a2", "## Reply two", at = 2_110))
        apply(stepEnded("a2", "stop", at = 2_120))
        apply(idle(at = 2_130))

        val ordered = displayOrder(store.messages(SESSION))
        assertEquals(listOf("u1", "a1", "u2", "a2"), ordered.map { it.id })
        val merged = chipBodies(ordered)
        assertEquals(listOf("u1", "a1", "u2", "a2"), merged.map { it.message.id })
        assertEquals("## Reply two", merged[3].message.text)
        assertEquals(
            documentFor("a2"),
            merged[3]
                .blocks
                .stepParagraphs()
                .single()
                .renderedMarkdown,
        )
    }

    @Test
    fun steerAndQueuedPromptsInOneExecution_renderInDeliveryOrder() {
        apply(prompt("u1", "First prompt", at = 1_000))
        apply(step("a1", at = 1_100))
        apply(enqueued("u2", "Steer prompt", at = 1_150))
        apply(text("a1", "Answer to the first prompt.", at = 1_200))
        apply(stepEnded("a1", "stop", at = 1_210))
        apply(delivered("u2", at = 1_220))
        apply(step("a2", at = 1_300))
        apply(enqueued("u3", "Queued prompt", at = 1_350))
        apply(text("a2", "Answer to the steer prompt.", at = 1_400))
        apply(stepEnded("a2", "stop", at = 1_410))
        apply(delivered("u3", at = 1_420))
        apply(step("a3", at = 1_500))
        apply(text("a3", "Answer to the queued prompt.", at = 1_510))
        apply(stepEnded("a3", "stop", at = 1_520))
        apply(idle(at = 1_530))

        val ordered = displayOrder(store.messages(SESSION))
        assertEquals(listOf("u1", "a1", "u2", "a2", "u3", "a3"), ordered.map { it.id })
        val merged = chipBodies(ordered)
        assertEquals(listOf("u1", "a1", "u2", "a2", "u3", "a3"), merged.map { it.message.id })
        assertEquals(
            listOf("Answer to the first prompt.", "Answer to the steer prompt.", "Answer to the queued prompt."),
            merged.filter { it.message.role == MessageRole.Assistant }.map { it.message.text },
        )
    }

    @Test
    fun senderReloadsWithInboxPrompts_keepEnqueueTimeAndDeliveryOrder() {
        // The sending app reloads REST after every POST, so each follow-up is
        // first seen as an inbox item merged into the tail, not as a live info.
        apply(prompt("u1", "First prompt", at = 1_000))
        apply(step("a1", at = 1_100))
        apply(enqueued("u2", "Steer prompt", at = 1_150))
        reload(
            """
            [
              {"id":"u1","type":"user","text":"First prompt","time":{"created":1000}},
              {"id":"a1","type":"assistant","content":[{"type":"text","text":""}],"time":{"created":1100}}
            ]
            """,
            inbox =
                """[{"id":"u2","sessionID":"$SESSION","time":{"created":1150},"type":"user",""" +
                    """"payload":{"text":"Steer prompt"},"delivery":"steer"}]""",
        )
        val steerWaiting = store.messages(SESSION).single { it.id == "u2" }
        assertTrue(steerWaiting.isWaitingForPickup)
        assertEquals(1_150L, steerWaiting.sentAtEpochMs)

        apply(text("a1", "Answer to the first prompt.", at = 1_200))
        apply(stepEnded("a1", "stop", at = 1_210))
        apply(delivered("u2", at = 1_220))
        apply(enqueued("u3", "Queued prompt", at = 1_250, delivery = "queue"))
        // Manual reply mode: the steer reply has not started, so REST has no a2 yet.
        reload(
            """
            [
              {"id":"u1","type":"user","text":"First prompt","time":{"created":1000}},
              {"id":"a1","type":"assistant","content":[{"type":"text","text":"Answer to the first prompt."}],
               "time":{"created":1100,"completed":1210}},
              {"id":"u2","type":"user","text":"Steer prompt","time":{"created":1150}}
            ]
            """,
            inbox =
                """[{"id":"u3","sessionID":"$SESSION","time":{"created":1250},"type":"user",""" +
                    """"payload":{"text":"Queued prompt"},"delivery":"queue"}]""",
        )
        assertTrue(store.messages(SESSION).single { it.id == "u3" }.isWaitingForPickup)

        apply(step("a2", at = 1_300))
        apply(text("a2", "Answer to the steer prompt.", at = 1_400))
        apply(stepEnded("a2", "stop", at = 1_410))
        apply(delivered("u3", at = 1_420))
        apply(step("a3", at = 1_500))
        apply(text("a3", "Answer to the queued prompt.", at = 1_510))
        apply(stepEnded("a3", "stop", at = 1_520))
        apply(idle(at = 1_530))

        val messages = store.messages(SESSION)
        assertTrue(messages.all { (it.sentAtEpochMs ?: 0) >= 1_000 })
        assertTrue(messages.none { it.isWaitingForPickup })
        val ordered = displayOrder(messages)
        assertEquals(listOf("u1", "a1", "u2", "a2", "u3", "a3"), ordered.map { it.id })
        val merged = chipBodies(ordered)
        assertEquals(listOf("u1", "a1", "u2", "a2", "u3", "a3"), merged.map { it.message.id })
        assertEquals(
            listOf("Answer to the first prompt.", "Answer to the steer prompt.", "Answer to the queued prompt."),
            merged.filter { it.message.role == MessageRole.Assistant }.map { it.message.text },
        )
    }

    @Test
    fun deliveredInboxPrompt_keepsTheSessionAgentAndModel() {
        // The sender sees its queued prompt as an inbox item, then only a partial live info.
        reload(
            """
            [
              {"id":"s1","type":"agent-switched","agent":"build","time":{"created":900}},
              {"id":"s2","type":"model-switched","model":{"id":"scripted","providerID":"sim","variant":"high"},
               "time":{"created":901}},
              {"id":"u1","type":"user","text":"First prompt","time":{"created":1000}},
              {"id":"a1","type":"assistant","agent":"build",
               "model":{"id":"scripted","providerID":"sim","variant":"high"},
               "content":[{"type":"text","text":"Answer."}],"time":{"created":1100}}
            ]
            """,
            inbox =
                """[{"id":"u2","sessionID":"$SESSION","time":{"created":1150},"type":"user",""" +
                    """"payload":{"text":"Queued prompt"},"delivery":"queue"}]""",
        )
        apply(delivered("u2", at = 1_220))

        val queued = store.messages(SESSION).single { it.id == "u2" }
        assertFalse(queued.isWaitingForPickup)
        assertEquals("build", queued.mode)
        assertEquals("scripted", queued.model)
        assertEquals("high", queued.variant)
    }

    @Test
    fun restReloadAfterLiveTurn_replacesLivePartsInPlace() {
        apply(prompt("u1", "Explore", at = 1_000))
        apply(step("a1", at = 1_100))
        apply(text("a1", "Let me look around the project.", at = 1_110))
        apply(tool("a1", "call_glob", "glob", at = 1_120))
        apply(stepEnded("a1", "tool-calls", at = 1_130))
        apply(step("a2", at = 1_200))
        apply(text("a2", "Done.", at = 1_210))
        apply(stepEnded("a2", "stop", at = 1_220))

        val rest =
            restMessages(
                """
                [
                  {"id":"u1","type":"user","text":"Explore","time":{"created":1000}},
                  {"id":"a1","type":"assistant","content":[
                    {"type":"text","text":"Let me look around the project."},
                    {"type":"tool","id":"call_glob","name":"glob","state":{"status":"completed","input":{}}}
                  ],"time":{"created":1100,"completed":1130}},
                  {"id":"a2","type":"assistant","content":[{"type":"text","text":"Done."}],
                   "time":{"created":1200,"completed":1220}}
                ]
                """,
            )
        assertTrue(store.mergeAuthoritativeIfRevision(SESSION, rest, store.revision(SESSION)))

        val messages = store.messages(SESSION)
        assertEquals(listOf("u1", "a1", "a2"), messages.map { it.id })
        assertEquals(listOf("a1:text:0", "call_glob"), messages[1].parts.map { it.partId() })
        assertEquals(listOf("a2:text:0"), messages[2].parts.map { it.partId() })
        assertEquals("Done.", messages[2].text)
    }

    @Test
    fun catchUpReload_dropsStaleWaitingPromptAndKeepsTurnsApart() {
        // Foreground catch-up: SSE saw turn 1 and the enqueue of prompt 2, then
        // dropped. REST has the whole second turn.
        apply(prompt("u1", "Tell me about the handler", at = 1_000))
        apply(step("a1", at = 1_100))
        apply(text("a1", "Reply `one`.", at = 1_110))
        apply(stepEnded("a1", "stop", at = 1_120))
        apply(idle(at = 1_130))
        apply(enqueued("u2", "Second turn while backgrounded", at = 2_000))

        val rest =
            restMessages(
                """
                [
                  {"id":"u1","type":"user","text":"Tell me about the handler","time":{"created":1000}},
                  {"id":"a1","type":"assistant","content":[{"type":"text","text":"Reply `one`."}],
                   "time":{"created":1100,"completed":1120}},
                  {"id":"u2","type":"user","text":"Second turn while backgrounded","time":{"created":2000}},
                  {"id":"a2","type":"assistant","content":[{"type":"text","text":"Answer two."}],
                   "time":{"created":2100,"completed":2120}}
                ]
                """,
            )
        assertTrue(store.mergeAuthoritativeIfRevision(SESSION, rest, store.revision(SESSION)))

        val messages = store.messages(SESSION)
        assertEquals(listOf("u1", "a1", "u2", "a2"), messages.map { it.id })
        assertFalse(messages[2].isWaitingForPickup)
        val ordered = displayOrder(messages)
        assertEquals(listOf("u1", "a1", "u2", "a2"), ordered.map { it.id })
        assertEquals(listOf("u1", "a1", "u2", "a2"), chipBodies(ordered).map { it.message.id })
    }

    @Test
    fun liveInfoAfterRestSeed_keepsTheReloadedTimestamp() {
        val rest =
            restMessages(
                """
                [
                  {"id":"u1","type":"user","text":"Go","time":{"created":1000}},
                  {"id":"a1","type":"assistant","content":[{"type":"text","text":"Working"}],
                   "time":{"created":1100}}
                ]
                """,
            )
        store.replaceAll(SESSION, rest)
        // A partial v2 assistant info (text.started) must not wipe the REST time.
        apply(textStarted("a1", ordinal = 1, at = 1_200))
        apply(prompt("u2", "Next", at = 2_000))

        val ordered = displayOrder(store.messages(SESSION))
        assertEquals(1_100L, ordered.first { it.id == "a1" }.sentAtEpochMs)
        assertEquals(listOf("u1", "a1", "u2"), ordered.map { it.id })
    }

    @Test
    fun revertCommitted_dropsTheRevertedTurnAndEverythingAfterIt() {
        apply(prompt("u1", "First prompt", at = 1_000))
        apply(step("a1", at = 1_100))
        apply(text("a1", "Answer one.", at = 1_110))
        apply(stepEnded("a1", "stop", at = 1_120))
        apply(idle(at = 1_130))
        apply(prompt("u2", "Queued prompt", at = 2_000))
        apply(step("a2", at = 2_100))
        apply(text("a2", "Answer two.", at = 2_110))
        apply(stepEnded("a2", "stop", at = 2_120))
        apply(idle(at = 2_130))
        // Captured shape: staged on "Revert message", committed when the edit is sent.
        apply(listOf(envelope("session.revert.staged", 3_000, """"revert":{"messageID":"u2","files":[]}""")))
        assertEquals(listOf("u1", "a1", "u2", "a2"), store.messages(SESSION).map { it.id })

        apply(listOf(envelope("session.revert.committed", 3_100, """"to":"u2"""")))
        apply(prompt("u3", "Queued prompt v2", at = 3_110))

        assertEquals(listOf("u1", "a1", "u3"), store.messages(SESSION).map { it.id })
    }

    @Test
    fun restCompaction_isACompactionRowNotReplyText() {
        // `GET /api/session/:id/message` from the sim server, oldest first.
        val rest =
            restMessages(
                """
                [
                  {"id":"u1","type":"user","text":"Say hi","time":{"created":1000}},
                  {"id":"a1","type":"assistant","agent":"build","content":[{"type":"text","text":"Hi there."}],
                   "finish":"stop","time":{"created":1100,"completed":1200}},
                  {"id":"i1","type":"idle","outcome":"succeeded","time":{"created":1201}},
                  {"type":"compaction","id":"c1","time":{"created":1300},"status":"completed","reason":"manual",
                   "summary":"## Objective\n- Exercise queue and steer prompts.\n\n## Next Move\n- Wait.",
                   "recent":"","cost":0},
                  {"id":"i2","type":"idle","outcome":"succeeded","time":{"created":1400}}
                ]
                """,
            )
        store.replaceAll(SESSION, rest)

        val messages = store.messages(SESSION)
        val compaction = messages.single { it.id == "c1" }
        assertEquals(listOf(MessagePart.Compaction(id = "c1", completed = true, summary = SUMMARY)), compaction.parts)
        assertEquals("", compaction.text)

        val merged = chipBodies(displayOrder(messages))
        assertEquals(listOf("u1", "a1", "c1"), merged.map { it.message.id })
        assertEquals(
            listOf("Hi there."),
            merged[1].blocks.stepParagraphs().map { it.text },
        )
        val marker = merged[2].blocks.single() as SessionChatTimelineBlock.ToolCard
        assertEquals(SessionCompactChrome.MARKER_TITLE, marker.title)
        assertEquals(SUMMARY, marker.detailText)
    }

    @Test
    fun liveCompaction_startedThenEnded_isOneCompactionRowAfterTheReply() {
        apply(prompt("u1", "Say hi", at = 1_000))
        apply(step("a1", at = 1_100))
        apply(text("a1", "Hi there.", at = 1_110))
        apply(stepEnded("a1", "stop", at = 1_120))
        apply(idle(at = 1_130))
        apply(
            listOf(
                envelope("session.compaction.started", 2_000, """"reason":"manual","recent":"","inputID":"msg_c1""""),
                envelope("session.compaction.delta", 2_010, """"text":"## Objective""""),
            ),
        )
        val running = store.messages(SESSION).single { it.id == "msg_c1" }
        assertEquals(listOf(MessagePart.Compaction(id = "msg_c1")), running.parts)
        assertTrue(SessionCompactionPresence.isActive(store.messages(SESSION), isSessionBusy = true))

        apply(
            listOf(
                envelope(
                    "session.compaction.ended",
                    2_100,
                    """"reason":"manual","text":${Json.encodeToString(SUMMARY)},"recent":"","cost":0""",
                ),
            ),
        )

        val messages = displayOrder(store.messages(SESSION))
        assertEquals(listOf("u1", "a1", "msg_c1"), messages.map { it.id })
        assertEquals(
            listOf(MessagePart.Compaction(id = "msg_c1", completed = true, summary = SUMMARY)),
            messages[2].parts,
        )
        val merged = chipBodies(messages)
        assertEquals(listOf("u1", "a1", "msg_c1"), merged.map { it.message.id })
        assertEquals("Hi there.", merged[1].message.text)
        assertEquals(SUMMARY, (merged[2].blocks.single() as SessionChatTimelineBlock.ToolCard).detailText)
    }

    // MARK: Wire events

    private fun prompt(
        id: String,
        text: String,
        at: Long,
    ): List<String> = enqueued(id, text, at) + delivered(id, at + 10)

    private fun enqueued(
        id: String,
        text: String,
        at: Long,
        delivery: String = "steer",
    ): List<String> =
        listOf(
            envelope(
                "session.inbox.enqueued",
                at,
                """"inboxID":"$id","item":{"id":"$id","type":"user","payload":{"text":"$text"},"delivery":"$delivery"}""",
            ),
        )

    private fun delivered(
        id: String,
        at: Long,
    ): List<String> = listOf(envelope("session.inbox.delivered", at, """"inboxID":"$id""""))

    private fun step(
        id: String,
        at: Long,
    ): List<String> =
        listOf(
            envelope(
                "session.step.started",
                at,
                """"agent":"build","model":{"id":"m","providerID":"p"},"assistantMessageID":"$id"""",
            ),
        )

    private fun textStarted(
        id: String,
        ordinal: Int,
        at: Long,
    ): List<String> = listOf(envelope("session.text.started", at, """"assistantMessageID":"$id","ordinal":$ordinal"""))

    private fun text(
        id: String,
        value: String,
        at: Long,
    ): List<String> {
        val fields = """"assistantMessageID":"$id","ordinal":0"""
        return textStarted(id, ordinal = 0, at = at) +
            envelope("session.text.delta", at + 1, """$fields,"delta":${Json.encodeToString(value)}""") +
            envelope("session.text.ended", at + 2, """$fields,"text":${Json.encodeToString(value)}""")
    }

    private fun tool(
        id: String,
        callId: String,
        name: String,
        at: Long,
    ): List<String> {
        val fields = """"assistantMessageID":"$id","id":"$callId","name":"$name""""
        return listOf(
            envelope("session.tool.input.started", at, fields),
            envelope("session.tool.called", at + 1, """$fields,"input":{"pattern":"*"}"""),
            envelope("session.tool.success", at + 2, """$fields,"content":[{"type":"text","text":"ok"}]"""),
        )
    }

    private fun stepEnded(
        id: String,
        finish: String,
        at: Long,
    ): List<String> = listOf(envelope("session.step.ended", at, """"assistantMessageID":"$id","finish":"$finish""""))

    private fun idle(at: Long): List<String> = listOf(envelope("session.execution.succeeded", at, ""))

    private fun envelope(
        type: String,
        created: Long,
        fields: String,
    ): String {
        val data = if (fields.isEmpty()) """"sessionID":"$SESSION"""" else """"sessionID":"$SESSION",$fields"""
        return """{"id":"evt_$type$created","created":$created,"type":"$type","data":{$data}}"""
    }

    private fun apply(lines: List<String>) {
        lines.flatMap(events::parseEvents).forEach { event ->
            if (event.sessionId != SESSION) return@forEach
            val properties = event.rawProperties ?: return@forEach
            when (event.type) {
                "message.updated" ->
                    store.apply(SESSION, MessagePatch.InfoUpdated(properties["info"]!!.jsonObject))
                "message.part.updated" ->
                    store.apply(
                        SESSION,
                        MessagePatch.PartUpdated(
                            properties["part"]!!.jsonObject,
                            properties["delta"]?.jsonPrimitive?.contentOrNull,
                        ),
                    )
                "message.part.delta" -> {
                    val messageId = properties.string("messageID") ?: return@forEach
                    val partId = properties.string("partID") ?: return@forEach
                    val delta = properties.string("delta") ?: return@forEach
                    store.apply(SESSION, MessagePatch.PartDelta(messageId, partId, delta))
                }
                "session.revert.committed" ->
                    store.apply(SESSION, MessagePatch.RemovedFrom(properties.string("messageID")!!))
                "session.idle" -> store.finishStreamingText(SESSION)
            }
        }
    }

    // MARK: Display

    private fun displayOrder(messages: List<Message>): List<Message> =
        TimelineDisplayOrder.ordered(messages.map { DisplayMessage(message = it) }).map { it.message }

    /** Per-message chip bodies, each decorated with its own document, then merged. */
    private fun chipBodies(messages: List<Message>): List<DisplayMessageBody> =
        SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
            messages.map { message ->
                val blocks =
                    SessionChatTimelineBlocks
                        .buildOrFallback(
                            role = message.role,
                            parts = message.parts,
                            messageId = message.id,
                            fallbackText = message.text,
                            chipTimeline = true,
                        ).map { block ->
                            if (block is SessionChatTimelineBlock.StepParagraph && block.text != null) {
                                block.copy(renderedMarkdown = documentFor(message.id))
                            } else {
                                block
                            }
                        }
                DisplayMessageBody(message = message, blocks = blocks)
            },
            collapseConsecutiveToolCalls = false,
            chipTimeline = true,
        )

    private fun documentFor(messageId: String): MarkdownDocument = MarkdownDocument(sourceHash = messageId.hashCode(), blocks = emptyList())

    private fun List<SessionChatTimelineBlock>.stepParagraphs(): List<SessionChatTimelineBlock.StepParagraph> =
        filterIsInstance<SessionChatTimelineBlock.StepParagraph>()

    /** Tail reload the way the message sync applies it: REST page plus inbox, merged over local rows. */
    private fun reload(
        body: String,
        inbox: String,
    ) {
        val items = json.decodeFromString<List<V2InboxItemDto>>(inbox)
        val tail = adapter.mergeInbox(restMessages(body), items)
        assertTrue(store.mergeTailAuthoritativeIfRevision(SESSION, tail, store.revision(SESSION)))
    }

    private fun restMessages(body: String): List<Message> =
        adapter.projectV2(json.decodeFromString<List<V2SessionMessageDto>>(body.trimIndent()))

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun MessagePart.partId(): String? =
        when (this) {
            is MessagePart.Text -> id
            is MessagePart.Tool -> id
            is MessagePart.File -> id
            is MessagePart.Compaction -> id
            is MessagePart.Reasoning -> id
        }

    private companion object {
        const val SESSION = "ses_v2"
        const val SUMMARY = "## Objective\n- Exercise queue and steer prompts.\n\n## Next Move\n- Wait."
    }
}
