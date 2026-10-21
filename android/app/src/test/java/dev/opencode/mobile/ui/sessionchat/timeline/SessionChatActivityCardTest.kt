package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionChatActivityCardTest {
    private fun tool(
        id: String,
        name: String = "read",
        status: ToolStatus = ToolStatus.Completed,
        input: JsonObject? = null,
        metadata: JsonObject? = null,
        error: String? = null,
    ) = MessagePart.Tool(toolName = name, status = status, id = id, input = input, metadata = metadata, error = error)

    private fun text(
        id: String,
        value: String,
    ) = MessagePart.Text(text = value, id = id)

    private fun build(parts: List<MessagePart>): List<SessionChatTimelineBlock> =
        (SessionChatTimelineBlocks.build(MessageRole.Assistant, parts, activityCard = true) as SessionChatTimelineBlocksResult.Ok)
            .blocks

    private fun cards(parts: List<MessagePart>) = build(parts).filterIsInstance<SessionChatTimelineBlock.ActivityCard>()

    private fun step(
        id: String,
        family: SessionChatStepFamily,
        status: SessionChatStepStatus = SessionChatStepStatus.Completed,
        interrupted: Boolean = false,
    ) = SessionChatStep(
        id = id,
        kind = if (family == SessionChatStepFamily.Agent) SessionChatStepKind.ChildSession else SessionChatStepKind.Tool,
        status = status,
        title = id,
        family = family,
        isInterrupted = interrupted,
    )

    private fun item(
        id: String,
        blocks: List<SessionChatTimelineBlock>,
    ) = SessionChatMessageItem(id = id, role = MessageRole.Assistant, text = "", metadataLabel = "", blocks = blocks)

    // Block builder

    @Test
    fun cardId_staysStableAsStepsAppend() {
        val first = cards(listOf(tool("p1", status = ToolStatus.Running))).single()
        val grown =
            cards(
                listOf(
                    tool("p1"),
                    tool("p2", name = "grep", status = ToolStatus.Running),
                    tool("p3", name = "bash", status = ToolStatus.Running),
                ),
            ).single()
        assertEquals("ac:p1", first.blockID)
        assertEquals(first.blockID, grown.blockID)
        assertEquals(listOf("p1", "p2", "p3"), grown.steps.map { it.id })
    }

    @Test
    fun text_splitsCards() {
        val blocks = build(listOf(tool("p1"), text("t1", "Looking further."), tool("p2"), tool("p3")))
        assertEquals(3, blocks.size)
        assertEquals("ac:p1", blocks[0].blockID)
        assertTrue(blocks[1] is SessionChatTimelineBlock.Text)
        assertEquals("ac:p2", blocks[2].blockID)
        assertEquals(2, (blocks[2] as SessionChatTimelineBlock.ActivityCard).steps.size)
    }

    @Test
    fun hiddenParts_areSeeThrough() {
        val parts =
            listOf(
                tool("p1"),
                tool("todo", name = "todowrite", status = ToolStatus.Running),
                MessagePart.Reasoning(text = "hmm", isStreaming = true, id = "r1"),
                text("blank", "   "),
                tool("p2"),
            )
        val card = build(parts).single() as SessionChatTimelineBlock.ActivityCard
        assertEquals(listOf("p1", "p2"), card.steps.map { it.id })
    }

    @Test
    fun toolOnlyMessagesInOneTurn_mergeIntoOneCard() {
        fun body(
            id: String,
            toolId: String,
        ): DisplayMessageBody {
            val parts = listOf(tool(toolId, name = "bash", input = buildJsonObject { put("command", "ls") }))
            return DisplayMessageBody(
                message = Message(id = id, role = MessageRole.Assistant, text = "", parts = parts),
                blocks = build(parts),
            )
        }
        val merged =
            SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
                listOf(body("a1", "p1"), body("a2", "p2"), body("a3", "p3")),
                collapseConsecutiveToolCalls = false,
                activityCard = true,
            )
        val card = merged.single().blocks.single() as SessionChatTimelineBlock.ActivityCard
        assertEquals("ac:p1", card.blockID)
        assertEquals(listOf("p1", "p2", "p3"), card.steps.map { it.id })
    }

    @Test
    fun thinking_onlyForLiveCardWithNothingRunning() {
        val settled = cards(listOf(tool("p1"))).single()
        val running = cards(listOf(tool("p1", status = ToolStatus.Running))).single()

        val settledCardMessages = SessionChatStepRail.markLive(listOf(item("a", listOf(settled))), isTurnActive = true)
        val settledCard = settledCardMessages.single().blocks.single() as SessionChatTimelineBlock.ActivityCard
        assertTrue(settledCard.isLive)
        assertTrue(settledCard.thinking)

        val runningCardMessages = SessionChatStepRail.markLive(listOf(item("a", listOf(running))), isTurnActive = true)
        val runningCard = runningCardMessages.single().blocks.single() as SessionChatTimelineBlock.ActivityCard
        assertTrue(runningCard.isLive)
        assertFalse(runningCard.thinking)
        assertTrue(SessionChatStepRail.hasLiveRail(listOf(item("a", listOf(runningCard)))))

        val idle = listOf(item("a", listOf(settled)))
        assertSame(idle, SessionChatStepRail.markLive(idle, isTurnActive = false))
        assertFalse(SessionChatStepRail.hasLiveRail(idle))
    }

    @Test
    fun liveCard_onlyWhenCardIsTheTrailingBlock() {
        val messages = listOf(item("a", build(listOf(tool("p1"), text("t1", "Done.")))))
        val marked = SessionChatStepRail.markLive(messages, isTurnActive = true)
        assertSame(messages, marked)
        assertFalse(SessionChatStepRail.hasLiveRail(marked))
    }

    @Test
    fun titlesAndFamilies() {
        val file = buildJsonObject { put("filePath", "/repo/src/App.kt") }
        val pattern = buildJsonObject { put("pattern", "branchPill") }
        val command = buildJsonObject { put("command", "npm test") }
        val card =
            cards(
                listOf(
                    tool("r1", "read", ToolStatus.Running, file),
                    tool("g1", "grep", ToolStatus.Completed, pattern),
                    tool("e1", "edit", ToolStatus.Running, file),
                    tool("b1", "bash", ToolStatus.Completed, command),
                    tool("w1", "webfetch", ToolStatus.Completed, buildJsonObject { put("url", "https://example.com/a") }),
                ),
            ).single()
        val byId = card.steps.associateBy { it.id }
        assertEquals("Reading App.kt", byId.getValue("r1").runningTitle)
        assertEquals("App.kt", byId.getValue("r1").settledTitle)
        assertEquals("Searching branchPill", byId.getValue("g1").runningTitle)
        assertEquals("branchPill", byId.getValue("g1").settledTitle)
        assertEquals("Editing App.kt", byId.getValue("e1").runningTitle)
        assertEquals("Running npm test", byId.getValue("b1").runningTitle)
        assertEquals("Ran npm test", byId.getValue("b1").settledTitle)
        assertEquals(
            listOf(
                SessionChatStepFamily.Read,
                SessionChatStepFamily.Search,
                SessionChatStepFamily.Edit,
                SessionChatStepFamily.Command,
                SessionChatStepFamily.Fetch,
            ),
            card.steps.map { it.family },
        )
    }

    @Test
    fun childSessionAndThought_families() {
        val input =
            buildJsonObject {
                put("subagent_type", "explore")
                put("description", "Find the branch pill")
            }
        val parts =
            listOf(
                MessagePart.Reasoning(text = "plan", id = "r1", timeStartEpochMs = 1_000, timeEndEpochMs = 3_500),
                tool("c1", "task", ToolStatus.Running, input, buildJsonObject { put("sessionId", "child-1") }),
            )
        val card = cards(parts).single()
        assertEquals("ac:r1", card.blockID)
        assertEquals(SessionChatStepFamily.Thought, card.steps[0].family)
        assertEquals(SessionChatStepFamily.Agent, card.steps[1].family)
        assertEquals("child-1", card.steps[1].childSession?.childSessionId)
        assertEquals("Find the branch pill", card.steps[1].childSession?.description)
    }

    @Test
    fun question_isNotCountedAsAFamily() {
        val card = cards(listOf(tool("q1", "question"), tool("p1"))).single()
        assertEquals(null, card.steps[0].family)
        assertEquals("2 steps · 1 read", SessionChatActivityCard.settledSummary(card.steps))
    }

    @Test
    fun otherTimelines_produceNoCards() {
        val parts = listOf(tool("p1"), tool("p2"))
        listOf(
            SessionChatTimelineBlocks.build(MessageRole.Assistant, parts),
            SessionChatTimelineBlocks.build(MessageRole.Assistant, parts, chipTimeline = true),
            SessionChatTimelineBlocks.build(MessageRole.Assistant, parts, stepRail = true),
        ).forEach { result ->
            assertTrue((result as SessionChatTimelineBlocksResult.Ok).blocks.none { it is SessionChatTimelineBlock.ActivityCard })
        }
    }

    // Running, done and summaries

    @Test
    fun runningAndDone_splitInOrder() {
        val steps =
            listOf(
                step("a", SessionChatStepFamily.Read),
                step("b", SessionChatStepFamily.Search, SessionChatStepStatus.Running),
                step("c", SessionChatStepFamily.Edit, SessionChatStepStatus.Failed),
                step("d", SessionChatStepFamily.Command, SessionChatStepStatus.Pending),
            )
        assertEquals(listOf("b", "d"), SessionChatActivityCard.running(steps).map { it.id })
        assertEquals(listOf("a", "c"), SessionChatActivityCard.done(steps).map { it.id })
    }

    @Test
    fun summary_countsFamiliesInFirstSeenOrder() {
        val done =
            listOf(
                step("a", SessionChatStepFamily.Read),
                step("b", SessionChatStepFamily.Search),
                step("c", SessionChatStepFamily.Read),
                step("d", SessionChatStepFamily.Edit),
                step("e", SessionChatStepFamily.Read),
                step("f", SessionChatStepFamily.Edit),
                step("g", SessionChatStepFamily.Agent),
                step("h", SessionChatStepFamily.Command),
                step("i", SessionChatStepFamily.Command),
            )
        assertEquals(
            "9 done · 3 reads · 1 search · 2 edits · 1 agent · 2 commands",
            SessionChatActivityCard.summary(done),
        )
        assertEquals(
            "9 steps · 3 reads · 1 search · 2 edits · 1 agent · 2 commands",
            SessionChatActivityCard.settledSummary(done),
        )
    }

    @Test
    fun summary_singularAndPlural() {
        fun two(family: SessionChatStepFamily) = listOf(step("x", family), step("y", family))
        assertEquals("1 step · 1 read", SessionChatActivityCard.settledSummary(listOf(step("a", SessionChatStepFamily.Read))))
        assertEquals("2 searches", SessionChatActivityCard.familySummary(two(SessionChatStepFamily.Search)))
        assertEquals("2 fetches", SessionChatActivityCard.familySummary(two(SessionChatStepFamily.Fetch)))
        assertEquals("2 thoughts", SessionChatActivityCard.familySummary(two(SessionChatStepFamily.Thought)))
        assertEquals("2 agents", SessionChatActivityCard.familySummary(two(SessionChatStepFamily.Agent)))
        assertEquals(
            "1 fetch · 1 thought · 1 command",
            SessionChatActivityCard.familySummary(
                listOf(
                    step("a", SessionChatStepFamily.Fetch),
                    step("b", SessionChatStepFamily.Thought),
                    step("c", SessionChatStepFamily.Command),
                ),
            ),
        )
    }

    @Test
    fun summary_emptyDone() {
        assertEquals("nothing finished yet", SessionChatActivityCard.summary(emptyList()))
    }

    @Test
    fun worstStatus_failedOnlyForRealFailures() {
        val ok = listOf(step("a", SessionChatStepFamily.Read))
        val interrupted = ok + step("b", SessionChatStepFamily.Command, SessionChatStepStatus.Failed, interrupted = true)
        val failed = ok + step("c", SessionChatStepFamily.Command, SessionChatStepStatus.Failed)
        assertEquals(SessionChatStepStatus.Completed, SessionChatActivityCard.worstStatus(ok))
        assertEquals(SessionChatStepStatus.Completed, SessionChatActivityCard.worstStatus(interrupted))
        assertEquals(SessionChatStepStatus.Failed, SessionChatActivityCard.worstStatus(failed))
        val card = cards(listOf(tool("p1"), tool("p2", status = ToolStatus.Error, error = "boom"))).single()
        assertEquals(SessionChatStepStatus.Failed, card.worstStatus)
    }
}
