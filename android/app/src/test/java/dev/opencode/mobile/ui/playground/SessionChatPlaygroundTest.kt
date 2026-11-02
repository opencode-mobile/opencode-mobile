package dev.opencode.mobile.ui.playground

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.domain.message.AttachmentKind
import dev.opencode.mobile.domain.message.DisplayMessage
import dev.opencode.mobile.domain.message.DisplayMessagePreparing
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessagePreparationMode
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.MessageStopReason
import dev.opencode.mobile.domain.message.StreamingHighlight
import dev.opencode.mobile.domain.message.TimelineNotice
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.preferences.InMemoryPreferences
import dev.opencode.mobile.domain.preferences.SessionChatDisplayPreferences
import dev.opencode.mobile.domain.preferences.SessionChatTimelineStyle
import dev.opencode.mobile.domain.session.SessionActivity
import dev.opencode.mobile.domain.session.SessionTodoStatus
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.ui.sessionchat.SessionChatBackgroundWork
import dev.opencode.mobile.ui.sessionchat.pendingPromptsFrom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionChatPlaygroundTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun everyScenarioBuildsAConsistentWorld() {
        for (id in PlaygroundScenarioId.entries) {
            val scenario = PlaygroundScenarios.build(id, NOW)
            assertEquals(id, scenario.id)
            assertNotNull("$id binds a session it has", scenario.initial.session(scenario.boundSessionId))
            assertConsistent(id, scenario.initial)
            var state = scenario.initial
            for (step in scenario.steps) {
                state = step.apply(state, NOW)
                assertConsistent(id, state)
            }
            assertConsistent(id, scenario.finalState(NOW))
        }
    }

    @Test
    fun sheetListsTwentyOneScenariosInFourGroups() {
        val codes = PlaygroundScenarioId.entries.map { it.code }
        assertEquals(
            (1..5).map { "M$it" } + (1..4).map { "L$it" } + (1..11).map { "P$it" } + listOf("S1"),
            codes,
        )
        assertEquals(
            PlaygroundScenarioGroup.entries,
            PlaygroundScenarioId.entries.map { it.group }.distinct(),
        )
        PlaygroundScenarioId.entries.forEach { id ->
            assertEquals("$id code matches its group", id.group.title.first(), id.code.first())
            assertTrue(id.summary.isNotBlank())
        }
    }

    @Test
    fun liveScenariosJumpToLatestAndFullLifecycleAreScripted() {
        val scripted = PlaygroundScenarioId.entries.filter { PlaygroundScenarios.build(it, NOW).isScripted }
        assertEquals(
            listOf(
                PlaygroundScenarioId.StreamingTurn,
                PlaygroundScenarioId.FollowUps,
                PlaygroundScenarioId.Subagents,
                PlaygroundScenarioId.AbortAndRetry,
                PlaygroundScenarioId.JumpToLatest,
                PlaygroundScenarioId.FullLifecycle,
            ),
            scripted,
        )
    }

    @Test
    fun contentStaysObviouslyFake() {
        for (id in PlaygroundScenarioId.entries) {
            val scenario = PlaygroundScenarios.build(id, NOW)
            // Everything the world holds: titles, directories, message text, tool input, output
            // and metadata, file names, branch, changed files, diffs and requests.
            val text = listOf(scenario.initial, scenario.finalState(NOW)).joinToString("\n") { it.toString() }
            Regex("""https?://([a-z0-9.-]+)""").findAll(text).forEach { match ->
                assertTrue("$id links ${match.value}", match.groupValues[1].endsWith("example.com"))
            }
            Regex("""/Users/([A-Za-z0-9_-]+)""").findAll(text).forEach { match ->
                assertEquals("$id names a home folder", "example", match.groupValues[1])
            }
            scenario.finalState(NOW).sessions.forEach { session ->
                assertTrue(
                    "$id session ${session.id} lives in the fake project",
                    session.directory in listOf(PlaygroundContent.WORKTREE, PlaygroundContent.WORKTREE_CHECKOUT),
                )
                assertEquals(PlaygroundContent.PROJECT_ID, session.projectId)
            }
            val paths = scenario.initial.changedFiles.map { it.path } + scenario.initial.turnDiff.map { it.path }
            assertTrue("$id changes only fake project files", paths.all { it.startsWith("src/") })
            scenario.initial.branch?.let { assertEquals(PlaygroundContent.BRANCH, it) }
        }
        assertTrue(PlaygroundContent.WORKTREE.startsWith("/Users/example/acme-"))
        assertTrue(PlaygroundContent.WORKTREE_CHECKOUT.startsWith("/Users/example/acme-"))
    }

    @Test
    fun everyScenarioKeepsItsMessagesInTimeOrder() {
        for (id in PlaygroundScenarioId.entries) {
            val scenario = PlaygroundScenarios.build(id, NOW)
            for (state in listOf(scenario.initial, scenario.finalState(NOW))) {
                for ((sessionId, messages) in state.messages) {
                    val times = messages.mapNotNull { it.sentAtEpochMs }
                    assertEquals("$id/$sessionId is out of order", times.sorted(), times)
                }
            }
        }
    }

    @Test
    fun messageScenariosCoverWhatTheyName() {
        fun messages(id: PlaygroundScenarioId) = PlaygroundScenarios.build(id, NOW).let { it.initial.messages(it.boundSessionId) }

        assertEquals(28, messages(PlaygroundScenarioId.LongConversation).size)

        val user = messages(PlaygroundScenarioId.UserMessages)
        val files = user.flatMap { it.parts }.filterIsInstance<MessagePart.File>()
        assertEquals(4, files.count { it.kind == AttachmentKind.Image })
        assertEquals(1, files.count { it.kind == AttachmentKind.Pdf })
        assertTrue(user.any { it.promptSkills.isNotEmpty() })
        assertTrue(user.any { it.role == MessageRole.User && it.text.contains('\n') })
        assertTrue(user.flatMap { it.parts }.any { it is MessagePart.Tool && it.title == "Shell" && it.output != null })

        val replies = PlaygroundScenarios.build(PlaygroundScenarioId.AgentReplies, NOW)
        val tools =
            replies.initial
                .messages(ROOT)
                .flatMap { it.parts }
                .filterIsInstance<MessagePart.Tool>()
        assertEquals(ToolStatus.entries.toSet(), tools.map { it.status }.toSet())
        assertTrue(tools.any { it.toolName == "edit" && it.metadata != null })
        assertTrue(tools.any { it.toolName == "todowrite" })
        assertTrue(tools.any { it.toolName == "task" && it.status == ToolStatus.Completed })
        assertTrue(
            replies.initial
                .messages(ROOT)
                .flatMap { it.parts }
                .any { it is MessagePart.Reasoning },
        )

        val notices = PlaygroundScenarios.build(PlaygroundScenarioId.NoticesAndFailures, NOW).initial
        val noticeKinds =
            notices
                .messages(ROOT)
                .flatMap { it.parts }
                .filterIsInstance<MessagePart.Notice>()
                .map { it.notice::class }
        assertEquals(
            setOf(
                TimelineNotice.AgentChanged::class,
                TimelineNotice.ModelChanged::class,
                TimelineNotice.Moved::class,
                TimelineNotice.InstructionsUpdated::class,
            ),
            noticeKinds.toSet(),
        )
        assertTrue(notices.messages(ROOT).flatMap { it.parts }.any { it is MessagePart.Compaction })
        assertTrue(notices.messages(ROOT).any { it.stopReason == MessageStopReason.Interrupted })
        assertTrue(notices.messages(ROOT).any { it.failure != null })
        assertTrue(notices.activity(ROOT) is SessionActivity.Retrying)

        assertTrue(messages(PlaygroundScenarioId.EmptySession).isEmpty())
    }

    @Test
    fun streamingTurnStreamsThenEndsIdle() {
        val scenario = PlaygroundScenarios.build(PlaygroundScenarioId.StreamingTurn, NOW)
        assertEquals(10, scenario.steps.size)
        val streaming = scenario.steps.filter { it.frames.size > 1 }.map { it.label }
        assertEquals(listOf("Reasoning streams", "Reply streams"), streaming)

        // Each frame of the reply shows more of it.
        var state = scenario.initial
        val replyStep = scenario.steps.indexOfFirst { it.label == "Reply streams" }
        scenario.steps.take(replyStep).forEach { state = it.apply(state, NOW) }
        val shown =
            scenario.steps[replyStep].frames.map { frame ->
                state = frame.apply(state, NOW)
                state.messages(ROOT).last().text
            }
        shown.zipWithNext().forEach { (before, after) -> assertTrue(after.startsWith(before) && after.length > before.length) }

        val final = scenario.finalState(NOW)
        assertFalse(final.isBusy(ROOT))
        val reply = final.messages(ROOT).last()
        assertEquals(shown.last(), reply.text)
        assertNotNull(reply.completedAtEpochMs)
        val tools = reply.parts.filterIsInstance<MessagePart.Tool>()
        assertEquals(listOf("read", "grep", "edit", "bash"), tools.map { it.toolName })
        assertTrue(tools.all { it.status == ToolStatus.Completed })
        assertTrue(
            reply.parts.none { (it as? MessagePart.Text)?.isStreaming == true || (it as? MessagePart.Reasoning)?.isStreaming == true },
        )
    }

    @Test
    fun followUpsDeliverTheSteerThenRunTheQueue() {
        val scenario = PlaygroundScenarios.build(PlaygroundScenarioId.FollowUps, NOW)
        val counts =
            scenario.steps.runningFold(scenario.initial) { state, step -> step.apply(state, NOW) }.map { state ->
                pendingPromptsFrom(state.messages(ROOT), emptyList()).map { it.delivery }
            }
        assertEquals(listOf(FollowUpDelivery.Queue, FollowUpDelivery.Queue), counts[0])
        assertEquals(3, counts[1].size)
        assertTrue(FollowUpDelivery.Steer in counts[1])
        assertEquals(2, counts[2].size)
        assertEquals(1, counts[scenario.steps.indexOfFirst { it.label == "First queued prompt runs" } + 1].size)
        assertTrue(counts.last().isEmpty())
        val final = scenario.finalState(NOW)
        assertFalse(final.isBusy(ROOT))
        assertTrue(final.messages(ROOT).filter { it.role == MessageRole.Assistant }.all { it.completedAtEpochMs != null })
    }

    @Test
    fun subagentsScriptAsksThenFinishes() {
        val scenario = PlaygroundScenarios.build(PlaygroundScenarioId.Subagents, NOW)
        assertEquals("2 subagents", pill(scenario.initial)?.label)

        val asking = scenario.steps.first().apply(scenario.initial, NOW)
        assertEquals("explore needs input · 1 running", pill(asking)?.label)

        val final = scenario.finalState(NOW)
        assertNull(pill(final))
        assertTrue(final.permissions.isEmpty())
        assertTrue(final.activity.isEmpty())
    }

    @Test
    fun abortAndRetryInterruptsThenRetriesThenFinishes() {
        val scenario = PlaygroundScenarios.build(PlaygroundScenarioId.AbortAndRetry, NOW)
        val states = scenario.steps.runningFold(scenario.initial) { state, step -> step.apply(state, NOW) }
        val stopped = states[scenario.steps.indexOfFirst { it.label == "Stop interrupts it" } + 1]
        assertFalse(stopped.isBusy(ROOT))
        assertEquals(MessageStopReason.Interrupted, stopped.messages(ROOT).last().stopReason)
        val retrying = states[scenario.steps.indexOfFirst { it.label == "Next prompt waits to retry" } + 1]
        assertTrue(retrying.activity(ROOT) is SessionActivity.Retrying)
        val final = states.last()
        assertFalse(final.isBusy(ROOT))
        assertNotNull(final.messages(ROOT).last().completedAtEpochMs)
        assertNull(final.messages(ROOT).last().stopReason)
    }

    @Test
    fun jumpToLatestScriptAddsThreeMessagesBelow() {
        val scenario = PlaygroundScenarios.build(PlaygroundScenarioId.JumpToLatest, NOW)
        assertNotNull(scenario.hint)
        val before = scenario.initial.messages(scenario.boundSessionId)
        val after = scenario.finalState(NOW).messages(scenario.boundSessionId)
        assertEquals(before.size + 3, after.size)
        assertEquals(listOf(MessageRole.User, MessageRole.Assistant, MessageRole.User), after.takeLast(3).map { it.role })
        assertNotNull(after[after.size - 2].completedAtEpochMs)
        assertTrue(scenario.finalState(NOW).isBusy(scenario.boundSessionId))
    }

    @Test
    fun fullLifecycleGoesFromSendToDone() {
        val scenario = PlaygroundScenarios.build(PlaygroundScenarioId.FullLifecycle, NOW)
        assertFalse(scenario.initial.isBusy(ROOT))
        val states = scenario.steps.runningFold(scenario.initial) { state, step -> step.apply(state, NOW) }

        fun after(label: String) = states[scenario.steps.indexOfFirst { it.label == label } + 1]
        assertEquals(1, pendingPromptsFrom(after("A prompt is queued").messages(ROOT), emptyList()).size)
        assertEquals("2 subagents", pill(after("Subagents start"))?.label)
        assertTrue(pill(after("explore needs input"))!!.needsInput)
        assertNull(pill(after("Subagents finish")))
        assertTrue(after("Subagents finish").isBusy(ROOT))
        assertTrue(pendingPromptsFrom(after("Queued prompt runs").messages(ROOT), emptyList()).isEmpty())
        val final = states.last()
        assertFalse(final.isBusy(ROOT))
        assertTrue(final.permissions.isEmpty())
        assertTrue(final.messages(ROOT).filter { it.role == MessageRole.Assistant }.all { it.completedAtEpochMs != null })
    }

    @Test
    fun stillScenariosSetUpTheirMoment() {
        fun build(id: PlaygroundScenarioId) = PlaygroundScenarios.build(id, NOW)

        val idle = build(PlaygroundScenarioId.IdleOnBranch).initial
        assertFalse(idle.isBusy(idle.rootSessionId))
        assertEquals(128, idle.changedFiles.sumOf { it.additions })
        assertEquals(14, idle.changedFiles.sumOf { it.deletions })
        assertEquals(3, idle.todos.count { it.status == SessionTodoStatus.Completed })
        assertEquals(5, idle.todos.size)

        val clean = build(PlaygroundScenarioId.CleanWorktree)
        assertEquals(PlaygroundContent.WORKTREE_CHECKOUT, clean.boundSession.directory)
        assertTrue(clean.initial.changedFiles.isEmpty())
        assertNotNull(clean.initial.branch)

        val queued = build(PlaygroundScenarioId.RunningQueued).initial
        assertTrue(queued.isBusy(queued.rootSessionId))
        assertEquals(2, pendingPromptsFrom(queued.messages(queued.rootSessionId), emptyList()).size)

        assertEquals("2 subagents", pill(build(PlaygroundScenarioId.BackgroundWork).initial)?.label)

        val needsInput = build(PlaygroundScenarioId.NeedsInput)
        assertTrue(needsInput.collapsesPermission)
        assertTrue(pill(needsInput.initial)!!.needsInput)
        assertEquals(1, needsInput.initial.questions.size)

        val side = build(PlaygroundScenarioId.SideQuestionReady)
        assertEquals(1, side.readySideQuestions.size)
        assertTrue(side.initial.isBusy(side.boundSessionId))

        val failed = build(PlaygroundScenarioId.RequestFailed).initial
        assertNotNull(failed.messages(failed.rootSessionId).last().failure)

        val busy = build(PlaygroundScenarioId.BusyCorner)
        assertTrue(busy.collapsesPermission)
        assertEquals(1, busy.readySideQuestions.size)
        assertEquals(1, busy.runningSideQuestions.size)
        assertEquals(2, pendingPromptsFrom(busy.initial.messages(busy.boundSessionId), emptyList()).size)
        assertNotNull(pill(busy.initial))

        val keyboard = build(PlaygroundScenarioId.KeyboardUp).initial
        assertFalse(pill(keyboard)!!.needsInput)
        assertNotNull(keyboard.branch)

        val child = build(PlaygroundScenarioId.ChildSession)
        assertNotNull(child.boundSession.parentId)
    }

    @Test
    fun nextFinishesAStreamingStepThenRunsTheNextAtOnce() =
        runTest(dispatcher) {
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val session = PlaygroundSession(streamingScenario(), PlainPreparing, scope, nowMs = { dispatcher.scheduler.currentTime })
            runCurrent()
            // Two frames of the three-frame step have streamed.
            advanceTimeBy(1_100)
            runCurrent()
            assertEquals("one two", session.world.state.value.branch)
            assertEquals(1, session.player.progress.value.shownSteps)
            assertEquals("stream", session.player.progress.value.label)

            session.player.next()
            assertEquals("one two three", session.world.state.value.branch)
            assertTrue(session.player.progress.value.isPlaying)

            session.player.pause()
            session.player.next()
            assertEquals("done", session.world.state.value.branch)
            assertEquals(2, session.player.progress.value.shownSteps)
            assertTrue(session.player.progress.value.isFinished)
            assertFalse(session.player.progress.value.isPlaying)
            session.close()
            scope.cancel()
        }

    @Test
    fun fastPlayWaitsAQuarterAndScalesAWaitInProgress() =
        runTest(dispatcher) {
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val session = PlaygroundSession(twoStepScenario(), PlainPreparing, scope, nowMs = { dispatcher.scheduler.currentTime })
            runCurrent()
            session.player.setFast(true)
            assertTrue(session.player.progress.value.isFast)
            // 50 ms at 4× is 12 ms.
            advanceTimeBy(13)
            runCurrent()
            assertEquals("first", session.world.state.value.branch)
            // 200 ms at 4× is 50 ms; 26 ms in, back to 1× makes the 24 ms left 96.
            advanceTimeBy(25)
            session.player.setFast(false)
            advanceTimeBy(95)
            runCurrent()
            assertEquals("first", session.world.state.value.branch)
            advanceTimeBy(2)
            runCurrent()
            assertEquals("second", session.world.state.value.branch)
            session.close()
            scope.cancel()
        }

    @Test
    fun realScreenModelStreamsTheReplyIncrementally() =
        runTest(dispatcher) {
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val scenario = PlaygroundScenarios.build(PlaygroundScenarioId.StreamingTurn, NOW)
            val session = PlaygroundSession(scenario, PlainPreparing, scope, nowMs = { NOW + dispatcher.scheduler.currentTime })
            val viewModel = session.chatViewModel
            viewModel.ioDispatcher = dispatcher
            viewModel.nowMs = { NOW + dispatcher.scheduler.currentTime }
            runCurrent()
            viewModel.bind(scenario.boundSessionId, scenario.boundSession.title, PlaygroundContent.WORKTREE)

            val seen = mutableListOf<String>()
            while (!session.player.progress.value.isFinished) {
                advanceTimeBy(45)
                runCurrent()
                val reply =
                    viewModel.uiState.value.messages
                        .firstOrNull { it.id == "pg-l1-a" }
                        ?.text
                if (reply != null && reply.startsWith("Done.") && reply != seen.lastOrNull()) seen += reply
            }
            assertTrue("the reply showed in several frames: $seen", seen.size >= 5)
            seen.zipWithNext().forEach { (before, after) -> assertTrue(after.startsWith(before)) }
            assertEquals(
                scenario
                    .finalState(NOW)
                    .messages(ROOT)
                    .last()
                    .text,
                seen.last(),
            )

            viewModel.ioDispatcher = Dispatchers.Unconfined
            session.close()
            scope.cancel()
        }

    @Test
    fun realScreenModelFollowsTheSubagentsScript() =
        runTest(dispatcher) {
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val scenario = PlaygroundScenarios.build(PlaygroundScenarioId.Subagents, NOW)
            val session = PlaygroundSession(scenario, PlainPreparing, scope, nowMs = { NOW + dispatcher.scheduler.currentTime })
            val viewModel = session.chatViewModel
            viewModel.ioDispatcher = dispatcher
            viewModel.nowMs = { NOW + dispatcher.scheduler.currentTime }
            runCurrent()
            assertTrue(session.ready.value)
            viewModel.bind(scenario.boundSessionId, scenario.boundSession.title, PlaygroundContent.WORKTREE)

            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(
                "2 subagents",
                viewModel.uiState.value.backgroundWorkPill
                    ?.label,
            )

            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(
                "explore needs input · 1 running",
                viewModel.uiState.value.backgroundWorkPill
                    ?.label,
            )
            assertNotNull(viewModel.uiState.value.permissionRequest)

            advanceTimeBy(9_000)
            runCurrent()
            assertTrue(session.player.progress.value.isFinished)
            assertNull(viewModel.uiState.value.backgroundWorkPill)
            assertNull(viewModel.uiState.value.permissionRequest)

            // onCleared flushes the draft with runBlocking on this dispatcher; it would wait on itself.
            viewModel.ioDispatcher = Dispatchers.Unconfined
            session.close()
            scope.cancel()
        }

    @Test
    fun leavingMidScriptStopsTheScript() =
        runTest(dispatcher) {
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val session = PlaygroundSession(twoStepScenario(), PlainPreparing, scope, nowMs = { dispatcher.scheduler.currentTime })
            runCurrent()
            assertTrue(session.player.progress.value.isPlaying)
            advanceTimeBy(60)
            runCurrent()
            assertEquals("first", session.world.state.value.branch)

            session.close()
            val afterClose = session.world.state.value
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(afterClose, session.world.state.value)
            assertFalse(session.player.progress.value.isPlaying)
            scope.cancel()
        }

    @Test
    fun pauseThenPlayKeepsTheRemainingWait() =
        runTest(dispatcher) {
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val session = PlaygroundSession(twoStepScenario(), PlainPreparing, scope, nowMs = { dispatcher.scheduler.currentTime })
            runCurrent()
            advanceTimeBy(60)
            runCurrent()
            // The second step's 200 ms wait began at 50 ms; pause 150 ms into it.
            advanceTimeBy(140)
            session.player.pause()
            assertEquals("first", session.world.state.value.branch)

            session.player.play()
            advanceTimeBy(49)
            runCurrent()
            assertEquals("first", session.world.state.value.branch)
            advanceTimeBy(2)
            runCurrent()
            assertEquals("second", session.world.state.value.branch)
            assertTrue(session.player.progress.value.isFinished)
            session.close()
            scope.cancel()
        }

    @Test
    fun restartMidScriptStartsAFreshWorldWithoutStackingIsolation() =
        runTest(dispatcher) {
            val before = ProductAnalytics.suppressionCount
            val store = ViewModelStore()
            val viewModel =
                ViewModelProvider.create(
                    store,
                    viewModelFactory {
                        initializer {
                            SessionChatPlaygroundViewModel(
                                PlainPreparing,
                            ) { NOW + dispatcher.scheduler.currentTime }
                        }
                    },
                )[SessionChatPlaygroundViewModel::class]
            runCurrent()
            assertEquals(before + 1, ProductAnalytics.suppressionCount)
            assertTrue(SessionChatDisplayPreferences.isSandboxed)
            viewModel.select(PlaygroundScenarioId.StreamingTurn)
            runCurrent()
            val first = viewModel.session.value.session
            advanceTimeBy(900)
            runCurrent()
            assertEquals(1, first.player.progress.value.shownSteps)
            first.player.setFast(true)

            viewModel.restart()
            runCurrent()
            val second = viewModel.session.value.session
            assertTrue(second !== first)
            assertEquals(PlaygroundScenarioId.StreamingTurn, second.scenario.id)
            assertEquals(0, second.player.progress.value.shownSteps)
            assertTrue("restart keeps the speed", second.player.progress.value.isFast)
            assertFalse(first.player.progress.value.isPlaying)
            val firstAfterRestart = first.world.state.value
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(firstAfterRestart, first.world.state.value)

            viewModel.selectNext()
            assertEquals(PlaygroundScenarioId.FollowUps, viewModel.session.value.session.scenario.id)
            viewModel.selectPrevious()
            viewModel.selectPrevious()
            assertEquals(PlaygroundScenarioId.EmptySession, viewModel.session.value.session.scenario.id)
            viewModel.select(PlaygroundScenarioId.ChildSession)
            viewModel.selectNext()
            assertEquals(PlaygroundScenarioId.LongConversation, viewModel.session.value.session.scenario.id)
            assertEquals(10, viewModel.stepCounts[PlaygroundScenarioId.StreamingTurn])
            assertEquals(0, viewModel.stepCounts[PlaygroundScenarioId.LongConversation])
            assertEquals(before + 1, ProductAnalytics.suppressionCount)

            store.clear()
            assertEquals(before, ProductAnalytics.suppressionCount)
            assertFalse(SessionChatDisplayPreferences.isSandboxed)
        }

    @Test
    fun playgroundLeavesRealPreferencesUntouched() {
        // An updated install that has not answered the Timeline Style picker yet.
        val stored = InMemoryPreferences()
        stored.values["timeline_style"] = "standard"
        stored.values["timeline_style_picker_done"] = false
        SessionChatDisplayPreferences.initialize(stored)
        val before = stored.values.toMap()
        assertTrue(SessionChatDisplayPreferences.timelineStylePickerPending)
        try {
            val isolation = PlaygroundIsolation.begin()
            // The one-time offers stay for the real app.
            assertFalse(SessionChatDisplayPreferences.timelineStylePickerPending)
            assertFalse(SessionChatDisplayPreferences.shouldOfferChipTimelineIntro(20, NOW))
            // What Session Chat writes when the user answers them or switches delivery.
            SessionChatDisplayPreferences.dismissTimelineStylePicker()
            SessionChatDisplayPreferences.updateTimelineStyle(SessionChatTimelineStyle.Chips)
            SessionChatDisplayPreferences.postponeChipTimelineIntro(NOW)
            SessionChatDisplayPreferences.finishChipTimelineIntro()
            SessionChatDisplayPreferences.updateFollowUpDelivery(FollowUpDelivery.Queue)
            SessionChatDisplayPreferences.updateCollapseConsecutiveToolCalls(false)
            assertEquals(SessionChatTimelineStyle.Chips, SessionChatDisplayPreferences.timelineStylePreference)
            isolation.close()
            isolation.close()

            assertEquals(before, stored.values.toMap())
            assertTrue(SessionChatDisplayPreferences.timelineStylePickerPending)
            assertEquals(SessionChatTimelineStyle.Standard, SessionChatDisplayPreferences.timelineStylePreference)
            assertEquals(FollowUpDelivery.Steer, SessionChatDisplayPreferences.followUpDelivery)
            assertTrue(SessionChatDisplayPreferences.collapseConsecutiveToolCalls)
            assertFalse(SessionChatDisplayPreferences.chipTimelineIntroDone)
            assertFalse(SessionChatDisplayPreferences.isSandboxed)
        } finally {
            SessionChatDisplayPreferences.initialize(InMemoryPreferences())
            SessionChatDisplayPreferences.clear()
        }
    }

    private fun streamingScenario() =
        PlaygroundScenarios.build(PlaygroundScenarioId.Subagents, NOW).copy(
            steps =
                listOf(
                    PlaygroundStep(
                        "stream",
                        listOf("one", "one two", "one two three").mapIndexed { index, text ->
                            PlaygroundFrame(if (index == 0) 1_000 else 80) { state, _ -> state.copy(branch = text) }
                        },
                    ),
                    PlaygroundStep(5_000, "done") { state, _ -> state.copy(branch = "done") },
                ),
        )

    private fun twoStepScenario() =
        PlaygroundScenarios.build(PlaygroundScenarioId.Subagents, NOW).copy(
            steps =
                listOf(
                    PlaygroundStep(50, "first") { state, _ -> state.copy(branch = "first") },
                    PlaygroundStep(200, "second") { state, _ -> state.copy(branch = "second") },
                ),
        )

    private fun pill(state: PlaygroundState) =
        SessionChatBackgroundWork.pill(
            SessionChatBackgroundWork.subagents(
                messages = state.messages(state.rootSessionId),
                sessions = state.sessions,
                busySessionIds = state.activity.keys,
                requestSessionIds = state.permissions.map { it.sessionId } + state.questions.map { it.sessionId },
            ),
        )

    private fun assertConsistent(
        id: PlaygroundScenarioId,
        state: PlaygroundState,
    ) {
        val sessionIds = state.sessions.map { it.id }.toSet()
        assertTrue("$id has messages only for its sessions", state.messages.keys.all { it in sessionIds })
        assertTrue("$id is busy only in its sessions", state.activity.keys.all { it in sessionIds })
        assertTrue(
            "$id asks only from its sessions",
            (state.permissions.map { it.sessionId } + state.questions.map { it.sessionId }).all {
                it in
                    sessionIds
            },
        )
        for ((sessionId, messages) in state.messages) {
            assertEquals("$id/$sessionId message ids are unique", messages.size, messages.map(Message::id).toSet().size)
            val partIds = messages.flatMap { it.parts }.mapNotNull { (it as? MessagePart.Tool)?.id }
            assertEquals("$id/$sessionId tool ids are unique", partIds.size, partIds.toSet().size)
        }
    }

    private object PlainPreparing : DisplayMessagePreparing {
        override suspend fun prepareMessages(
            messages: List<Message>,
            previousById: Map<String, DisplayMessage>,
            mode: MessagePreparationMode,
        ): List<DisplayMessage> = messages.map(::DisplayMessage)

        override suspend fun prepareMessage(
            message: Message,
            previous: DisplayMessage?,
            mode: MessagePreparationMode,
        ): DisplayMessage = DisplayMessage(message)

        override suspend fun prepareStructureOnlyTextBlock(
            blockID: String,
            plain: String,
            sessionKey: String,
        ): MarkdownDocument? = null

        override suspend fun highlightStreamingTextBlock(
            blockID: String,
            plain: String,
            sessionKey: String,
        ): StreamingHighlight? = null
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val ROOT = PlaygroundContent.ROOT_SESSION_ID
    }
}
