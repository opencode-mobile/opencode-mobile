package dev.opencode.mobile.ui.playground

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.domain.message.DisplayMessage
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.preferences.InMemoryPreferences
import dev.opencode.mobile.domain.preferences.SessionChatDisplayPreferences
import dev.opencode.mobile.domain.preferences.SessionChatTimelineStyle
import dev.opencode.mobile.domain.message.DisplayMessagePreparing
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessagePreparationMode
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.StreamingHighlight
import dev.opencode.mobile.domain.message.ToolStatus
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
            assertConsistent(id, scenario.finalState(NOW))
        }
    }

    @Test
    fun onlyEverythingSubagentsAndScrolledUpAreScripted() {
        val scripted = PlaygroundScenarioId.entries.filter { PlaygroundScenarios.build(it, NOW).isScripted }
        assertEquals(
            listOf(PlaygroundScenarioId.Everything, PlaygroundScenarioId.Subagents, PlaygroundScenarioId.ScrolledUp),
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
            scenario.initial.sessions.forEach { session ->
                assertEquals("$id session ${session.id} lives in the fake project", PlaygroundContent.WORKTREE, session.directory)
                assertEquals(PlaygroundContent.PROJECT_ID, session.projectId)
            }
            val paths = scenario.initial.changedFiles.map { it.path } + scenario.initial.turnDiff.map { it.path }
            assertTrue("$id changes only fake project files", paths.all { it.startsWith("src/") })
            scenario.initial.branch?.let { assertEquals(PlaygroundContent.BRANCH, it) }
        }
        assertTrue(PlaygroundContent.WORKTREE.startsWith("/Users/example/acme-"))
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
    fun everythingCoversEveryToolStateAndEndsIdle() {
        val scenario = PlaygroundScenarios.build(PlaygroundScenarioId.Everything, NOW)
        val tools =
            scenario.initial
                .messages(scenario.boundSessionId)
                .flatMap { it.parts }
                .filterIsInstance<MessagePart.Tool>()
        assertEquals(ToolStatus.entries.toSet(), tools.map { it.status }.toSet())
        assertTrue(scenario.initial.isBusy(scenario.boundSessionId))
        assertEquals(1, pendingPromptsFrom(scenario.initial.messages(scenario.boundSessionId), emptyList()).size)

        val final = scenario.finalState(NOW)
        assertFalse(final.isBusy(scenario.boundSessionId))
        assertTrue(pendingPromptsFrom(final.messages(scenario.boundSessionId), emptyList()).isEmpty())
        val last = final.messages(scenario.boundSessionId).last { it.role == MessageRole.Assistant }
        assertNotNull(last.completedAtEpochMs)
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
    fun scrolledUpScriptAddsThreeMessagesBelow() {
        val scenario = PlaygroundScenarios.build(PlaygroundScenarioId.ScrolledUp, NOW)
        val before = scenario.initial.messages(scenario.boundSessionId)
        val after = scenario.finalState(NOW).messages(scenario.boundSessionId)
        assertEquals(before.size + 3, after.size)
        assertEquals(listOf(MessageRole.User, MessageRole.Assistant, MessageRole.User), after.takeLast(3).map { it.role })
        assertNotNull(after[after.size - 2].completedAtEpochMs)
        assertTrue(scenario.finalState(NOW).isBusy(scenario.boundSessionId))
    }

    @Test
    fun stillScenariosSetUpTheirMoment() {
        fun build(id: PlaygroundScenarioId) = PlaygroundScenarios.build(id, NOW)

        val quiet = build(PlaygroundScenarioId.Quiet).initial
        assertFalse(quiet.isBusy(quiet.rootSessionId))
        assertNotNull(quiet.branch)
        assertTrue(quiet.changedFiles.isNotEmpty() && quiet.todos.isNotEmpty())

        val working = build(PlaygroundScenarioId.Working).initial
        assertTrue(working.isBusy(working.rootSessionId))
        assertEquals(2, pendingPromptsFrom(working.messages(working.rootSessionId), emptyList()).size)

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
                    viewModelFactory { initializer { SessionChatPlaygroundViewModel(PlainPreparing) { NOW + dispatcher.scheduler.currentTime } } },
                )[SessionChatPlaygroundViewModel::class]
            runCurrent()
            assertEquals(before + 1, ProductAnalytics.suppressionCount)
            assertTrue(SessionChatDisplayPreferences.isSandboxed)
            val first = viewModel.session.value.session
            advanceTimeBy(3_500)
            runCurrent()
            assertEquals(1, first.player.progress.value.nextStep)

            viewModel.restart()
            runCurrent()
            val second = viewModel.session.value.session
            assertTrue(second !== first)
            assertEquals(PlaygroundScenarioId.Everything, second.scenario.id)
            assertEquals(0, second.player.progress.value.nextStep)
            assertFalse(first.player.progress.value.isPlaying)
            val firstAfterRestart = first.world.state.value
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(firstAfterRestart, first.world.state.value)

            viewModel.select(PlaygroundScenarioId.Quiet)
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
    }
}
