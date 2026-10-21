package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.ui.sessionchat.view.sessionChatStepDetail
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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

    private fun card(
        steps: List<SessionChatStep>,
        isLive: Boolean = true,
    ) = SessionChatTimelineBlock.ActivityCard(blockID = "ac:${steps.first().id}", steps = steps, isLive = isLive)

    private fun runningTool(
        id: String,
        family: SessionChatStepFamily = SessionChatStepFamily.Read,
        startedAtMs: Long?,
        runningTitle: String = "Running $id",
    ) = SessionChatStep(
        id = id,
        kind = SessionChatStepKind.Tool,
        status = SessionChatStepStatus.Running,
        title = runningTitle,
        runningTitle = runningTitle,
        settledTitle = id,
        family = family,
        startedAtMs = startedAtMs,
    )

    private fun child(
        id: String,
        status: SessionChatStepStatus = SessionChatStepStatus.Running,
        childSessionId: String? = "ses-$id",
        description: String? = "Find the pill",
        startedAtMs: Long? = 1_000,
        toolsSoFar: Int? = null,
        result: String? = null,
        detail: String? = null,
    ) = SessionChatStep(
        id = id,
        kind = SessionChatStepKind.ChildSession,
        status = status,
        title = "explore",
        family = SessionChatStepFamily.Agent,
        detail = detail,
        startedAtMs = startedAtMs,
        childSession =
            SessionChatStepChildSession(
                agent = "explore",
                description = description,
                childSessionId = childSessionId,
                toolsSoFar = toolsSoFar,
                result = result,
            ),
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
    fun sheetDetail_showsTheChosenAnswerOfAQuestion() {
        val answered =
            tool(
                "q1",
                "question",
                input =
                    buildJsonObject {
                        put("questions", kotlinx.serialization.json.buildJsonArray { add(buildJsonObject { put("question", "Where?") }) })
                    },
                metadata =
                    buildJsonObject {
                        put("answers", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("Invoice field")) }) })
                    },
            )
        val steps = cards(listOf(answered, tool("g1", "grep", metadata = buildJsonObject { put("matches", 4) }))).single().steps
        assertEquals("Invoice field", SessionChatActivityCard.sheetDetail(steps[0]))
        assertEquals("4 matches", SessionChatActivityCard.sheetDetail(steps[1]))
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

    // Summaries

    @Test
    fun summary_countsEveryStepAndFamiliesInFirstSeenOrder() {
        val steps =
            listOf(
                step("a", SessionChatStepFamily.Read),
                step("b", SessionChatStepFamily.Search, SessionChatStepStatus.Running),
                step("c", SessionChatStepFamily.Read),
                step("d", SessionChatStepFamily.Edit),
                step("e", SessionChatStepFamily.Read),
                step("f", SessionChatStepFamily.Edit, SessionChatStepStatus.Failed),
                step("g", SessionChatStepFamily.Agent, SessionChatStepStatus.Running),
                step("h", SessionChatStepFamily.Command),
                step("i", SessionChatStepFamily.Command, SessionChatStepStatus.Pending),
            )
        assertEquals(
            "9 steps · 3 reads · 1 search · 2 edits · 1 agent · 2 commands",
            SessionChatActivityCard.settledSummary(steps),
        )
        assertEquals(
            "9 steps · 3 reads · 1 search · 2 edits · 1 agent · 2 commands",
            SessionChatActivityCard.live(card(steps)).summary,
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

    // Live card

    @Test
    fun live_agentsOnlyWhileRunning_withObservedActionOrDescription() {
        val steps =
            listOf(
                step("a", SessionChatStepFamily.Read),
                child("c1"),
                child("c2", childSessionId = "ses-unobserved", description = "Review the diff"),
                child("c3", status = SessionChatStepStatus.Completed),
            )
        val children =
            mapOf(
                "ses-c1" to
                    SessionChatChildActivity(
                        listOf(
                            runningTool("x1", startedAtMs = 10, runningTitle = "Reading A.kt"),
                            runningTool("x2", startedAtMs = 20, runningTitle = "Searching pill"),
                            step("x3", SessionChatStepFamily.Read),
                        ),
                    ),
            )
        val live = SessionChatActivityCard.live(card(steps), children)
        assertEquals(listOf("c1", "c2"), live.agents.map { it.id })
        assertEquals("Explore", live.agents[0].agentName)
        assertEquals("Searching pill", live.agents[0].action)
        assertEquals("Review the diff", live.agents[1].action)
        assertEquals(1_000L, live.agents[0].startedAtMs)
    }

    @Test
    fun live_currentIsLatestStartedRunningTool_withExtraCount() {
        val steps =
            listOf(
                runningTool("r1", startedAtMs = 5_000),
                runningTool("r2", SessionChatStepFamily.Edit, startedAtMs = 9_000, runningTitle = "Editing App.kt"),
                runningTool("r3", startedAtMs = 7_000),
                child("c1", startedAtMs = 20_000),
                step("d1", SessionChatStepFamily.Read),
            )
        val live = SessionChatActivityCard.live(card(steps))
        val current = live.current!!
        assertEquals("r2", current.stepId)
        assertEquals("Editing App.kt", current.title)
        assertEquals(SessionChatStepFamily.Edit, current.family)
        assertEquals(2, current.extraRunning)
        assertEquals(9_000L, current.startedAtMs)
        assertEquals(null, live.idleText)
        assertEquals(1_000L, SessionChatActivityCard.live(card(listOf(child("c1"), runningTool("r", startedAtMs = 3_000)))).startedAtMs)
    }

    @Test
    fun live_latestRunning_tieGoesToLaterStep() {
        val steps = listOf(runningTool("r1", startedAtMs = null), runningTool("r2", startedAtMs = null))
        assertEquals("r2", SessionChatActivityCard.latestRunning(steps)?.id)
    }

    @Test
    fun live_idleText_waitingForAgentsOrThinking() {
        val waiting = SessionChatActivityCard.live(card(listOf(step("a", SessionChatStepFamily.Read), child("c1"))))
        assertEquals(null, waiting.current)
        assertEquals("Waiting for agents", waiting.idleText)
        val thinking = SessionChatActivityCard.live(card(listOf(step("a", SessionChatStepFamily.Read), child("c1", SessionChatStepStatus.Completed))))
        assertEquals("Thinking…", thinking.idleText)
        assertTrue(thinking.agents.isEmpty())
    }

    // Settled line

    @Test
    fun settled_dedupesToolNamesInFirstSeenOrder() {
        val parts =
            listOf(
                MessagePart.Reasoning(text = "plan", id = "t1", timeStartEpochMs = 1, timeEndEpochMs = 2),
                tool("r1", "read"),
                tool("g1", "grep"),
                tool("r2", "read"),
                tool("e1", "edit"),
                tool("p1", "apply_patch"),
                tool("w1", "write"),
                tool("b1", "bash"),
                tool("f1", "webfetch"),
                tool("s1", "websearch"),
                tool("gl", "glob"),
                tool("k1", "skill"),
                tool("q1", "question"),
                tool("m1", "mcp_lookup"),
                tool("c1", "task", input = buildJsonObject { put("subagent_type", "explore") }),
            )
        val settled = SessionChatActivityCard.settled(cards(parts).single())
        assertEquals(15, settled.stepCount)
        assertEquals(
            listOf("Thought", "Read", "Grep", "Edit", "Write", "Shell", "Fetch", "Search", "Glob", "Skill", "Question", "Mcp_lookup", "Agent"),
            settled.names,
        )
        assertEquals(SessionChatStepStatus.Completed, settled.worstStatus)
    }

    @Test
    fun settled_worstStatusIgnoresInterruptions() {
        val interrupted = card(listOf(step("a", SessionChatStepFamily.Read), step("b", SessionChatStepFamily.Command, SessionChatStepStatus.Failed, interrupted = true)), isLive = false)
        val failed = card(listOf(step("a", SessionChatStepFamily.Read), step("b", SessionChatStepFamily.Command, SessionChatStepStatus.Failed)), isLive = false)
        assertEquals(SessionChatStepStatus.Completed, SessionChatActivityCard.settled(interrupted).worstStatus)
        assertEquals(SessionChatStepStatus.Failed, SessionChatActivityCard.settled(failed).worstStatus)
    }

    // Sheet

    @Test
    fun sheet_resolvesByBlockId_withAgentsAndSteps() {
        val target =
            card(
                listOf(
                    step("a", SessionChatStepFamily.Read),
                    child("c1", toolsSoFar = 2),
                    runningTool("r1", startedAtMs = 5),
                    child("c2", SessionChatStepStatus.Completed, result = "Found it in Header.kt"),
                    child("c3", SessionChatStepStatus.Failed, detail = "boom"),
                ),
            )
        val other = card(listOf(step("z", SessionChatStepFamily.Read)), isLive = false)
        val messages = listOf(item("m1", listOf(other)), item("m2", listOf(target)))
        val children =
            mapOf(
                "ses-c1" to
                    SessionChatChildActivity(
                        listOf(
                            step("x1", SessionChatStepFamily.Read),
                            step("x2", SessionChatStepFamily.Read),
                            step("x3", SessionChatStepFamily.Search),
                            runningTool("x4", startedAtMs = 9),
                        ),
                    ),
            )
        val sheet = SessionChatActivityCard.sheet(messages, "ac:a", children)!!
        assertEquals("m2", sheet.messageId)
        assertTrue(sheet.isLive)
        assertEquals(5, sheet.stepCount)
        assertEquals(listOf("a", "r1"), sheet.steps.map { it.id })
        assertEquals(listOf("c1", "c2", "c3"), sheet.agents.map { it.step.id })
        assertEquals("4 tools so far", sheet.agents[0].caption)
        assertEquals(listOf("x2", "x3", "x4"), sheet.agents[0].recent.map { it.id })
        assertEquals("Found it in Header.kt", sheet.agents[1].caption)
        assertTrue(sheet.agents[1].recent.isEmpty())
        assertEquals("boom", sheet.agents[2].caption)

        val unobserved = SessionChatActivityCard.sheet(messages, "ac:a")!!
        assertEquals("2 tools so far", unobserved.agents[0].caption)
        assertTrue(unobserved.agents[0].recent.isEmpty())

        assertFalse(SessionChatActivityCard.sheet(messages, "ac:z")!!.isLive)
        assertEquals(null, SessionChatActivityCard.sheet(messages, "ac:gone"))
        assertTrue(SessionChatActivityCard.hasCard(messages, "ac:z"))
        assertFalse(SessionChatActivityCard.hasCard(messages, "ac:gone"))
    }

    @Test
    fun hasStep_onlyForAStepOfThatCard() {
        val steps = listOf(step("a", SessionChatStepFamily.Read), step("b", SessionChatStepFamily.Search))
        val messages = listOf(item("m1", listOf(card(steps))))
        assertTrue(SessionChatActivityCard.hasStep(messages, "ac:a", "b"))
        assertFalse(SessionChatActivityCard.hasStep(messages, "ac:a", "z"))
        assertFalse(SessionChatActivityCard.hasStep(messages, "ac:gone", "a"))
    }

    @Test
    fun stepDetail_inSheetForThoughtsAndToolText_noneForNavigatingSteps() {
        val parts =
            listOf(
                MessagePart.Reasoning(text = "Plan the fix", id = "r1", timeStartEpochMs = 1_000, timeEndEpochMs = 3_500),
                tool("b1", "bash", input = buildJsonObject { put("command", "ls") }).copy(output = "a.txt\nb.txt"),
                tool("e1", "edit", input = buildJsonObject { put("filePath", "/repo/a.kt") }),
                tool(
                    "c1",
                    "task",
                    input = buildJsonObject { put("subagent_type", "explore") },
                    metadata = buildJsonObject { put("sessionId", "ses-c") },
                ),
                tool("f1", "bash", ToolStatus.Error, input = buildJsonObject { put("command", "make") }, error = "make: no target"),
            )
        val steps = cards(parts).single().steps.associateBy { it.id }

        val thought = sessionChatStepDetail(steps.getValue("r1"), supportsSessionMessageDiff = true)
        assertEquals("Thought", thought?.title)
        assertEquals("Plan the fix", thought?.body)

        val shell = sessionChatStepDetail(steps.getValue("b1"), supportsSessionMessageDiff = true)
        assertTrue(shell?.body.orEmpty().contains("a.txt"))

        val failed = sessionChatStepDetail(steps.getValue("f1"), supportsSessionMessageDiff = true)
        assertTrue(failed?.body.orEmpty().contains("make: no target"))

        val questions = buildJsonArray { add(buildJsonObject { put("question", "Where?") }) }
        val answers = buildJsonArray { add(buildJsonArray { add(JsonPrimitive("Invoice field")) }) }
        val question =
            tool(
                "q1",
                "question",
                input = buildJsonObject { put("questions", questions) },
                metadata = buildJsonObject { put("answers", answers) },
            )
        val answered = sessionChatStepDetail(cards(listOf(question)).single().steps.single(), supportsSessionMessageDiff = true)
        assertTrue(answered?.body.orEmpty().contains("Invoice field"))

        // Without session diffs an edit shows its own detail, as in the standard timeline.
        val editCard = steps.getValue("e1").card
        if (editCard?.detailText != null) {
            assertEquals(editCard.detailText, sessionChatStepDetail(steps.getValue("e1"), supportsSessionMessageDiff = false)?.body)
        }

        // A child session and an edit with Changes navigate instead.
        assertEquals(null, sessionChatStepDetail(steps.getValue("c1"), supportsSessionMessageDiff = true))
        assertEquals(null, sessionChatStepDetail(steps.getValue("e1"), supportsSessionMessageDiff = true))
    }

    @Test
    fun observedChildSessionIds_runningChildrenOfLiveOrPresentedCards() {
        val live = card(listOf(child("c1"), child("c2", SessionChatStepStatus.Completed), child("c3", childSessionId = null)))
        val settled = card(listOf(child("c4")), isLive = false)
        val messages = listOf(item("m1", listOf(settled)), item("m2", listOf(live)))
        assertEquals(setOf("ses-c1"), SessionChatActivityCard.observedChildSessionIds(messages, presentedBlockId = null))
        assertEquals(setOf("ses-c1", "ses-c4"), SessionChatActivityCard.observedChildSessionIds(messages, presentedBlockId = "ac:c4"))
    }

    @Test
    fun childActivity_readsVisibleToolStepsOfAssistantMessages() {
        val file = buildJsonObject { put("filePath", "/repo/src/App.kt") }
        val messages =
            listOf(
                Message(id = "u", role = MessageRole.User, text = "go", parts = listOf(text("t", "go"))),
                Message(
                    id = "a",
                    role = MessageRole.Assistant,
                    text = "",
                    parts =
                        listOf(
                            tool("r1", "read", input = file),
                            tool("todo", "todowrite", ToolStatus.Running),
                            tool("r2", "read", ToolStatus.Running, file),
                        ),
                ),
            )
        val activity = SessionChatActivityCard.childActivity(messages)
        assertEquals(listOf("r1", "r2"), activity.steps.map { it.id })
        assertEquals(2, activity.toolCount)
        assertEquals("Reading App.kt", activity.currentAction)
    }

    @Test
    fun childResult_firstLineOfTaskResult() {
        val output = "task_id: ses_1 (for resuming)\n\n<task_result>\n## Found it\nIn Header.kt line 4\n</task_result>"
        val parts =
            listOf(tool("c1", "task", input = buildJsonObject { put("subagent_type", "explore") }).copy(output = output))
        assertEquals("Found it", cards(parts).single().steps.single().childSession?.result)
        val running =
            listOf(tool("c1", "task", ToolStatus.Running, buildJsonObject { put("subagent_type", "explore") }).copy(output = output))
        assertEquals(null, cards(running).single().steps.single().childSession?.result)
    }

    @Test
    fun childResult_skipsWrapperTags() {
        val output = "<subagent sessionID=\"ses_2\">\nStandards look fine.\n</subagent>"
        val parts =
            listOf(tool("c1", "task", input = buildJsonObject { put("subagent_type", "general") }).copy(output = output))
        assertEquals("Standards look fine.", cards(parts).single().steps.single().childSession?.result)
        val tagsOnly =
            listOf(tool("c1", "task", input = buildJsonObject { put("subagent_type", "general") }).copy(output = "<subagent/>"))
        assertEquals(null, cards(tagsOnly).single().steps.single().childSession?.result)
    }
}
