package dev.opencode.hexcode.home

import androidx.lifecycle.viewModelScope
import dev.opencode.hexcode.firstrun.ModelChoice
import dev.opencode.hexcode.session.DailySession
import dev.opencode.hexcode.session.DailySessionApi
import dev.opencode.hexcode.session.DailySessionMemory
import dev.opencode.hexcode.session.DailySessionSetup
import dev.opencode.hexcode.session.DetailsBody
import dev.opencode.hexcode.session.DetailsSection
import dev.opencode.hexcode.session.DetailsSections
import dev.opencode.hexcode.session.EventTransport
import dev.opencode.hexcode.session.LiveSessionApi
import dev.opencode.hexcode.session.LiveSnapshot
import dev.opencode.hexcode.session.PromptImage
import dev.opencode.hexcode.session.RecentOutcome
import dev.opencode.hexcode.session.RecentStep
import dev.opencode.hexcode.session.RunningRead
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
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * app-plan.md, Needs you and control › Details › Size and first load: Recent and Running now are read in the background
 * while home is open (when today's session opens, and 1 s after each run ends), so Details opens with them in place; until
 * the first reads return it shows a spinner, never "Nothing running right now.".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailsPrefetchTest {
    private val dispatcher = StandardTestDispatcher()
    private val today = LocalDate.of(2026, 11, 7)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Dictation : dev.opencode.hexcode.home.Dictation {
        override val level = 0f
        override val speech = MutableStateFlow<SpeechStatus>(SpeechStatus.Ready)

        override fun start(onFailed: (Throwable) -> Unit) = Unit

        override suspend fun end(): FloatArray? = null

        override fun drop() = Unit

        override suspend fun transcribe(samples: FloatArray) = ""

        override fun reload() = Unit
    }

    private inner class Server :
        DailySessionApi,
        LiveSessionApi,
        DailySessionMemory {
        var busy = false
        var steps = listOf(step("ls"))
        var recentReads = 0
        var runningReads = 0
        var recentGate: CompletableDeferred<Unit>? = null
        var runningGate: CompletableDeferred<Unit>? = null
        var recentFails = false

        override suspend fun sessionsPage(
            directory: String,
            search: String,
            limit: Int,
            cursor: String?,
        ) = SessionListingPage(listOf(SessionListing("ses_1", DailySession.title(today), "/p", 1, busySince = if (busy) 1 else null)), null)

        override suspend fun create(
            title: String,
            directory: String,
            model: ModelChoice?,
        ) = SessionListing("ses_2", title, directory, 1)

        override suspend fun putMessageRules(sessionId: String) = Unit

        override suspend fun snapshot(rootId: String) =
            LiveSnapshot(
                if (busy) 5 else null,
                hasRun = true,
                children = emptyList(),
                pending = emptyList(),
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

        override suspend fun running(
            rootId: String,
            directory: String,
        ): RunningRead {
            runningReads += 1
            runningGate?.await()
            return RunningRead(emptyMap(), emptySet(), emptyList())
        }

        override suspend fun recent(sessionId: String): List<RecentStep> {
            recentReads += 1
            recentGate?.await()
            if (recentFails) error("HTTP 500")
            return steps
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

    private fun step(command: String) = RecentStep(RecentStep.Kind.Command, command, RecentOutcome.Done, 1)

    private fun TestScope.home(): HomeViewModel {
        val vm =
            HomeViewModel(Dictation(), makeSession = { scope ->
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
    fun recentAndRunningNowAreReadWhenTodaysSessionOpensBeforeDetailsIs() =
        runTest(dispatcher) {
            val vm = home()
            try {
                assertEquals(1, server.recentReads)
                assertEquals(1, server.runningReads)
                val state = vm.uiState.value
                assertFalse(state.detailsOpen)
                assertEquals(listOf(step("ls")), state.recent)
                assertFalse(state.detailsReading)
            } finally {
                done(vm)
            }
        }

    @Test
    fun openingDetailsShowsWhatWasReadAndRefreshesItQuietly() =
        runTest(dispatcher) {
            val vm = home()
            try {
                server.steps = listOf(step("ls"), step("pwd"))
                server.recentGate = CompletableDeferred()
                vm.openDetails()
                runCurrent()
                // The rows read before stay while the new read runs: no spinner, no empty line.
                assertEquals(listOf(step("ls")), vm.uiState.value.recent)
                assertFalse(vm.uiState.value.detailsReading)
                server.recentGate?.complete(Unit)
                runCurrent()
                assertEquals(2, vm.uiState.value.recent.size)
            } finally {
                done(vm)
            }
        }

    @Test
    fun untilTheFirstReadsReturnDetailsIsStillReading() =
        runTest(dispatcher) {
            server.recentGate = CompletableDeferred()
            server.runningGate = CompletableDeferred()
            val vm = home()
            try {
                vm.openDetails()
                runCurrent()
                assertTrue(vm.uiState.value.detailsReading)
                server.runningGate?.complete(Unit)
                runCurrent()
                assertTrue(vm.uiState.value.detailsReading)
                server.recentGate?.complete(Unit)
                runCurrent()
                assertFalse(vm.uiState.value.detailsReading)
            } finally {
                done(vm)
            }
        }

    @Test
    fun aFailedReadStillCountsAsReturned() =
        runTest(dispatcher) {
            server.recentFails = true
            val vm = home()
            try {
                assertFalse(vm.uiState.value.detailsReading)
                assertTrue(
                    vm.uiState.value.recent
                        .isEmpty(),
                )
            } finally {
                done(vm)
            }
        }

    @Test
    fun recentAndRunningNowAreReadAgainASecondAfterARunEnds() =
        runTest(dispatcher) {
            server.busy = true
            val vm = home()
            try {
                val recent = server.recentReads
                val running = server.runningReads
                server.steps = listOf(step("make"))
                stream.lines.trySend("""{"type":"session.execution.succeeded","created":9,"data":{"sessionID":"ses_1"}}""")
                runCurrent()
                assertEquals(recent, server.recentReads)
                advanceTimeBy(999)
                assertEquals(recent, server.recentReads)
                advanceTimeBy(2)
                assertEquals(recent + 1, server.recentReads)
                assertEquals(running + 1, server.runningReads)
                assertEquals(listOf(step("make")), vm.uiState.value.recent)
            } finally {
                done(vm)
            }
        }

    @Test
    fun aNewSessionStartsReadingAfresh() =
        runTest(dispatcher) {
            val vm = home()
            try {
                server.steps = emptyList()
                server.recentGate = CompletableDeferred()
                vm.startNewSession()
                runCurrent()
                // The old session's rows go; the new one's haven't come back yet.
                assertTrue(
                    vm.uiState.value.recent
                        .isEmpty(),
                )
                assertTrue(vm.uiState.value.detailsReading)
                server.recentGate?.complete(Unit)
                runCurrent()
                assertFalse(vm.uiState.value.detailsReading)
            } finally {
                done(vm)
            }
        }

    @Test
    fun theBodyIsASpinnerUntilEveryReadReturnedThenTheEmptyLine() {
        assertEquals(DetailsBody.Loading, DetailsSections.body(emptyList(), reading = true))
        assertEquals(DetailsBody.Empty, DetailsSections.body(emptyList(), reading = false))
        // What's already known shows while the reads run.
        assertEquals(DetailsBody.Sections, DetailsSections.body(listOf(DetailsSection.Todo), reading = true))
        assertEquals(DetailsBody.Sections, DetailsSections.body(listOf(DetailsSection.Recent), reading = false))
    }
}
