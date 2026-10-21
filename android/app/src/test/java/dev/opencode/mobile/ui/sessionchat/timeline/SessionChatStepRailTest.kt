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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionChatStepRailTest {
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
        (SessionChatTimelineBlocks.build(MessageRole.Assistant, parts, stepRail = true) as SessionChatTimelineBlocksResult.Ok).blocks

    private fun rails(parts: List<MessagePart>) = build(parts).filterIsInstance<SessionChatTimelineBlock.StepRail>()

    private fun step(
        id: String,
        status: SessionChatStepStatus = SessionChatStepStatus.Completed,
        kind: SessionChatStepKind = SessionChatStepKind.Tool,
    ) = SessionChatStep(id = id, kind = kind, status = status, title = id)

    // Block builder

    @Test
    fun railId_staysStableAsStepsAppend() {
        val first = rails(listOf(tool("p1"))).single()
        val grown = rails(listOf(tool("p1"), tool("p2"), tool("p3", name = "bash", status = ToolStatus.Running))).single()
        assertEquals("sr:p1", first.blockID)
        assertEquals(first.blockID, grown.blockID)
        assertEquals(listOf("p1", "p2", "p3"), grown.steps.map { it.id })
    }

    @Test
    fun text_splitsRails() {
        val blocks = build(listOf(tool("p1"), text("t1", "Looking further."), tool("p2"), tool("p3")))
        assertEquals(3, blocks.size)
        assertEquals("sr:p1", blocks[0].blockID)
        assertTrue(blocks[1] is SessionChatTimelineBlock.Text)
        assertEquals("sr:p2", blocks[2].blockID)
        assertEquals(2, (blocks[2] as SessionChatTimelineBlock.StepRail).steps.size)
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
        val blocks = build(parts)
        val rail = blocks.single() as SessionChatTimelineBlock.StepRail
        assertEquals(listOf("p1", "p2"), rail.steps.map { it.id })
    }

    @Test
    fun parallelRunningSteps_markSecondAsBranch() {
        val rail =
            rails(
                listOf(
                    tool("p1"),
                    tool("p2", status = ToolStatus.Running),
                    tool("p3", name = "grep", status = ToolStatus.Running),
                ),
            ).single()
        assertEquals(listOf(false, false, true), rail.steps.map { it.isBranch })
    }

    @Test
    fun thinking_onlyForLiveRailWithNothingRunning() {
        val settled = rails(listOf(tool("p1"))).single()
        val running = rails(listOf(tool("p1", status = ToolStatus.Running))).single()

        fun item(
            id: String,
            rail: SessionChatTimelineBlock.StepRail,
        ) = SessionChatMessageItem(id = id, role = MessageRole.Assistant, text = "", metadataLabel = "", blocks = listOf(rail))

        val settledLive = SessionChatStepRail.markLive(listOf(item("a", settled)), isTurnActive = true)
        val settledRail = settledLive.single().blocks.single() as SessionChatTimelineBlock.StepRail
        assertTrue(settledRail.isLive)
        assertTrue(settledRail.thinking)

        val runningLive = SessionChatStepRail.markLive(listOf(item("a", running)), isTurnActive = true)
        val runningRail = runningLive.single().blocks.single() as SessionChatTimelineBlock.StepRail
        assertTrue(runningRail.isLive)
        assertFalse(runningRail.thinking)

        val idle = listOf(item("a", settled))
        assertSame(idle, SessionChatStepRail.markLive(idle, isTurnActive = false))
        assertFalse(SessionChatStepRail.hasLiveRail(idle))
    }

    @Test
    fun liveRail_onlyWhenRailIsTheTrailingBlock() {
        val blocks = build(listOf(tool("p1"), text("t1", "Done.")))
        val messages =
            listOf(SessionChatMessageItem(id = "a", role = MessageRole.Assistant, text = "", metadataLabel = "", blocks = blocks))
        val marked = SessionChatStepRail.markLive(messages, isTurnActive = true)
        assertSame(messages, marked)
        assertFalse(SessionChatStepRail.hasLiveRail(marked))
    }

    @Test
    fun toolOnlyMessagesInOneTurn_mergeIntoOneRail() {
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
                stepRail = true,
            )
        val rail = merged.single().blocks.single() as SessionChatTimelineBlock.StepRail
        assertEquals("sr:p1", rail.blockID)
        assertEquals(listOf("p1", "p2", "p3"), rail.steps.map { it.id })
    }

    @Test
    fun worstStatus_failedWhenAnyStepFailed() {
        val ok = rails(listOf(tool("p1"), tool("p2"))).single()
        val failed = rails(listOf(tool("p1"), tool("p2", status = ToolStatus.Error, error = "boom"))).single()
        assertEquals(SessionChatStepStatus.Completed, ok.worstStatus)
        assertEquals(SessionChatStepStatus.Failed, failed.worstStatus)
        assertEquals("boom", failed.steps[1].detail)
    }

    @Test
    fun titles_useProductWords() {
        val file = buildJsonObject { put("filePath", "/repo/src/App.kt") }
        val pattern = buildJsonObject { put("pattern", "branchPill") }
        val command = buildJsonObject { put("command", "./gradlew :app:testDebugUnitTest --tests SomeVeryLongClassName") }
        val rail =
            rails(
                listOf(
                    tool("r1", "read", ToolStatus.Running, file),
                    tool("r2", "read", ToolStatus.Completed, file),
                    tool("g1", "grep", ToolStatus.Running, pattern),
                    tool("g2", "grep", ToolStatus.Completed, pattern, buildJsonObject { put("matches", 4) }),
                    tool("e1", "edit", ToolStatus.Running, file),
                    tool("e2", "edit", ToolStatus.Completed, file),
                    tool("b1", "bash", ToolStatus.Running, command),
                    tool("b2", "bash", ToolStatus.Completed, command),
                ),
            ).single()
        val titles = rail.steps.associate { it.id to it.title }
        assertEquals("Reading App.kt", titles["r1"])
        assertEquals("App.kt", titles["r2"])
        assertEquals("Searching branchPill", titles["g1"])
        assertEquals("branchPill", titles["g2"])
        assertEquals("4 matches", rail.steps.first { it.id == "g2" }.detail)
        assertEquals("Editing App.kt", titles["e1"])
        assertEquals("App.kt", titles["e2"])
        assertEquals("Running ./gradlew :app:testDebugUnitTest --tests…", titles["b1"])
        assertEquals("Ran ./gradlew :app:testDebugUnitTest --tests…", titles["b2"])
    }

    @Test
    fun childSession_isAgentStepWithDescription() {
        val input =
            buildJsonObject {
                put("subagent_type", "explore")
                put("description", "Find the branch pill")
            }
        val metadata = buildJsonObject { put("sessionId", "child-1") }
        val step = rails(listOf(tool("c1", "task", ToolStatus.Running, input, metadata))).single().steps.single()
        assertEquals(SessionChatStepKind.ChildSession, step.kind)
        assertEquals("explore", step.title)
        assertEquals("Find the branch pill", step.detail)
        assertEquals("child-1", step.childSession?.childSessionId)
        assertEquals("child-1", step.card?.childSessionId)
    }

    @Test
    fun settledThought_isAStep() {
        val parts =
            listOf(
                MessagePart.Reasoning(text = "plan", id = "r1", timeStartEpochMs = 1_000, timeEndEpochMs = 3_500),
                tool("p1"),
            )
        val rail = rails(parts).single()
        assertEquals("sr:r1", rail.blockID)
        assertEquals(SessionChatStepKind.Thought, rail.steps[0].kind)
        assertEquals("Thought · 2.5s", rail.steps[0].title)
        assertNull(rail.steps[0].card)
    }

    @Test
    fun standardAndChipTimelines_produceNoRails() {
        val parts = listOf(tool("p1"), tool("p2"))
        val standard = (SessionChatTimelineBlocks.build(MessageRole.Assistant, parts) as SessionChatTimelineBlocksResult.Ok).blocks
        val chipsResult = SessionChatTimelineBlocks.build(MessageRole.Assistant, parts, chipTimeline = true)
        val chips = (chipsResult as SessionChatTimelineBlocksResult.Ok).blocks
        assertTrue(standard.none { it is SessionChatTimelineBlock.StepRail })
        assertTrue(chips.none { it is SessionChatTimelineBlock.StepRail })
    }

    // Fold

    @Test
    fun fold_fourSettledSteps_doNotFold() {
        val entries = SessionChatStepRail.entries((1..4).map { step("s$it") }, unfolded = false)
        assertEquals(4, entries.size)
        assertTrue(entries.all { it is SessionChatStepRail.Entry.Step })
    }

    @Test
    fun fold_fiveSettledSteps_foldTwoAndKeepLastThree() {
        val entries = SessionChatStepRail.entries((1..5).map { step("s$it") }, unfolded = false)
        assertEquals(SessionChatStepRail.Entry.Fold(count = 2, status = SessionChatStepStatus.Completed), entries.first())
        assertEquals(listOf("s3", "s4", "s5"), entries.drop(1).map { (it as SessionChatStepRail.Entry.Step).step.id })
    }

    @Test
    fun fold_runningStepsAndChildSessionsNeverFold() {
        val steps =
            listOf(
                step("s1"),
                step("child", kind = SessionChatStepKind.ChildSession),
                step("s2", status = SessionChatStepStatus.Failed),
                step("s3"),
                step("s4"),
                step("s5"),
                step("s6"),
                step("run", status = SessionChatStepStatus.Running),
            )
        val entries = SessionChatStepRail.entries(steps, unfolded = false)
        val fold = entries.first() as SessionChatStepRail.Entry.Fold
        assertEquals(3, fold.count)
        assertEquals(SessionChatStepStatus.Failed, fold.status)
        assertEquals(
            listOf("child", "s4", "s5", "s6", "run"),
            entries.drop(1).map { (it as SessionChatStepRail.Entry.Step).step.id },
        )
    }

    @Test
    fun fold_unfoldedShowsAll() {
        val steps = (1..7).map { step("s$it") }
        val entries = SessionChatStepRail.entries(steps, unfolded = true)
        assertEquals(steps.map { it.id }, entries.map { (it as SessionChatStepRail.Entry.Step).step.id })
    }
}
