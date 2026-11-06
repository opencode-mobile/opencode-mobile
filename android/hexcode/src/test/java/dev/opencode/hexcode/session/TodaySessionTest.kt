package dev.opencode.hexcode.session

import dev.opencode.hexcode.firstrun.ModelChoice
import dev.opencode.mobile.data.remote.SseAttemptOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Today's session end to end with fakes: setup, then the stream; the state read back on every (re)connect; the summary
 * on open, reconnect and run end; reconnecting, the background grace, and a new date on return (app-plan.md, Testing).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodaySessionTest {
    private val project = "/Users/me/Projects/hexcode"
    private val model = ModelChoice("opencode", "big-pickle")
    private val log = mutableListOf<String>()
    private var date = LocalDate.of(2026, 11, 5)

    private inner class Api(
        val listed: MutableList<SessionListing> = mutableListOf(),
    ) : DailySessionApi,
        LiveSessionApi {
        var fails = false
        var snapshot = LiveSnapshot(busySince = null, hasRun = true, children = emptyList(), pending = emptyList(), inbox = emptyList())
        var reply = "## Task\nNow: Working."
        var gate: CompletableDeferred<Unit>? = null
        val models = mutableListOf<ModelChoice>()
        private var next = 1

        override suspend fun sessionsPage(
            directory: String,
            search: String,
            limit: Int,
            cursor: String?,
        ): SessionListingPage {
            log += "list"
            gate?.await()
            if (fails) error("unreachable")
            return SessionListingPage(listed.filter { it.title == search }, null)
        }

        override suspend fun create(
            title: String,
            directory: String,
            model: ModelChoice?,
        ): SessionListing {
            log += "create $title"
            return SessionListing("new${next++}", title, directory, 1).also { listed.add(0, it) }
        }

        override suspend fun putMessageRules(sessionId: String) {
            log += "rules $sessionId"
        }

        override suspend fun snapshot(rootId: String): LiveSnapshot {
            log += "snapshot $rootId"
            return snapshot
        }

        override suspend fun summarise(
            sessionId: String,
            prompt: String,
        ): String {
            log += "summary $sessionId"
            return reply
        }

        override suspend fun switchModel(
            sessionId: String,
            model: ModelChoice,
        ) {
            models += model
        }

        override suspend fun send(
            sessionId: String,
            text: String,
            delivery: String,
        ) = "msg_1"

        var cancelFails = false

        override suspend fun cancelQueued(
            sessionId: String,
            itemId: String,
        ) {
            log += "cancel $itemId"
            if (cancelFails) error("HTTP 500")
        }
    }

    /** The phone's saved card: one session's at a time. */
    private class Store(
        var saved: SavedSummary? = null,
    ) : SummaryStore {
        override suspend fun loadSummary() = saved

        override suspend fun saveSummary(saved: SavedSummary) {
            this.saved = saved
        }
    }

    private class Memory(
        var last: LocalDate?,
    ) : DailySessionMemory {
        override suspend fun lastOpenedOn() = last

        override suspend fun setLastOpenedOn(date: LocalDate) {
            last = date
        }
    }

    /** Each connection reads lines the test sends; null ends it. [refuse] fails every attempt. */
    private inner class Transport : EventTransport {
        val lines = Channel<String?>(Channel.UNLIMITED)
        var refuse = false
        var attempts = 0

        override suspend fun connect(
            onActivity: () -> Unit,
            onLine: suspend (String) -> Unit,
        ): SseAttemptOutcome {
            attempts += 1
            log += "stream"
            if (refuse) return SseAttemptOutcome.Transport(null)
            while (true) {
                val line = lines.receive() ?: return SseAttemptOutcome.Ended
                onActivity()
                onLine(line)
            }
        }

        fun send(line: String) = lines.trySend(line)

        fun connected() = send("""{"id":"evt_0","type":"server.connected","data":{}}""")

        fun end() = lines.trySend(null)
    }

    private fun TestScope.session(
        api: Api,
        transport: Transport?,
        memory: Memory = Memory(date.minusDays(1)),
        store: SummaryStore? = null,
    ) = TodaySession(
        scope = backgroundScope,
        setup = DailySessionSetup(api, memory),
        api = api,
        transport = transport,
        directory = { project },
        model = { model },
        today = { date },
        clock = { testScheduler.currentTime },
        summaryStore = store,
    )

    private fun event(
        type: String,
        data: String,
        created: Long = 0,
    ) = """{"id":"evt","created":$created,"type":"$type","data":$data}"""

    @Test
    fun noSessionTodayIsCreatedTheRulesPutThenTheStreamOpens() =
        runTest {
            val api = Api()
            val transport = Transport()
            val today = session(api, transport)
            today.open()
            runCurrent()
            assertEquals(listOf("list", "create 2026-11-05", "rules new1", "stream"), log)
            // A new session never ran: the empty card, no summary request.
            assertEquals(StatusSummary.Empty("Thursday 5 November"), today.state.value.summary.summary)
            assertTrue(today.state.value.newDayNotice)
        }

    @Test
    fun whileSetupRunsTheTopBarStateIsEmpty() =
        runTest {
            val api = Api().apply { gate = CompletableDeferred() }
            val today = session(api, Transport())
            today.open()
            runCurrent()
            assertEquals(SessionSetup.Opening, today.state.value.setup)
            assertNull(today.state.value.agent)
            api.gate?.complete(Unit)
            runCurrent()
            assertEquals(AgentStatus.Idle, today.state.value.agent)
        }

    @Test
    fun aSessionThatRanIsSummarisedOnceTheStreamHasReadTheStateBack() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            api.snapshot = api.snapshot.copy(busySince = 50, inbox = listOf(InboxEntry("m1", "queue"), InboxEntry("m2", "steer")))
            val transport = Transport()
            val today = session(api, transport, Memory(date))
            today.open()
            runCurrent()
            transport.connected()
            runCurrent()
            // The summary waits for the read-back, which says whether the agent is working.
            assertEquals(listOf("list", "rules s1", "stream", "snapshot s1", "summary s1"), log)
            assertEquals(AgentStatus.Working(50), today.state.value.agent)
            assertEquals(1, today.state.value.waiting)
            assertFalse(today.state.value.newDayNotice)
        }

    @Test
    fun aRunStartingAndEndingRefreshesTheSummaryOnce() =
        runTest {
            val api = Api()
            api.snapshot = api.snapshot.copy(hasRun = false)
            val transport = Transport()
            val today = session(api, transport)
            today.open()
            runCurrent()
            transport.connected()
            transport.send(event("session.execution.started", """{"sessionID":"new1"}""", created = 1_000))
            runCurrent()
            assertEquals(AgentStatus.Working(1_000), today.state.value.agent)
            transport.send(event("session.execution.succeeded", """{"sessionID":"new1"}"""))
            runCurrent()
            assertEquals(AgentStatus.Idle, today.state.value.agent)
            assertEquals(1, log.count { it.startsWith("summary") })
            assertEquals("Working.", (today.state.value.summary.summary as StatusSummary.Card).now)
        }

    @Test
    fun anApprovalShowsNeedsInputUntilItIsReplied() =
        runTest {
            val api = Api()
            val transport = Transport()
            val today = session(api, transport)
            today.open()
            runCurrent()
            transport.connected()
            transport.send(
                event("permission.asked", """{"id":"per_1","sessionID":"new1","action":"shell","resources":["ls"]}"""),
            )
            runCurrent()
            assertEquals(AgentStatus.NeedsInput, today.state.value.agent)
            assertEquals(PendingItem("per_1", "new1", PendingItem.Kind.Approval, "ls"), today.state.value.pendingInput)
            transport.send(event("permission.replied", """{"sessionID":"new1","requestID":"per_1","reply":"once"}"""))
            runCurrent()
            assertNull(today.state.value.pendingInput)
        }

    @Test
    fun aDroppedStreamShowsReconnectingThenReadsTheStateAgainAndRefreshes() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            val transport = Transport()
            val today = session(api, transport, Memory(date))
            today.open()
            runCurrent()
            transport.connected()
            runCurrent()
            log.clear()
            transport.end()
            runCurrent()
            assertEquals(AgentStatus.Reconnecting, today.state.value.agent)
            // Missed while down: a question came in.
            api.snapshot = api.snapshot.copy(pending = listOf(PendingItem("frm_1", "s1", PendingItem.Kind.Question, "Which one?")))
            advanceTimeBy(1_300)
            transport.connected()
            runCurrent()
            assertEquals(listOf("stream", "snapshot s1", "summary s1"), log)
            assertEquals(AgentStatus.NeedsInput, today.state.value.agent)
        }

    @Test
    fun thirtyFiveSecondsWithoutDataCountsAsDown() =
        runTest {
            val transport = Transport()
            val today = session(Api(), transport)
            today.open()
            runCurrent()
            transport.connected()
            runCurrent()
            advanceTimeBy(34_000)
            runCurrent()
            assertEquals(AgentStatus.Idle, today.state.value.agent)
            transport.send(""": heartbeat""")
            advanceTimeBy(34_000)
            runCurrent()
            assertEquals(AgentStatus.Idle, today.state.value.agent)
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(AgentStatus.Reconnecting, today.state.value.agent)
        }

    @Test
    fun theStreamClosesFiveSecondsIntoTheBackgroundAndANewDateOpensANewSessionOnReturn() =
        runTest {
            val api = Api()
            val transport = Transport()
            val today = session(api, transport, Memory(null))
            today.open()
            runCurrent()
            assertFalse(today.state.value.newDayNotice)
            transport.connected()
            runCurrent()
            today.enteredBackground()
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals(1, transport.attempts)
            advanceTimeBy(1_500)
            runCurrent()
            // Overnight in the background.
            date = date.plusDays(1)
            log.clear()
            today.enteredForeground()
            runCurrent()
            assertEquals(listOf("list", "create 2026-11-06", "rules new2", "stream"), log)
            assertEquals(SessionSetup.Ready("new2"), today.state.value.setup)
            assertTrue(today.state.value.newDayNotice)
        }

    @Test
    fun backInFrontOnTheSameDayReconnectsWithoutLookingAgain() =
        runTest {
            val api = Api()
            val transport = Transport()
            val today = session(api, transport)
            today.open()
            runCurrent()
            today.enteredBackground()
            advanceTimeBy(6_000)
            runCurrent()
            log.clear()
            today.enteredForeground()
            runCurrent()
            assertEquals(listOf("stream"), log)
        }

    @Test
    fun aFailureShowsTryAgainWithASpinnerAndNeverMakesASecondSession() =
        runTest {
            val api = Api().apply { fails = true }
            val today = session(api, Transport())
            today.open()
            runCurrent()
            assertEquals(SessionSetup.Failed(retrying = false), today.state.value.setup)
            api.fails = false
            api.gate = CompletableDeferred()
            today.retry()
            runCurrent()
            assertEquals(SessionSetup.Failed(retrying = true), today.state.value.setup)
            api.gate?.complete(Unit)
            runCurrent()
            assertEquals(SessionSetup.Ready("new1"), today.state.value.setup)
            assertEquals(1, log.count { it.startsWith("create") })
        }

    @Test
    fun aModelChangeReachesTheSession() =
        runTest {
            val api = Api()
            val today = session(api, null)
            today.open()
            runCurrent()
            val next = ModelChoice("opencode", "beam-free", "low")
            today.switchModel(next)
            runCurrent()
            assertEquals(listOf(next), api.models)
        }

    @Test
    fun droppingTheStreamHoldsItDownFor10s() =
        runTest {
            val transport = Transport()
            val today = session(Api(), transport)
            today.open()
            runCurrent()
            transport.connected()
            runCurrent()
            today.dropStream()
            runCurrent()
            assertEquals(AgentStatus.Reconnecting, today.state.value.agent)
            advanceTimeBy(9_900)
            runCurrent()
            assertEquals(1, transport.attempts)
            advanceTimeBy(200)
            runCurrent()
            assertEquals(2, transport.attempts)
        }

    @Test
    fun anotherStartDuringARunDoesNotRestartTheClock() =
        runTest {
            val transport = Transport()
            val today = session(Api(), transport)
            today.open()
            runCurrent()
            transport.connected()
            transport.send(event("session.execution.started", """{"sessionID":"new1"}""", created = 1_000))
            runCurrent()
            // A queued message delivered inside the run, or a second start event, keeps the first start.
            transport.send(event("session.inbox.delivered", """{"sessionID":"new1","inboxID":"m1"}"""))
            transport.send(event("session.execution.started", """{"sessionID":"new1"}""", created = 9_000))
            runCurrent()
            assertEquals(AgentStatus.Working(1_000), today.state.value.agent)
        }

    @Test
    fun anEmptyReplyWhileWorkingKeepsTheCardWithoutTheNoteAndTheRunsEndRefreshesIt() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            val transport = Transport()
            val today = session(api, transport, Memory(date))
            today.open()
            runCurrent()
            transport.connected()
            transport.send(event("session.execution.started", """{"sessionID":"s1"}""", created = 60))
            runCurrent()
            val card = today.state.value.summary.summary
            api.reply = ""
            today.refresh()
            runCurrent()
            assertEquals(card, today.state.value.summary.summary)
            assertFalse(today.state.value.summary.failed)
            assertFalse(today.state.value.summary.loading)
            api.reply = "## Task\nNow: Done."
            transport.send(event("session.execution.succeeded", """{"sessionID":"s1"}"""))
            runCurrent()
            assertEquals("Done.", (today.state.value.summary.summary as StatusSummary.Card).now)
        }

    @Test
    fun anEmptyReplyWhileIdleShowsCouldntUpdate() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            val today = session(api, Transport(), Memory(date))
            today.open()
            runCurrent()
            api.reply = ""
            today.refresh()
            runCurrent()
            assertTrue(today.state.value.summary.failed)
        }

    @Test
    fun aRelaunchMidRunShowsTheSavedCardAndAnEmptyReplyIsNotAFailure() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            api.snapshot = api.snapshot.copy(busySince = 50)
            api.reply = ""
            val saved = SavedSummary("s1", StatusSummary.parse("## Task\nNow: Reading the tests."), updatedAt = -720_000)
            val transport = Transport()
            val today = session(api, transport, Memory(date), Store(saved))
            today.open()
            runCurrent()
            // At once, before the stream is up, with its age.
            assertEquals(saved.summary, today.state.value.summary.summary)
            assertEquals(
                RefreshLabel.MinutesAgo(12),
                today.state.value.summary
                    .label(0),
            )
            assertEquals(0, log.count { it.startsWith("summary") })
            transport.connected()
            runCurrent()
            assertEquals(listOf("list", "rules s1", "stream", "snapshot s1", "summary s1"), log)
            assertEquals(saved.summary, today.state.value.summary.summary)
            assertFalse(today.state.value.summary.failed)
            assertEquals(
                RefreshLabel.MinutesAgo(12),
                today.state.value.summary
                    .label(0),
            )
        }

    @Test
    fun aNewCardIsSavedForTodaysSessionAndReplacesTheSavedOneOnScreen() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            val store = Store(SavedSummary("s1", StatusSummary.Fallback("Earlier."), updatedAt = 0))
            val transport = Transport()
            val today = session(api, transport, Memory(date), store)
            today.open()
            runCurrent()
            assertEquals(StatusSummary.Fallback("Earlier."), today.state.value.summary.summary)
            transport.connected()
            runCurrent()
            val card = StatusSummary.parse("## Task\nNow: Working.")
            assertEquals(card, today.state.value.summary.summary)
            assertEquals(SavedSummary("s1", card, testScheduler.currentTime), store.saved)
        }

    @Test
    fun aCardSavedForAnotherSessionIsNotShown() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            val store = Store(SavedSummary("s0", StatusSummary.Fallback("Yesterday."), updatedAt = 0))
            val today = session(api, Transport(), Memory(date), store)
            today.open()
            runCurrent()
            assertNull(today.state.value.summary.summary)
        }

    @Test
    fun withNoCardAnEmptyReplyWhileWorkingSaysTheAgentIsWorking() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            api.snapshot = api.snapshot.copy(busySince = 50)
            api.reply = ""
            val store = Store()
            val transport = Transport()
            val today = session(api, transport, Memory(date), store)
            today.open()
            runCurrent()
            transport.connected()
            runCurrent()
            assertEquals(StatusSummary.Working("Thursday 5 November"), today.state.value.summary.summary)
            assertFalse(today.state.value.summary.failed)
            // Only real cards are kept for the next launch.
            assertNull(store.saved)
        }

    @Test
    fun withoutAStreamTheSummaryIsAskedForOnOpen() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            val today = session(api, null, Memory(date))
            today.open()
            runCurrent()
            assertEquals(listOf("list", "rules s1", "summary s1"), log)
        }

    @Test
    fun aCancelledMessageLeavesTheListOnceTheDeleteWorks() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            api.snapshot = api.snapshot.copy(busySince = 50, inbox = listOf(InboxEntry("m1", "queue"), InboxEntry("m2", "queue")))
            val transport = Transport()
            val today = session(api, transport, Memory(date))
            today.open()
            runCurrent()
            transport.connected()
            runCurrent()
            today.cancelQueued("m1")
            runCurrent()
            assertEquals(
                listOf("m2"),
                today.state.value.queued
                    .map { it.id },
            )
            assertEquals(1, today.state.value.waiting)
            api.cancelFails = true
            val failed = runCatching { today.cancelQueued("m2") }
            assertTrue(failed.isFailure)
            runCurrent()
            assertEquals(
                listOf("m2"),
                today.state.value.queued
                    .map { it.id },
            )
        }
}
