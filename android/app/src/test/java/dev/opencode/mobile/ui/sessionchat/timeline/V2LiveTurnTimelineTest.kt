package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.data.remote.dto.V2InboxItemDto
import dev.opencode.mobile.data.remote.dto.V2SessionMessageDto
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.message.DisplayMessage
import dev.opencode.mobile.domain.message.InMemoryMessageStore
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageContextPatch
import dev.opencode.mobile.domain.message.MessageDomainAdapter
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessagePatch
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.MessageStopReason
import dev.opencode.mobile.domain.message.NoticeModelRef
import dev.opencode.mobile.domain.message.TimelineNotice
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.markdown.MarkdownDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    fun liveV2Prompts_getTheAgentAndModelRestRecords() {
        // Shaped like the B2-5 sim run: a server-injected prompt, a steer from this app (which
        // switches agent and model first), then more injected prompts and steers.
        apply(prompt("u1", "Say hello.", at = 1_000))
        apply(step("a1", at = 1_050, model = SCRIPTED))
        apply(agentSelected("build", at = 1_200))
        apply(modelSelected(SCRIPTED_HIGH, at = 1_201))
        apply(enqueued("u2", "Steer from Android", at = 1_202))
        apply(text("a1", "Hello!", at = 1_300))
        apply(stepEnded("a1", "stop", at = 1_310))
        apply(delivered("u2", at = 1_320))
        apply(step("a2", at = 1_330, model = SCRIPTED_HIGH))
        apply(text("a2", "Steered.", at = 1_340))
        apply(stepEnded("a2", "stop", at = 1_350))
        apply(idle(at = 1_360))
        apply(prompt("u3", "Second hello.", at = 2_000))
        apply(agentSelected("build", at = 2_020))
        apply(step("a3", at = 2_030, model = SCRIPTED_HIGH))
        apply(text("a3", "Hello again!", at = 2_040))
        apply(stepEnded("a3", "stop", at = 2_050))
        apply(idle(at = 2_060))

        val live = store.messages(SESSION).filter { it.role == MessageRole.User }
        val rest =
            restMessages(
                """
                [
                  {"id":"u1","type":"user","text":"Say hello.","time":{"created":1000}},
                  {"id":"s1","type":"agent-switched","agent":"build","time":{"created":1200}},
                  {"id":"s2","type":"model-switched","model":$SCRIPTED_HIGH,"time":{"created":1201}},
                  {"id":"a1","type":"assistant","agent":"build","model":$SCRIPTED,
                   "content":[{"type":"text","text":"Hello!"}],"time":{"created":1050}},
                  {"id":"u2","type":"user","text":"Steer from Android","time":{"created":1320}},
                  {"id":"a2","type":"assistant","agent":"build","model":$SCRIPTED_HIGH,
                   "content":[{"type":"text","text":"Steered."}],"time":{"created":1330}},
                  {"id":"u3","type":"user","text":"Second hello.","time":{"created":2000}},
                  {"id":"s3","type":"agent-switched","agent":"build","time":{"created":2020}},
                  {"id":"a3","type":"assistant","agent":"build","model":$SCRIPTED_HIGH,
                   "content":[{"type":"text","text":"Hello again!"}],"time":{"created":2030}}
                ]
                """,
            ).filter { it.role == MessageRole.User }

        assertEquals(listOf("u1", "u2", "u3"), live.map { it.id })
        assertEquals(rest.map { it.metaLine() }, live.map { it.metaLine() })
        assertEquals(listOf(null, "build|scripted|null", "build|scripted|high"), live.map { it.metaLine() })
    }

    @Test
    fun switchBeforeAPrompt_appliesToThatPrompt() {
        apply(prompt("u1", "First", at = 1_000))
        apply(step("a1", at = 1_050, model = SCRIPTED_HIGH))
        apply(text("a1", "Reply.", at = 1_060))
        apply(stepEnded("a1", "stop", at = 1_070))
        apply(idle(at = 1_080))
        apply(agentSelected("plan", at = 2_000))
        apply(modelSelected("""{"id":"scripted","providerID":"sim","variant":"low"}""", at = 2_001))
        apply(prompt("u2", "Second", at = 2_010))

        assertEquals("plan|scripted|low", store.messages(SESSION).single { it.id == "u2" }.metaLine())
    }

    @Test
    fun liveNotices_showWithoutRestThenShareOneRowWithTheRestItem() {
        apply(prompt("u1", "First", at = 1_000))
        apply(step("a1", at = 1_050))
        apply(text("a1", "Reply.", at = 1_060))
        apply(stepEnded("a1", "stop", at = 1_070))
        apply(idle(at = 1_080))
        apply(moved("/repo/.worktrees/feat", at = 2_000))
        apply(agentSelected("plan", at = 2_001))
        apply(modelSelected(SCRIPTED_HIGH, at = 2_002))
        apply(instructionsUpdated(at = 2_003))
        apply(restart(at = 2_004))

        val live = store.messages(SESSION).filter { it.role == MessageRole.Notice }
        val ids =
            listOf(
                "msg_session.moved2000",
                "msg_session.agent.selected2001",
                "msg_session.model.selected2002",
                "msg_session.instructions.updated2003",
                "msg_session.synthetic2004",
            )
        assertEquals(ids, live.map { it.id })
        assertEquals(
            listOf(
                TimelineNotice.Moved("/repo/.worktrees/feat"),
                TimelineNotice.AgentChanged("plan"),
                TimelineNotice.ModelChanged(NoticeModelRef("sim", "scripted", "high")),
                TimelineNotice.InstructionsUpdated(listOf("AGENTS.md")),
                TimelineNotice.Restart,
            ),
            live.map { it.notice() },
        )
        // Each notice is its own row, after the finished turn.
        val rows = chipBodies(displayOrder(store.messages(SESSION)))
        assertEquals(listOf("u1", "a1") + ids, rows.map { it.message.id })
        rows.drop(2).forEach { row ->
            assertTrue(row.blocks.single() is SessionChatTimelineBlock.Notice)
        }

        // REST records the same items under the same ids, with what changed from.
        reload(
            """
            [
              {"id":"u1","type":"user","text":"First","time":{"created":1000}},
              {"id":"a1","type":"assistant","agent":"build","model":{"id":"m","providerID":"p"},
               "content":[{"type":"text","text":"Reply."}],"time":{"created":1050}},
              {"id":"${ids[0]}","type":"location-switched","location":{"directory":"/repo/.worktrees/feat"},
               "previous":{"location":{"directory":"/repo"}},"time":{"created":2000}},
              {"id":"${ids[1]}","type":"agent-switched","agent":"plan","previous":"build","time":{"created":2001}},
              {"id":"${ids[2]}","type":"model-switched","model":$SCRIPTED_HIGH,
               "previous":{"id":"m","providerID":"p"},"time":{"created":2002}},
              {"id":"${ids[3]}","type":"system","text":"model text","description":"Instructions updated: AGENTS.md",
               "metadata":{"notice":"instructions","instructionSources":["AGENTS.md"]},"time":{"created":2003}},
              {"id":"${ids[4]}","type":"synthetic","text":"Resume.","metadata":{"notice":"restart"},"time":{"created":2004}}
            ]
            """,
            inbox = "[]",
        )

        val reloaded = store.messages(SESSION).filter { it.role == MessageRole.Notice }
        assertEquals(ids, reloaded.map { it.id })
        assertEquals(TimelineNotice.AgentChanged("plan", previous = "build"), reloaded[1].notice())
        assertEquals(TimelineNotice.Moved("/repo/.worktrees/feat", previousDirectory = "/repo"), reloaded[0].notice())
    }

    @Test
    fun noticeBetweenAssistantSteps_isAStandaloneRowThatSplitsTheChipMerge() {
        apply(prompt("u1", "Explore", at = 1_000))
        apply(step("a1", at = 1_100))
        apply(text("a1", "Looking around.", at = 1_110))
        apply(tool("a1", "call_glob", "glob", at = 1_120))
        apply(stepEnded("a1", "tool-calls", at = 1_130))
        apply(moved("/repo/b", at = 1_150))
        apply(step("a2", at = 1_200))
        apply(text("a2", "Found it.", at = 1_210))
        apply(stepEnded("a2", "stop", at = 1_220))
        apply(idle(at = 1_230))

        val notice = "msg_session.moved1150"
        val rows = chipBodies(displayOrder(store.messages(SESSION)))
        assertEquals(listOf("u1", "a1", notice, "a2"), rows.map { it.message.id })
        val block = rows[2].blocks.single() as SessionChatTimelineBlock.Notice
        assertEquals("p:$notice", block.blockID)
        assertEquals(TimelineNotice.Moved("/repo/b"), block.notice)
        assertEquals(listOf("Looking around."), rows[1].blocks.stepParagraphs().map { it.text })
        assertEquals(listOf("Found it."), rows[3].blocks.stepParagraphs().map { it.text })
    }

    @Test
    fun restSwitchNoticesAfterTheLastReply_stampTheNextLivePrompt() {
        reload(
            """
            [
              {"id":"u1","type":"user","text":"First","time":{"created":1000}},
              {"id":"a1","type":"assistant","agent":"build","model":$SCRIPTED,
               "content":[{"type":"text","text":"Answer."}],"time":{"created":1100}},
              {"id":"s1","type":"agent-switched","agent":"plan","previous":"build","time":{"created":2000}},
              {"id":"s2","type":"model-switched","model":$SCRIPTED_HIGH,"previous":$SCRIPTED,"time":{"created":2001}}
            ]
            """,
            inbox = "[]",
        )
        // The switches happened before this app connected: only REST knows them.
        apply(prompt("u2", "Next prompt", at = 2_100))

        assertEquals("plan|scripted|high", store.messages(SESSION).single { it.id == "u2" }.metaLine())
    }

    @Test
    fun stoppedTool_isInterruptedNotFailed_liveAndAfterReload() {
        apply(prompt("u1", "Start the deployment.", at = 1_000))
        apply(step("a1", at = 1_100))
        apply(text("a1", "Starting the deployment now.", at = 1_110))
        val fields = """"assistantMessageID":"a1","id":"call_8","name":"sim_task""""
        apply(
            listOf(
                envelope("session.tool.input.started", 1_120, fields),
                envelope("session.tool.called", 1_121, """$fields,"input":{"title":"Deploy"}"""),
                envelope(
                    "session.tool.failed",
                    1_200,
                    """$fields,"error":{"type":"aborted","message":"Tool execution interrupted"},"executed":false""",
                ),
            ),
        )

        val liveTool = store.messages(SESSION).single { it.id == "a1" }.parts.filterIsInstance<MessagePart.Tool>().single()
        assertTrue(liveTool.isInterrupted)
        val paragraph = chipBodies(store.messages(SESSION))[1].blocks.stepParagraphs().single()
        assertTrue(paragraph.errorLines.isEmpty())
        assertTrue(paragraph.chips.single().card.isInterrupted)
        assertEquals("Stopped", paragraph.chips.single().card.errorText)

        val rest =
            restMessages(
                """
                [
                  {"id":"u1","type":"user","text":"Start the deployment.","time":{"created":1000}},
                  {"id":"a1","type":"assistant","finish":"error","error":{"type":"aborted","message":"Step interrupted"},
                   "content":[{"type":"text","text":"Starting the deployment now."},
                     {"type":"tool","id":"call_8","name":"sim_task","state":{"status":"error","input":{},
                      "error":{"type":"aborted","message":"Tool execution interrupted"}}}],
                   "time":{"created":1100,"completed":1200}}
                ]
                """,
            )
        assertTrue(rest[1].parts.filterIsInstance<MessagePart.Tool>().single().isInterrupted)
    }

    @Test
    fun streamingShellArguments_growOnThePendingCardUntilCalledSettlesThem() {
        apply(prompt("u1", "List files", at = 1_000))
        apply(step("a1", at = 1_100))
        val fields = """"assistantMessageID":"a1","id":"call_sh","name":"bash""""
        apply(listOf(envelope("session.tool.input.started", 1_120, fields)))
        // Servers that do not stream tool input still show the placeholder until `ended`.
        assertEquals("Writing command…", toolCard("a1").subtitle)
        apply(listOf(inputDelta(fields, """{"comm""", at = 1_121)))

        val waiting = toolCard("a1")
        assertEquals(ToolStatus.Pending, waiting.status)
        assertTrue(waiting.isReceivingArguments)
        assertEquals("Writing command…", waiting.subtitle)

        apply(listOf(inputDelta(fields, """and":"ls -la /tm""", at = 1_122)))
        assertEquals("ls -la /tm", toolCard("a1").subtitle)
        apply(listOf(inputDelta(fields, """p/scratch","description":"List""", at = 1_123)))
        assertEquals("ls -la /tmp/scratch", toolCard("a1").subtitle)
        val chip =
            chipBodies(store.messages(SESSION))[1]
                .blocks
                .stepParagraphs()
                .single()
                .chips
                .single()
        assertEquals("ls -la /tmp/scratch", chip.label)

        apply(
            listOf(
                envelope("session.tool.called", 1_130, """$fields,"input":{"command":"ls -la /tmp","description":"List"}"""),
            ),
        )
        val tool = liveTool("a1")
        assertFalse(tool.input!!.containsKey("preview"))
        val called = toolCard("a1")
        assertEquals(ToolStatus.Running, called.status)
        assertFalse(called.isReceivingArguments)
        assertEquals("ls -la /tmp", called.subtitle)

        apply(listOf(envelope("session.tool.success", 1_140, """$fields,"content":[{"type":"text","text":"ok"}]""")))
        val done = toolCard("a1")
        assertEquals(ToolStatus.Completed, done.status)
        assertFalse(done.isReceivingArguments)
        assertEquals("ls -la /tmp", done.subtitle)
        assertEquals("ok", done.detailText)
    }

    @Test
    fun streamingFileArguments_showThePathOnReadEditAndWriteCards() {
        apply(prompt("u1", "Change files", at = 1_000))
        apply(step("a1", at = 1_100))
        val cases =
            listOf(
                Triple("read", "Preparing read…", """{"filePath":"/repo/src/app.ts"""),
                Triple("edit", "Preparing edit…", """{"filePath":"/repo/src/app.ts","oldString":"a"""),
                Triple("write", "Preparing write…", """{"content":"line 1\nline 2","filePath":"/repo/NOTES"""),
            )
        cases.forEachIndexed { index, (name, placeholder, partial) ->
            val fields = """"assistantMessageID":"a1","id":"call_$name","name":"$name""""
            val at = 1_200L + index * 10
            apply(listOf(envelope("session.tool.input.started", at, fields), inputDelta(fields, "{", at = at + 1)))
            assertEquals(placeholder, toolCard("a1", "call_$name").subtitle)
            apply(listOf(inputDelta(fields, partial.removePrefix("{"), at = at + 2)))
            val card = toolCard("a1", "call_$name")
            assertTrue(card.isReceivingArguments)
            assertEquals(if (name == "write") "/repo/NOTES" else "/repo/src/app.ts", card.subtitle)
            assertFalse(card.opensChangesReview)
        }
    }

    @Test
    fun inputEnded_replacesThePreviewWithTheSettledArguments() {
        apply(prompt("u1", "List files", at = 1_000))
        apply(step("a1", at = 1_100))
        val fields = """"assistantMessageID":"a1","id":"call_sh","name":"bash""""
        apply(
            listOf(
                envelope("session.tool.input.started", 1_120, fields),
                inputDelta(fields, """{"command":"ls -l""", at = 1_121),
                envelope("session.tool.input.ended", 1_122, """$fields,"text":"{\"command\":\"ls -la\"}""""),
            ),
        )

        val tool = liveTool("a1")
        assertEquals("""{"command":"ls -la"}""", tool.input.toString())
        val card = toolCard("a1")
        assertEquals(ToolStatus.Pending, card.status)
        assertFalse(card.isReceivingArguments)
        assertEquals("ls -la", card.subtitle)
    }

    @Test
    fun restReloadMidStream_keepsTheStreamingArgumentsAsThePreview() {
        val rest =
            restMessages(
                """
                [
                  {"id":"a1","type":"assistant","content":[
                    {"type":"tool","id":"call_sh","name":"bash","state":{"status":"streaming","input":"{\"command\":\"git st"}}],
                   "time":{"created":1100}}
                ]
                """,
            )
        val card = toolCards(rest.single()).single()
        assertTrue(card.isReceivingArguments)
        assertEquals("git st", card.subtitle)
    }

    @Test
    fun v1AndSettledCards_keepTodaysChrome() {
        val v1Pending = MessagePart.Tool(toolName = "bash", status = ToolStatus.Pending, id = "call_v1")
        val v1Card = toolCards(listOf(v1Pending)).single()
        assertFalse(v1Card.isReceivingArguments)
        assertEquals(null, v1Card.subtitle)

        // A settled card ignores a stray preview the server never replaced.
        val stray = JsonObject(mapOf("preview" to JsonPrimitive("""{"command":"rm""")))
        for (status in listOf(ToolStatus.Completed, ToolStatus.Error)) {
            val card =
                toolCards(listOf(MessagePart.Tool(toolName = "bash", status = status, input = stray, id = "call_$status")))
                    .single()
            assertFalse(card.isReceivingArguments)
            assertEquals(null, card.subtitle)
        }
        val generic = MessagePart.Tool(toolName = "sim_task", status = ToolStatus.Pending, input = stray, id = "call_g")
        assertEquals(null, toolCards(listOf(generic)).single().subtitle)
    }

    @Test
    fun realToolFailure_staysAnError() {
        apply(prompt("u1", "Run it", at = 1_000))
        apply(step("a1", at = 1_100))
        val fields = """"assistantMessageID":"a1","id":"call_9","name":"sim_task""""
        apply(
            listOf(
                envelope("session.tool.called", 1_121, """$fields,"input":{}"""),
                envelope("session.tool.failed", 1_200, """$fields,"error":{"type":"unknown","message":"lock timeout"}"""),
            ),
        )
        val tool = store.messages(SESSION).single { it.id == "a1" }.parts.filterIsInstance<MessagePart.Tool>().single()
        assertFalse(tool.isInterrupted)
    }

    @Test
    fun truncatedReply_keepsACutOffNote_liveAndFromRest() {
        apply(prompt("u1", "Write an essay", at = 1_000))
        apply(step("a1", at = 1_100))
        apply(text("a1", "This is a very long essay that never", at = 1_110))
        apply(stepEnded("a1", "length", at = 1_200))
        // A later partial info (usage) must not drop how the step ended.
        apply(
            listOf(
                envelope(
                    "session.usage.updated",
                    1_210,
                    """"assistantMessageID":"a1","tokens":{"input":1,"output":2,"reasoning":0,"cache":{"read":0,"write":0}}""",
                ),
            ),
        )
        apply(idle(at = 1_300))

        val live = store.messages(SESSION).single { it.id == "a1" }
        assertEquals(MessageStopReason.Length, live.stopReason)
        val body = chipBodies(store.messages(SESSION))[1]
        assertEquals(
            SessionChatTimelineBlock.StopNote(blockID = "a1:stop-note", reason = MessageStopReason.Length),
            body.blocks.last(),
        )

        val rest =
            restMessages(
                """
                [{"id":"a1","type":"assistant","finish":"length","content":[{"type":"text","text":"Cut"}],
                  "time":{"created":1100,"completed":1200}}]
                """,
            )
        assertEquals(MessageStopReason.Length, rest.single().stopReason)
    }

    @Test
    fun interruptedReply_keepsAnInterruptedNote_andNoAbortText() {
        apply(prompt("u1", "Explain", at = 1_000))
        apply(step("a1", at = 1_100))
        apply(text("a1", "The handler lives in", at = 1_110))
        apply(
            listOf(
                envelope(
                    "session.step.failed",
                    1_200,
                    """"assistantMessageID":"a1","error":{"type":"aborted","message":"Step interrupted"}""",
                ),
            ),
        )
        assertEquals(MessageStopReason.Interrupted, store.messages(SESSION).single { it.id == "a1" }.stopReason)
        val merged = chipBodies(store.messages(SESSION))
        assertTrue(merged[1].blocks.last() is SessionChatTimelineBlock.StopNote)

        val rest =
            restMessages(
                """
                [{"id":"a1","type":"assistant","finish":"error","error":{"type":"aborted","message":"Step interrupted"},
                  "content":[],"time":{"created":1100,"completed":1200}}]
                """,
            )
        assertEquals(MessageStopReason.Interrupted, rest.single().stopReason)
        assertEquals("", rest.single().text)
    }

    @Test
    fun completeReply_hasNoStopNote() {
        apply(prompt("u1", "Say hi", at = 1_000))
        apply(step("a1", at = 1_100))
        apply(text("a1", "Hi.", at = 1_110))
        apply(stepEnded("a1", "stop", at = 1_200))
        assertEquals(null, store.messages(SESSION).single { it.id == "a1" }.stopReason)
        assertTrue(chipBodies(store.messages(SESSION))[1].blocks.none { it is SessionChatTimelineBlock.StopNote })
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
        model: String = """{"id":"m","providerID":"p"}""",
    ): List<String> =
        listOf(
            envelope(
                "session.step.started",
                at,
                """"agent":"build","model":$model,"assistantMessageID":"$id"""",
            ),
        )

    private fun agentSelected(
        agent: String,
        at: Long,
    ): List<String> = listOf(envelope("session.agent.selected", at, """"agent":"$agent""""))

    private fun modelSelected(
        model: String,
        at: Long,
    ): List<String> = listOf(envelope("session.model.selected", at, """"model":$model"""))

    private fun moved(
        directory: String,
        at: Long,
    ): List<String> = listOf(envelope("session.moved", at, """"location":{"directory":"$directory"},"projectID":"prj_1""""))

    private fun instructionsUpdated(at: Long): List<String> =
        listOf(envelope("session.instructions.updated", at, """"delta":{"AGENTS.md":{}},"text":"<instructions>""""))

    private fun restart(at: Long): List<String> =
        listOf(envelope("session.synthetic", at, """"text":"Resume.","metadata":{"notice":"restart"}"""))

    private fun Message.notice(): TimelineNotice = parts.filterIsInstance<MessagePart.Notice>().single().notice

    private fun Message.metaLine(): String? = if (mode == null && model == null) null else "$mode|$model|$variant"

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

    private fun inputDelta(
        fields: String,
        delta: String,
        at: Long,
    ): String = envelope("session.tool.input.delta", at, """$fields,"delta":${Json.encodeToString(delta)}""")

    private fun liveTool(messageId: String): MessagePart.Tool =
        store
            .messages(SESSION)
            .single { it.id == messageId }
            .parts
            .filterIsInstance<MessagePart.Tool>()
            .single()

    private fun toolCard(
        messageId: String,
        callId: String? = null,
    ): SessionChatTimelineBlock.ToolCard {
        val message = store.messages(SESSION).single { it.id == messageId }
        return toolCards(message).single { callId == null || it.blockID == PresentationBlockId.direct(callId) }
    }

    private fun toolCards(message: Message): List<SessionChatTimelineBlock.ToolCard> = toolCards(message.parts)

    private fun toolCards(parts: List<MessagePart>): List<SessionChatTimelineBlock.ToolCard> {
        val result = SessionChatTimelineBlocks.build(role = MessageRole.Assistant, parts = parts)
        return (result as SessionChatTimelineBlocksResult.Ok).blocks.filterIsInstance<SessionChatTimelineBlock.ToolCard>()
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
                EventDomainAdapter.MESSAGE_CONTEXT_EVENT ->
                    MessageContextPatch.from(properties)?.let { store.apply(SESSION, it) }
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
            is MessagePart.Notice -> id
            is MessagePart.Reasoning -> id
        }

    private companion object {
        const val SESSION = "ses_v2"
        const val SCRIPTED = """{"id":"scripted","providerID":"sim"}"""
        const val SCRIPTED_HIGH = """{"id":"scripted","providerID":"sim","variant":"high"}"""
        const val SUMMARY = "## Objective\n- Exercise queue and steer prompts.\n\n## Next Move\n- Wait."
    }
}
