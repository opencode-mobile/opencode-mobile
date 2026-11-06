package dev.opencode.hexcode.home

import androidx.lifecycle.viewModelScope
import dev.opencode.hexcode.firstrun.ModelChoice
import dev.opencode.hexcode.session.DailySession
import dev.opencode.hexcode.session.DailySessionApi
import dev.opencode.hexcode.session.DailySessionMemory
import dev.opencode.hexcode.session.DailySessionSetup
import dev.opencode.hexcode.session.EventTransport
import dev.opencode.hexcode.session.LiveSessionApi
import dev.opencode.hexcode.session.LiveSnapshot
import dev.opencode.hexcode.session.SessionListing
import dev.opencode.hexcode.session.SessionListingPage
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/** app-plan.md, Sending: delivery per agent state, Now held 3 s with Undo, failure keeps the draft, the waiting sheet. */
@OptIn(ExperimentalCoroutinesApi::class)
class SendingTest {
    private val dispatcher = StandardTestDispatcher()
    private val today = LocalDate.of(2026, 11, 6)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Takes whose text is the next of [texts]; [gate] holds the transcription. */
    private class Dictation(
        vararg texts: String,
    ) : dev.opencode.hexcode.home.Dictation {
        val texts = ArrayDeque(texts.toList())
        var gate: CompletableDeferred<Unit>? = null
        private var recording = false
        override val level = 0f
        override val speech = MutableStateFlow<SpeechStatus>(SpeechStatus.Ready)

        override fun start(onFailed: (Throwable) -> Unit) {
            recording = true
        }

        override suspend fun end(): FloatArray? = if (recording) FloatArray(16_000).also { recording = false } else null

        override fun drop() {
            recording = false
        }

        override suspend fun transcribe(samples: FloatArray): String {
            gate?.await()
            return texts.removeFirst()
        }

        override fun reload() = Unit
    }

    /** The server: today's session, busy or not, and what was sent or cancelled. */
    private inner class Server :
        DailySessionApi,
        LiveSessionApi,
        DailySessionMemory {
        var busy = false
        var fail = false
        val sent = mutableListOf<Pair<String, String>>()
        val cancelled = mutableListOf<String>()

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

        override suspend fun send(
            sessionId: String,
            text: String,
            delivery: String,
        ): String {
            if (fail) error("The computer can't be reached.")
            sent += text to delivery
            return "msg_${sent.size}"
        }

        override suspend fun cancelQueued(
            sessionId: String,
            itemId: String,
        ) {
            cancelled += itemId
        }

        override suspend fun switchModel(
            sessionId: String,
            model: ModelChoice,
        ) = Unit

        override suspend fun lastOpenedOn() = today

        override suspend fun setLastOpenedOn(date: LocalDate) = Unit
    }

    /** Each connection reads the lines the test sends; null ends it. */
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

