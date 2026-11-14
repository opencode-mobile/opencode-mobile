package dev.opencode.hexcode.home

import androidx.lifecycle.viewModelScope
import dev.opencode.hexcode.firstrun.ModelChoice
import dev.opencode.hexcode.session.ApprovalDecision
import dev.opencode.hexcode.session.ApprovalRequest
import dev.opencode.hexcode.session.DailySession
import dev.opencode.hexcode.session.DailySessionApi
import dev.opencode.hexcode.session.DailySessionMemory
import dev.opencode.hexcode.session.DailySessionSetup
import dev.opencode.hexcode.session.EventTransport
import dev.opencode.hexcode.session.LiveSessionApi
import dev.opencode.hexcode.session.LiveSnapshot
import dev.opencode.hexcode.session.PendingItem
import dev.opencode.hexcode.session.PromptImage
import dev.opencode.hexcode.session.QuestionField
import dev.opencode.hexcode.session.QuestionForm
import dev.opencode.hexcode.session.QuestionOption
import dev.opencode.hexcode.session.RequestGoneException
import dev.opencode.hexcode.session.SessionListing
import dev.opencode.hexcode.session.SessionListingPage
import dev.opencode.hexcode.session.SessionPrompts
import dev.opencode.hexcode.session.TodaySession
import dev.opencode.mobile.data.remote.SseAttemptOutcome
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * The approval and question sheets, their takes and Stop through home's view model (app-plan.md, Needs you and control
 * › Testing): two pending items open one after another, a 404 closes the sheet, Deny sends what was said, and Stop's
 * hold released at 0.2 s, 1.5 s and 3 s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NeedsYouViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val today = LocalDate.of(2026, 11, 6)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Each take's text is the next of [texts]; a take shorter than 0.3 s has no audio. */
    private class Dictation(
        vararg texts: String,
    ) : dev.opencode.hexcode.home.Dictation {
        val texts = ArrayDeque(texts.toList())
        var gate: CompletableDeferred<Unit>? = null
        var recording = false
        var short = false

        override val level = 0f
        override val speech = MutableStateFlow<SpeechStatus>(SpeechStatus.Ready)

        override fun start(onFailed: (Throwable) -> Unit) {
            recording = true
        }

        override suspend fun end(): FloatArray? =
            if (recording) FloatArray(if (short) 1_000 else 16_000).also { recording = false } else null

        override fun drop() {
            recording = false
        }

        override suspend fun transcribe(samples: FloatArray): String {
            gate?.await()
            return texts.removeFirst()
        }

        override fun reload() = Unit
    }

    private inner class Server :
        DailySessionApi,
        LiveSessionApi,
        DailySessionMemory {
        var busy = true
        var pending = listOf<PendingItem>()
        val calls = mutableListOf<String>()
        var gone = false
        var fails = false
        var gate: CompletableDeferred<Unit>? = null

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
            LiveSnapshot(
                if (busy) 5 else null,
                hasRun = true,
                children = emptyList(),
                pending = pending,
                inbox = emptyList(),
                projectId = "proj",
            )

        override suspend fun summarise(
            sessionId: String,
            prompt: String,
        ) = "## Task\nNow: Working."

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

        override suspend fun replyPermission(
            sessionId: String,
            requestId: String,
            decision: ApprovalDecision,
            message: String?,
        ) {
            calls += "reply $requestId ${decision.wire}${message?.let { " \"$it\"" }.orEmpty()}"
            gate?.await()
            if (gone) throw RequestGoneException()
            if (fails) error("HTTP 500")
        }

        override suspend fun replyForm(
            sessionId: String,
            formId: String,
            answer: JsonObject,
        ) {
            calls += "answer $formId $answer"
        }

        override suspend fun dismissForm(
            sessionId: String,
            formId: String,
            message: String,
        ) {
            calls += "dismiss $formId \"$message\""
        }

        override suspend fun interrupt(sessionId: String): Boolean {
            calls += "interrupt $sessionId"
            return true
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

        fun send(
            type: String,
            data: String,
        ) = lines.trySend("""{"type":"$type","data":$data}""")
    }

    private val server = Server()
    private val stream = Stream()

    private fun approval(id: String) =
        PendingItem(id, "ses_1", PendingItem.Kind.Approval, "ls", approval = ApprovalRequest("shell", listOf("ls"), listOf("ls *")))

    private val form =
        QuestionForm(
            "Questions",
            listOf(
                QuestionField("q0", "Colour", "Which colour?", QuestionField.SINGLE, listOf(QuestionOption("Red", "Red")), custom = true),
                QuestionField(
                    "q1",
                    "Fruits",
                    "Which fruits?",
                    QuestionField.MULTIPLE,
                    listOf(QuestionOption("Apple", "Apple"), QuestionOption("Kiwi", "Kiwi")),
                    custom = true,
                ),
            ),
        )

    private fun TestScope.home(vararg texts: String): Pair<HomeViewModel, Dictation> {
        val dictation = Dictation(*texts)
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
        return vm to dictation
    }

    private fun TestScope.done(vm: HomeViewModel) {
        vm.viewModelScope.cancel()
        runCurrent()
    }

    @Test
    fun theOldestOpensAndAfterAReplyTheNextOpensAThirdOfASecondLater() =
        runTest(dispatcher) {
            server.pending = listOf(approval("per_1"), approval("per_2"))
            val (vm, _) = home()
            try {
                vm.openNeeds()
                assertEquals(
                    "per_1",
                    vm.uiState.value.needs
                        ?.itemId,
                )
                vm.approve(ApprovalDecision.Once)
                assertEquals(
                    NeedsAction.Once,
                    vm.uiState.value.needs
                        ?.busy,
                )
                runCurrent()

                // Closed at once, with no notice; the next one opens 0.3 s later.
                assertNull(vm.uiState.value.needs)
                assertNull(vm.uiState.value.notice)
                advanceTimeBy(NEXT_NEEDS_MS + 1)
                assertEquals(
                    "per_2",
                    vm.uiState.value.needs
                        ?.itemId,
                )
                vm.approve(ApprovalDecision.Always)
                runCurrent()
                advanceTimeBy(NEXT_NEEDS_MS + 1)
                assertNull(vm.uiState.value.needs)
                assertEquals(listOf("reply per_1 once", "reply per_2 always"), server.calls)
            } finally {
                done(vm)
            }
        }

    @Test
    fun denySendsWhatWasSaidElseTheDefaultNote() =
        runTest(dispatcher) {
            server.pending = listOf(approval("per_1"), approval("per_2"))
            val (vm, dictation) = home("Use printf instead of echo.")
            try {
                vm.openNeeds()
                dictation.gate = CompletableDeferred()
                assertTrue(vm.sheetTakeStart(SheetTarget.Note("per_1")))
                assertTrue(dictation.recording)
                advanceTimeBy(1_500)
                vm.sheetTakeEnd()
                assertTrue(
                    vm.uiState.value.sheetTake!!
                        .transcribing,
                )
                dictation.gate?.complete(Unit)
                runCurrent()
                assertNull(vm.uiState.value.sheetTake)
                assertEquals("Use printf instead of echo.", vm.uiState.value.notes["per_1"])
                vm.approve(ApprovalDecision.Reject)
                runCurrent()
                // The server rejects the session's other approval with the same note: nothing more opens.
                advanceTimeBy(NEXT_NEEDS_MS + 1)
                assertNull(vm.uiState.value.needs)
                assertTrue(
                    vm.uiState.value.today.pending
                        .isEmpty(),
                )
                // A new one with nothing said gets the default note.
                stream.send(
                    "permission.asked",
                    """{"id":"per_3","sessionID":"ses_1","action":"shell","resources":["ls"],"save":["ls *"]}""",
                )
                runCurrent()
                vm.openNeeds()
                vm.approve(ApprovalDecision.Reject)
                runCurrent()

                assertEquals(
                    listOf("reply per_1 reject \"Use printf instead of echo.\"", "reply per_3 reject \"${SessionPrompts.DENY_NOTE}\""),
                    server.calls,
                )
            } finally {
                done(vm)
            }
        }

    @Test
    fun aTapOnSayWhyKeepsWhatWasSaidBefore() =
        runTest(dispatcher) {
            server.pending = listOf(approval("per_1"))
            val (vm, dictation) = home("Ask me first.")
            try {
                vm.openNeeds()
                vm.sheetTakeStart(SheetTarget.Note("per_1"))
                advanceTimeBy(1_500)
                vm.sheetTakeEnd()
                runCurrent()
                // A tap (under 0.38 s) records nothing and keeps what was said.
                vm.sheetTakeStart(SheetTarget.Note("per_1"))
                advanceTimeBy(200)
                vm.sheetTakeEnd()
                runCurrent()
                assertFalse(dictation.recording)
                assertEquals("Ask me first.", vm.uiState.value.notes["per_1"])
                // An empty take (no speech) keeps it too.
                dictation.short = true
                vm.sheetTakeStart(SheetTarget.Note("per_1"))
                advanceTimeBy(1_000)
                vm.sheetTakeEnd()
                runCurrent()
                assertEquals("Ask me first.", vm.uiState.value.notes["per_1"])
            } finally {
                done(vm)
            }
        }

    @Test
    fun a404ClosesTheSheetAsAnsweredElsewhere() =
        runTest(dispatcher) {
            server.pending = listOf(approval("per_1"), approval("per_2"))
            server.gone = true
            val (vm, _) = home()
            try {
                vm.openNeeds()
                vm.approve(ApprovalDecision.Once)
                runCurrent()
                assertNull(vm.uiState.value.needs)
                assertEquals(HomeNotice.NeedsGone, vm.uiState.value.notice)
                assertEquals(
                    listOf("per_2"),
                    vm.uiState.value.today.pending
                        .map { it.id },
                )
            } finally {
                done(vm)
            }
        }

    @Test
    fun anItemAnsweredElsewhereOrEndedWithTheRunClosesTheSheet() =
        runTest(dispatcher) {
            server.pending = listOf(approval("per_1"))
            val (vm, _) = home()
            try {
                vm.openNeeds()
                stream.send("session.execution.interrupted", """{"sessionID":"ses_1","reason":"user"}""")
                runCurrent()
                assertNull(vm.uiState.value.needs)
                assertEquals(HomeNotice.NeedsGone, vm.uiState.value.notice)
                assertTrue(
                    vm.uiState.value.today.pending
                        .isEmpty(),
                )
            } finally {
                done(vm)
            }
        }

    @Test
    fun theReplyedEventDuringOurOwnReplyIsNotAnsweredElsewhere() =
        runTest(dispatcher) {
            server.pending = listOf(approval("per_1"))
            server.gate = CompletableDeferred()
            val (vm, _) = home()
            try {
                vm.openNeeds()
                vm.approve(ApprovalDecision.Once)
                runCurrent()
                stream.send("permission.replied", """{"sessionID":"ses_1","requestID":"per_1","reply":"once"}""")
                runCurrent()
                server.gate?.complete(Unit)
                runCurrent()
                assertNull(vm.uiState.value.needs)
                assertNull(vm.uiState.value.notice)
            } finally {
                done(vm)
            }
        }

    @Test
    fun aFailedReplyKeepsTheSheetAndSaysSo() =
        runTest(dispatcher) {
            server.pending = listOf(approval("per_1"))
            server.fails = true
            val (vm, _) = home()
            try {
                vm.openNeeds()
                vm.approve(ApprovalDecision.Once)
                runCurrent()
                assertEquals(NeedsSheet("per_1"), vm.uiState.value.needs)
                assertEquals(HomeNotice.NeedsFailed, vm.uiState.value.notice)
            } finally {
                done(vm)
            }
        }

    @Test
    fun hideClosesUnansweredAndTheHintStays() =
        runTest(dispatcher) {
            server.pending = listOf(approval("per_1"))
            val (vm, _) = home()
            try {
                vm.openNeeds()
                vm.hideNeeds()
                runCurrent()
                assertNull(vm.uiState.value.needs)
                assertEquals(
                    "per_1",
                    vm.uiState.value.today.pendingInput
                        ?.id,
                )
                assertTrue(server.calls.isEmpty())
            } finally {
                done(vm)
            }
        }

    @Test
    fun questionsAreAnsweredPageByPageWithAnOwnAnswer() =
        runTest(dispatcher) {
            server.pending = listOf(PendingItem("frm_1", "ses_1", PendingItem.Kind.Question, "Which colour?", form = form))
            val (vm, dictation) = home("Green")
            try {
                vm.openNeeds()
                vm.chooseOption("Red")
                dictation.gate = CompletableDeferred()
                vm.sheetTakeStart(SheetTarget.Own("frm_1", "q0"))
                advanceTimeBy(1_500)
                vm.sheetTakeEnd()
                // Next waits for the take's text.
                vm.nextQuestion()
                assertEquals(
                    0,
                    vm.uiState.value.answers["frm_1"]
                        ?.page,
                )
                dictation.gate?.complete(Unit)
                runCurrent()
                vm.nextQuestion()
                vm.chooseOption("Kiwi")
                vm.chooseOption("Apple")
                vm.previousQuestion()
                assertEquals(
                    "Green",
                    vm.uiState.value.answers["frm_1"]
                        ?.answer
                        ?.own,
                )
                vm.nextQuestion()
                vm.submitAnswers()
                runCurrent()
                assertEquals(listOf("answer frm_1 {\"q0\":\"Green\",\"q1\":[\"Apple\",\"Kiwi\"]}"), server.calls)
                assertNull(vm.uiState.value.needs)
            } finally {
                done(vm)
            }
        }

    @Test
    fun dismissSendsTheNote() =
        runTest(dispatcher) {
            server.pending = listOf(PendingItem("frm_1", "ses_1", PendingItem.Kind.Question, "Which colour?", form = form))
            val (vm, _) = home()
            try {
                vm.openNeeds()
                vm.dismissQuestion()
                runCurrent()
                assertEquals(listOf("dismiss frm_1 \"${SessionPrompts.DISMISS_NOTE}\""), server.calls)
            } finally {
                done(vm)
            }
        }

    @Test
    fun stopReleasedAtATenthAndAHalfOrHeldThreeSeconds() =
        runTest(dispatcher) {
            val (vm, _) = home()
            try {
                assertTrue(vm.uiState.value.stopLive)

                assertTrue(vm.stopPressed())
                assertTrue(vm.uiState.value.stopHolding)
                advanceTimeBy(200)
                vm.stopReleased()
                assertEquals(HomeNotice.StopTap, vm.uiState.value.notice)
                assertFalse(vm.uiState.value.stopHolding)

                vm.stopPressed()
                advanceTimeBy(1_500)
                vm.stopReleased()
                assertEquals(HomeNotice.StopCancelled, vm.uiState.value.notice)
                assertTrue(server.calls.isEmpty())

                vm.stopPressed()
                advanceTimeBy(STOP_HOLD_MS)
                runCurrent()
                assertFalse(vm.uiState.value.stopHolding)
                assertEquals(listOf("interrupt ses_1"), server.calls)
                assertEquals(HomeNotice.Stopped, vm.uiState.value.notice)
                // Letting go afterwards does nothing more.
                vm.stopReleased()
                assertEquals(HomeNotice.Stopped, vm.uiState.value.notice)
            } finally {
                done(vm)
            }
        }

    @Test
    fun aDimmedStopDoesNothing() =
        runTest(dispatcher) {
            server.busy = false
            val (vm, _) = home()
            try {
                assertFalse(vm.uiState.value.stopLive)
                assertFalse(vm.stopPressed())
                advanceTimeBy(STOP_HOLD_MS)
                assertTrue(server.calls.isEmpty())
            } finally {
                done(vm)
            }
        }

    // app-plan.md, Settings and finish (M6) › Testing › New session: what's shown starts empty; the draft stays.

    @Test
    fun aNewSessionClosesTheOldSessionsSheetsAndKeepsTheDraft() =
        runTest(dispatcher) {
            val (vm, _) = home()
            try {
                vm.addToDraft("Make it blue")
                vm.openDetails()
                runCurrent()
                assertEquals(dev.opencode.hexcode.session.NewSessionOutcome.Started, vm.startNewSession())
                runCurrent()
                val state = vm.uiState.value
                assertFalse(state.detailsOpen)
                assertEquals(null, state.needs)
                assertEquals(HomeNotice.NewSession, state.notice)
                assertEquals("Make it blue", state.draft.text)
            } finally {
                done(vm)
            }
        }

    // app-plan.md, Settings and finish (M6) › Accessibility: Stop through the screen reader's confirmation.

    @Test
    fun aScreenReadersConfirmedStopRunsTheSameStepsWithoutTheHold() =
        runTest(dispatcher) {
            val (vm, _) = home()
            try {
                vm.stopConfirmed()
                runCurrent()
                assertEquals(listOf("interrupt ses_1"), server.calls)
                assertEquals(HomeNotice.Stopped, vm.uiState.value.notice)
            } finally {
                done(vm)
            }
        }

    @Test
    fun aConfirmedStopWhileDimmedDoesNothing() =
        runTest(dispatcher) {
            server.busy = false
            val (vm, _) = home()
            try {
                vm.stopConfirmed()
                runCurrent()
                assertTrue(server.calls.isEmpty())
            } finally {
                done(vm)
            }
        }

    @Test
    fun theWarningCountsThreeTwoOne() {
        assertEquals(3, StopCount.left(0))
        assertEquals(3, StopCount.left(999))
        assertEquals(2, StopCount.left(1_000))
        assertEquals(1, StopCount.left(2_500))
        assertEquals(1, StopCount.left(3_000))
    }

    @Test
    fun aTapOnTheOwnAnswerChoosesItOrTogglesIt() =
        runTest(dispatcher) {
            server.pending = listOf(PendingItem("frm_1", "ses_1", PendingItem.Kind.Question, "Which colour?", form = form))
            val (vm, dictation) = home()
            try {
                vm.openNeeds()
                vm.chooseOption("Red")
                vm.sheetTakeStart(SheetTarget.Own("frm_1", "q0"))
                advanceTimeBy(100)
                vm.sheetTakeEnd()
                assertFalse(dictation.recording)
                val single =
                    vm.uiState.value.answers["frm_1"]!!
                        .answer
                assertTrue(single.ownChosen)
                assertTrue(single.chosen.isEmpty())
                assertFalse(single.answered)
                vm.chooseOption("Red")
                vm.nextQuestion()
                vm.sheetTakeStart(SheetTarget.Own("frm_1", "q1"))
                advanceTimeBy(100)
                vm.sheetTakeEnd()
                assertTrue(
                    vm.uiState.value.answers["frm_1"]!!
                        .answer.ownChosen,
                )
                vm.sheetTakeStart(SheetTarget.Own("frm_1", "q1"))
                advanceTimeBy(100)
                vm.sheetTakeEnd()
                assertFalse(
                    vm.uiState.value.answers["frm_1"]!!
                        .answer.ownChosen,
                )
            } finally {
                done(vm)
            }
        }
}
