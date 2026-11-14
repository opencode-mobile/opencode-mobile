package dev.opencode.hexcode.home

import androidx.lifecycle.viewModelScope
import dev.opencode.hexcode.firstrun.ModelChoice
import dev.opencode.hexcode.session.AskReply
import dev.opencode.hexcode.session.DailySession
import dev.opencode.hexcode.session.DailySessionApi
import dev.opencode.hexcode.session.DailySessionMemory
import dev.opencode.hexcode.session.DailySessionSetup
import dev.opencode.hexcode.session.EventTransport
import dev.opencode.hexcode.session.Goal
import dev.opencode.hexcode.session.GoalAction
import dev.opencode.hexcode.session.GoalChangedException
import dev.opencode.hexcode.session.GoalStatus
import dev.opencode.hexcode.session.LiveSessionApi
import dev.opencode.hexcode.session.LiveSnapshot
import dev.opencode.hexcode.session.PromptImage
import dev.opencode.hexcode.session.SessionListing
import dev.opencode.hexcode.session.SessionListingPage
import dev.opencode.hexcode.session.TodaySession
import dev.opencode.mobile.data.remote.SseAttemptOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/** The goal sheet, the draft's ⋯ and Ask through home's view model (app-plan.md, Goal and Ask). */
@OptIn(ExperimentalCoroutinesApi::class)
class GoalAskViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val today = LocalDate.of(2026, 11, 7)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Dictation(
        vararg texts: String,
    ) : dev.opencode.hexcode.home.Dictation {
        val texts = ArrayDeque(texts.toList())
        var recording = false

        override val level = 0f
        override val speech = MutableStateFlow<SpeechStatus>(SpeechStatus.Ready)

        override fun start(onFailed: (Throwable) -> Unit) {
            recording = true
        }

        override suspend fun end(): FloatArray? = if (recording) FloatArray(16_000).also { recording = false } else null

        override fun drop() {
            recording = false
        }

        override suspend fun transcribe(samples: FloatArray): String = texts.removeFirst()

        override fun reload() = Unit
    }

    private inner class Server :
        DailySessionApi,
        LiveSessionApi,
        DailySessionMemory {
        var busy = false
        var goal: Goal? = null
        val calls = mutableListOf<String>()
        var fails: Exception? = null

        override suspend fun sessionsPage(
            directory: String,
            search: String,
            limit: Int,
            cursor: String?,
        ) = SessionListingPage(listOf(SessionListing("ses_1", DailySession.title(today), "/p", 1, busySince = 1)), null)

        override suspend fun create(
            title: String,
            directory: String,
            model: ModelChoice?,
        ) = SessionListing("ses_1", title, directory, 1)

        override suspend fun putMessageRules(sessionId: String) = Unit

        override suspend fun snapshot(rootId: String) =
            LiveSnapshot(if (busy) 5 else null, hasRun = true, children = emptyList(), pending = emptyList(), inbox = emptyList())

        override suspend fun summarise(
            sessionId: String,
            prompt: String,
        ) = "## Task\nNow: Working."

        override suspend fun ask(
            sessionId: String,
            prompt: String,
        ): String {
            calls += "ask ${prompt.substringAfter("Question: ")}"
            return "Two tests fail."
        }

        override suspend fun send(
            sessionId: String,
            text: String,
            delivery: String,
            images: List<PromptImage>,
            metadata: JsonObject?,
        ) = "msg_1"

        override suspend fun cancelQueued(
            sessionId: String,
            itemId: String,
        ) = Unit

        override suspend fun switchModel(
            sessionId: String,
            model: ModelChoice,
        ) = Unit

        override suspend fun lastOpenedOn() = today

        override suspend fun setLastOpenedOn(date: LocalDate) = Unit

        override suspend fun goal(
            sessionId: String,
            directory: String,
        ) = goal

        override suspend fun goalCommand(
            sessionId: String,
            text: String,
            images: List<PromptImage>,
        ) {
            calls += "goal $text"
            fails?.let { throw it }
        }
    }

    private class Stream : EventTransport {
        val lines = Channel<String?>(Channel.UNLIMITED)

        override suspend fun connect(
            onActivity: () -> Unit,
            onLine: suspend (String) -> Unit,
        ): SseAttemptOutcome {
            while (true) {
                val line = lines.receive() ?: return SseAttemptOutcome.Ended
                onActivity()
                onLine(line)
            }
        }
    }

    private val server = Server()
    private val stream = Stream()

    private fun TestScope.home(vararg texts: String): HomeViewModel = home(Dictation(*texts))

    private fun TestScope.home(dictation: Dictation): HomeViewModel {
        val vm =
            HomeViewModel(dictation, makeSession = { scope ->
                TodaySession(
                    scope,
                    DailySessionSetup(server, server),
                    server,
                    stream,
                    { "/p" },
                    { null },
                    { today },
                    { testScheduler.currentTime },
                )
            }) { testScheduler.currentTime }
        runCurrent()
        stream.lines.trySend("""{"type":"server.connected","data":{}}""")
        runCurrent()
        return vm
    }

    private fun TestScope.done(vm: HomeViewModel) {
        vm.viewModelScope.cancel()
        runCurrent()
    }

    @Test
    fun theMenusItemsWaitForEveryChunk() =
        runTest {
            val dictation = Dictation("Fix the login")
            dictation.speech.value = SpeechStatus.Loading()
            val vm = home(dictation)
            try {
                vm.press()
                advanceTimeBy(1_000)
                vm.release(TalkRelease.End)
                runCurrent()
                // The take waits for the speech model: neither item does anything yet.
                assertFalse(vm.uiState.value.draftReady)
                vm.goalFromDraft()
                vm.askFromDraft()
                runCurrent()
                assertEquals(emptyList<String>(), server.calls)
                dictation.speech.value = SpeechStatus.Ready
                runCurrent()
                assertTrue(vm.uiState.value.draftReady)
            } finally {
                done(vm)
            }
        }

    @Test
    fun setAsGoalSendsTheDraftClearsItAndSaysSo() =
        runTest {
            val vm = home()
            vm.addToDraft("Fix the login")
            vm.addToDraft("and the logout")
            vm.toggleDraftMenu()
            assertTrue(vm.uiState.value.draftMenu)
            vm.goalFromDraft()
            assertTrue(vm.uiState.value.draft.isEmpty)
            assertFalse(vm.uiState.value.draftMenu)
            runCurrent()
            assertEquals(listOf("goal Fix the login and the logout"), server.calls)
            assertEquals(HomeNotice.GoalSetIdle, vm.uiState.value.notice)
            done(vm)
        }

    @Test
    fun aSingleCommandWordIsRefusedAndTheDraftKept() =
        runTest {
            val vm = home()
            vm.addToDraft("Pause.")
            vm.goalFromDraft()
            runCurrent()
            assertEquals(emptyList<String>(), server.calls)
            assertEquals(HomeNotice.GoalRefusedWord, vm.uiState.value.notice)
            assertEquals("Pause.", vm.uiState.value.draft.text)
            vm.clearDraft()
            vm.addToDraft("x".repeat(4_001))
            vm.goalFromDraft()
            runCurrent()
            assertEquals(HomeNotice.GoalRefusedLong, vm.uiState.value.notice)
            assertFalse(vm.uiState.value.draft.isEmpty)
            done(vm)
        }

    @Test
    fun aFailedGoalBringsTheDraftBack() =
        runTest {
            server.fails = IllegalStateException("HTTP 502")
            val vm = home()
            vm.addToDraft("Fix the login")
            vm.goalFromDraft()
            runCurrent()
            assertEquals("Fix the login", vm.uiState.value.draft.text)
            assertEquals(HomeNotice.GoalFailed, vm.uiState.value.notice)
            done(vm)
        }

    @Test
    fun askFromTheDraftShowsTheAnswerUnderTheCard() =
        runTest {
            val vm = home()
            vm.addToDraft("Why did the tests fail?")
            vm.askFromDraft()
            assertTrue(vm.uiState.value.draft.isEmpty)
            runCurrent()
            assertEquals(listOf("ask Why did the tests fail?"), server.calls)
            assertEquals(
                AskReply.Answered("Two tests fail."),
                vm.uiState.value.today.ask
                    ?.reply,
            )
            vm.openAsk()
            runCurrent()
            assertTrue(
                vm.uiState.value.today.ask!!
                    .open,
            )
            vm.closeAsk()
            runCurrent()
            assertNull(vm.uiState.value.today.ask)
            assertEquals(1, vm.uiState.value.today.asked.size)
            // Opening it from Details closes Details and shows it again.
            vm.openDetails()
            vm.showAsked(0)
            runCurrent()
            assertFalse(vm.uiState.value.detailsOpen)
            assertEquals(
                "Why did the tests fail?",
                vm.uiState.value.today.ask
                    ?.question,
            )
            done(vm)
        }

    @Test
    fun theGoalIsSaidIntoTheBoxThenSet() =
        runTest {
            val vm = home("Ship the login screen")
            vm.openGoal()
            assertTrue(vm.goalTakeStart())
            assertEquals(
                GoalTake.Listening,
                vm.uiState.value.goalSheet
                    ?.edit
                    ?.take,
            )
            advanceTimeBy(1_000)
            vm.sheetTakeEnd()
            runCurrent()
            assertEquals(
                GoalEdit("Ship the login screen"),
                vm.uiState.value.goalSheet
                    ?.edit,
            )
            vm.setGoal()
            assertEquals(
                GoalAction.Change,
                vm.uiState.value.goalSheet
                    ?.busy,
            )
            runCurrent()
            assertEquals(listOf("goal Ship the login screen"), server.calls)
            assertNull(vm.uiState.value.goalSheet)
            assertEquals(HomeNotice.GoalSetIdle, vm.uiState.value.notice)
            done(vm)
        }

    @Test
    fun aQuickTapRecordsNothingAndCancelShowsTheGoalAgain() =
        runTest {
            val vm = home("Ship it")
            vm.openGoal()
            vm.goalTakeStart()
            vm.sheetTakeEnd()
            runCurrent()
            assertNull(
                vm.uiState.value.goalSheet
                    ?.edit,
            )
            vm.goalTakeStart()
            advanceTimeBy(1_000)
            vm.sheetTakeEnd()
            runCurrent()
            assertNotNull(
                vm.uiState.value.goalSheet
                    ?.edit,
            )
            vm.cancelGoalEdit()
            assertNull(
                vm.uiState.value.goalSheet
                    ?.edit,
            )
            done(vm)
        }

    @Test
    fun pauseKeepsTheSheetOpenAndClearClosesIt() =
        runTest {
            server.goal = Goal("g1", "Ship it", GoalStatus.Active)
            server.busy = true
            val vm = home()
            vm.openGoal()
            vm.pauseGoal()
            runCurrent()
            assertNotNull(vm.uiState.value.goalSheet)
            assertEquals(HomeNotice.GoalPaused, vm.uiState.value.notice)
            vm.clearGoal()
            runCurrent()
            assertNull(vm.uiState.value.goalSheet)
            assertEquals(HomeNotice.GoalClearedBusy, vm.uiState.value.notice)
            assertEquals(listOf("goal pause", "goal clear"), server.calls)
            done(vm)
        }

    @Test
    fun aStaleChangeSaysTheGoalHadAlreadyChangedAndKeepsTheText() =
        runTest {
            server.goal = Goal("g1", "Ship it", GoalStatus.Complete, "Verified")
            val vm = home("Something new")
            vm.openGoal()
            vm.goalTakeStart()
            advanceTimeBy(1_000)
            vm.sheetTakeEnd()
            runCurrent()
            server.fails = GoalChangedException("No paused goal to resume.")
            vm.setGoal()
            runCurrent()
            assertEquals(HomeNotice.GoalStale, vm.uiState.value.notice)
            assertEquals(
                "Something new",
                vm.uiState.value.goalSheet
                    ?.edit
                    ?.text,
            )
            done(vm)
        }
}