        fun connected() = lines.trySend("""{"type":"server.connected","data":{}}""")
    }

    private val server = Server()
    private val stream = Stream()

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
        stream.connected()
        runCurrent()
        return vm to dictation
    }

    private fun TestScope.dictate(vm: HomeViewModel) {
        vm.press()
        vm.release(TalkRelease.End)
        runCurrent()
    }

    private fun TestScope.done(vm: HomeViewModel) {
        // The stream's 35 s watchdog would run forever.
        vm.viewModelScope.cancel()
        runCurrent()
    }

    @Test
    fun toAnIdleAgentItSendsAtOnceAsSteer() =
        runTest(dispatcher) {
            val (vm, _) = home("Run the tests")
            dictate(vm)
            vm.send()
            runCurrent()
            assertEquals(listOf("Run the tests" to "steer"), server.sent)
            assertTrue(vm.uiState.value.draft.isEmpty)
            assertEquals(HomeNotice.SentIdle, vm.uiState.value.notice)
            done(vm)
        }

    @Test
    fun toAWorkingAgentItQueuesByDefault() =
        runTest(dispatcher) {
            server.busy = true
            val (vm, _) = home("Also check the logs")
            assertTrue(vm.uiState.value.busy)
            dictate(vm)
            vm.send()
            runCurrent()
            assertEquals(listOf("Also check the logs" to "queue"), server.sent)
            assertEquals(HomeNotice.Queued, vm.uiState.value.notice)
            done(vm)
        }

    @Test
    fun nowIsHeld3sThenSentAsACorrectionAndTheSwitchGoesBack() =
        runTest(dispatcher) {
            server.busy = true
            val (vm, _) = home("Use the old button")
            dictate(vm)
            vm.setSendMode(SendMode.Now)
            vm.send()
            runCurrent()
            assertEquals(SendMode.AfterTurn, vm.uiState.value.sendMode)
            assertEquals(HomeNotice.SendingNow, vm.uiState.value.notice)
            advanceTimeBy(2_900)
            runCurrent()
            assertTrue(server.sent.isEmpty())
            advanceTimeBy(200)
            runCurrent()
            assertEquals(listOf("[Correction] Use the old button" to "steer"), server.sent)
            assertEquals(HomeNotice.SentNow, vm.uiState.value.notice)
            done(vm)
        }

    @Test
    fun undoDuringTheHoldSendsNothingAndKeepsTheDraft() =
        runTest(dispatcher) {
            server.busy = true
            val (vm, _) = home("Use the old button")
            dictate(vm)
            vm.setSendMode(SendMode.Now)
            vm.send()
            runCurrent()
            // The toast calls its action, then reports itself dismissed.
            vm.noticeAction()
            vm.noticeShown()
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue(server.sent.isEmpty())
            assertEquals(HomeNotice.Unsent, vm.uiState.value.notice)
            assertEquals("Use the old button", vm.uiState.value.draft.text)
            done(vm)
        }

    @Test
    fun undoOfAQueuedMessageTakesItBackFromTheInbox() =
        runTest(dispatcher) {
            server.busy = true
            val (vm, _) = home("Also check the logs")
            dictate(vm)
            vm.send()
            runCurrent()
            vm.noticeAction()
            vm.noticeShown()
            runCurrent()
            assertEquals(listOf("msg_1"), server.cancelled)
            assertEquals(HomeNotice.TakenBack, vm.uiState.value.notice)
            done(vm)
        }

    @Test
    fun sendWaitsForEveryChunk() =
        runTest(dispatcher) {
            val (vm, dictation) = home("One", "Two")
            dictate(vm)
            dictation.gate = CompletableDeferred()
            dictate(vm)
            assertFalse(vm.uiState.value.draft.isSettled)
            vm.send()
            runCurrent()
            assertTrue(server.sent.isEmpty())
            dictation.gate?.complete(Unit)
            runCurrent()
            vm.send()
            runCurrent()
            assertEquals(listOf("One Two" to "steer"), server.sent)
            done(vm)
        }

    @Test
    fun aFailedSendBringsTheDraftBackAsItWas() =
        runTest(dispatcher) {
            server.fail = true
            val (vm, _) = home("One", "Two")
            dictate(vm)
            dictate(vm)
            val before = vm.uiState.value.draft.chunks
            vm.send()
            runCurrent()
            assertEquals(before, vm.uiState.value.draft.chunks)
            assertEquals(HomeNotice.SendFailed, vm.uiState.value.notice)
            done(vm)
        }

    @Test
    fun aNowHeldThroughAReconnectGoesOnceTheStreamIsBack() =
        runTest(dispatcher) {
            server.busy = true
            val (vm, _) = home("Stop that")
            dictate(vm)
            vm.setSendMode(SendMode.Now)
            vm.send()
            runCurrent()
            // The stream drops during the hold.
            stream.lines.trySend(null)
            runCurrent()
            advanceTimeBy(3_100)
            runCurrent()
            assertTrue(server.sent.isEmpty())
            advanceTimeBy(1_300)
            stream.connected()
            runCurrent()
            assertEquals(listOf("[Correction] Stop that" to "steer"), server.sent)
            done(vm)
        }

    @Test
    fun cancelInTheSheetCallsDeleteAndADeliveredMessageLeavesTheList() =
        runTest(dispatcher) {
            server.busy = true
            val (vm, _) = home()

            fun enqueue(id: String) =
                stream.lines.trySend(
                    """{"type":"session.inbox.enqueued","data":{"sessionID":"ses_1","inboxID":"$id",""" +
                        """"item":{"type":"user","payload":{"text":"Message $id"},"delivery":"queue"}}}""",
                )
            enqueue("msg_a")
            enqueue("msg_b")
            runCurrent()
            assertEquals(
                listOf("Message msg_a", "Message msg_b"),
                vm.uiState.value.today.queued
                    .map { it.text },
            )
            vm.cancelQueued("msg_a")
            runCurrent()
            assertEquals(listOf("msg_a"), server.cancelled)
            stream.lines.trySend("""{"type":"session.inbox.delivered","data":{"sessionID":"ses_1","inboxID":"msg_b"}}""")
            stream.lines.trySend("""{"type":"session.inbox.cancelled","data":{"sessionID":"ses_1","inboxID":"msg_a"}}""")
            runCurrent()
            assertTrue(
                vm.uiState.value.today.queued
                    .isEmpty(),
            )
            done(vm)
        }

    @Test
    fun theDeliveryRules() {
        assertEquals(SendPlan.Idle, Sending.plan(busy = false, mode = SendMode.Now))
        assertEquals(SendPlan.Queue, Sending.plan(busy = true, mode = SendMode.AfterTurn))
        assertEquals(SendPlan.Now, Sending.plan(busy = true, mode = SendMode.Now))
        assertEquals("steer", Sending.delivery(SendPlan.Idle))
        assertEquals("queue", Sending.delivery(SendPlan.Queue))
        assertEquals("steer", Sending.delivery(SendPlan.Now))
        assertEquals("[Correction] Fix it", Sending.text(SendPlan.Now, "Fix it"))
        assertEquals("Fix it", Sending.text(SendPlan.Queue, "Fix it"))
    }
}
