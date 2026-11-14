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
import kotlinx.serialization.json.JsonObject
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
        var signedOut = false
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
            if (signedOut) throw SignedOutException("HTTP 401")
            return SessionListingPage(listed.filter { it.title.contains(search) }, null)
        }

        override suspend fun create(
            title: String,
            directory: String,
            model: ModelChoice?,
        ): SessionListing {
            log += "create $title"
            if (createFails) error("HTTP 500")
            return SessionListing("new${next++}", title, directory, 1).also { listed.add(0, it) }
        }

        var createFails = false
        var rulesFail = false

        override suspend fun putMessageRules(sessionId: String) {
            log += "rules $sessionId"
            if (rulesFail) error("HTTP 500")
        }

        var snapshotFails = false

        override suspend fun snapshot(rootId: String): LiveSnapshot {
            log += "snapshot $rootId"
            if (snapshotFails) error("HTTP 500")
            return snapshot.copy(updatedAt = updated)
        }

        override suspend fun summarise(
            sessionId: String,
            prompt: String,
        ): String {
            log += "summary $sessionId"
            return reply
        }

        /** The session's `time.updated`, read before each request and on every read-back. */
        var updated: Long? = 100

        override suspend fun updatedAt(sessionId: String): Long? = updated

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
            images: List<PromptImage>,
            metadata: JsonObject?,
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

        var lastId: String? = null

        override suspend fun lastSessionId() = lastId

        override suspend fun setLastSessionId(id: String) {
            lastId = id
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
        beforeSetup: suspend () -> Unit = {},
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
        beforeSetup = beforeSetup,
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

    // app-plan.md, Home › Cold start.

    @Test
    fun untilTheFirstReadBackTheTopBarSaysConnecting() =
        runTest {
            val api = Api().apply { gate = CompletableDeferred() }
            val transport = Transport()
            val today = session(api, transport)
            today.open()
            runCurrent()
            assertEquals(SessionSetup.Opening, today.state.value.setup)
            assertEquals(AgentStatus.Connecting, today.state.value.agent)
            api.gate?.complete(Unit)
            runCurrent()
            // Found, the stream opening: still no read-back.
            assertEquals(SessionSetup.Ready("new1"), today.state.value.setup)
            assertEquals(AgentStatus.Connecting, today.state.value.agent)
            transport.connected()
            runCurrent()
            assertEquals(AgentStatus.Idle, today.state.value.agent)
        }

    @Test
    fun aFailedReadBackStillEndsConnecting() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50))).apply { snapshotFails = true }
            val transport = Transport()
            val today = session(api, transport, Memory(date))
            today.open()
            runCurrent()
            transport.connected()
            runCurrent()
            assertEquals(AgentStatus.Idle, today.state.value.agent)
        }

    @Test
    fun theStreamDownBeforeTheFirstReadBackIsReconnecting() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            val transport = Transport().apply { refuse = true }
            val today = session(api, transport, Memory(date))
            today.open()
            runCurrent()
            assertEquals(AgentStatus.Reconnecting, today.state.value.agent)
        }

    @Test
    fun theSavedCardForTodaysSavedSessionShowsBeforeAnythingReachesTheNetwork() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            val saved = SavedSummary("s1", StatusSummary.Fallback(listOf(NowLine("Earlier."))), -300_000, "2026-11-05", project)
            val memory = Memory(date).apply { lastId = "s1" }
            // The sign-in renewal and the version read wait for the saved server, which binds after the first frame.
            val connection = CompletableDeferred<Unit>()
            val today = session(api, Transport(), memory, Store(saved), beforeSetup = { connection.await() })
            assertFalse(today.state.value.restored)
            today.open()
            runCurrent()
            assertTrue(today.state.value.restored)
            assertEquals(saved.summary, today.state.value.summary.summary)
            assertEquals(AgentStatus.Connecting, today.state.value.agent)
            assertEquals(emptyList<String>(), log)
            connection.complete(Unit)
            runCurrent()
            assertEquals(SessionSetup.Ready("s1"), today.state.value.setup)
            assertEquals(saved.summary, today.state.value.summary.summary)
        }

    @Test
    fun withNoSavedCardTheSavedStateIsReadAtOnceAndTheCardAreaStaysEmpty() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            val connection = CompletableDeferred<Unit>()
            val today = session(api, Transport(), Memory(date), Store(), beforeSetup = { connection.await() })
            today.open()
            runCurrent()
            assertTrue(today.state.value.restored)
            assertNull(today.state.value.summary.summary)
            assertEquals(emptyList<String>(), log)
        }

    @Test
    fun aCardSavedForAnotherSessionThanTodaysSavedOneWaitsUntilTodaysSessionIsKnown() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            api.gate = CompletableDeferred()
            val saved = SavedSummary("s1", StatusSummary.Fallback(listOf(NowLine("Earlier."))), 0, "2026-11-05", project)
            // Start a new session opened s2 after this card was saved.
            val memory = Memory(date).apply { lastId = "s2" }
            val today = session(api, Transport(), memory, Store(saved))
            today.open()
            runCurrent()
            assertTrue(today.state.value.restored)
            assertNull(today.state.value.summary.summary)
            api.gate?.complete(Unit)
            runCurrent()
            // Today's session turned out to be s1 after all: its card shows now (and fades in).
            assertEquals(SessionSetup.Ready("s1"), today.state.value.setup)
            assertEquals(saved.summary, today.state.value.summary.summary)
        }

    @Test
    fun setupFailureLeavesTheTopBarStateEmpty() =
        runTest {
            val api = Api().apply { fails = true }
            val today = session(api, Transport())
            today.open()
            runCurrent()
            assertEquals(SessionSetup.Failed(), today.state.value.setup)
            assertNull(today.state.value.agent)
        }

    @Test
    fun aBusySessionAsksForNoSummaryOnConnect() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            api.snapshot = api.snapshot.copy(busySince = 50, inbox = listOf(InboxEntry("m1", "queue"), InboxEntry("m2", "steer")))
            val transport = Transport()
            val today = session(api, transport, Memory(date))
            today.open()
            runCurrent()
            transport.connected()
            runCurrent()
            // The read-back says the agent is working: no request; the run's end refreshes.
            assertEquals(listOf("list", "rules s1", "stream", "snapshot s1"), log)
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
            assertEquals("Working.", (today.state.value.summary.summary as StatusSummary.Card).nowText)
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
            assertEquals(
                PendingItem("per_1", "new1", PendingItem.Kind.Approval, "ls"),
                today.state.value.pendingInput
                    ?.copy(approval = null),
            )
            transport.send(event("permission.replied", """{"sessionID":"new1","requestID":"per_1","reply":"once"}"""))
            runCurrent()
            assertNull(today.state.value.pendingInput)
        }

    @Test
    fun aDroppedStreamShowsReconnectingThenReadsTheStateAgain() =
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
            // Nothing new since the card (time.updated didn't move): no request.
            assertEquals(listOf("stream", "snapshot s1"), log)
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
            assertEquals("Done.", (today.state.value.summary.summary as StatusSummary.Card).nowText)
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
            // Busy: no request at all, so no empty reply to mistake for a failure.
            assertEquals(listOf("list", "rules s1", "stream", "snapshot s1"), log)
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
            val store = Store(SavedSummary("s1", StatusSummary.Fallback(listOf(NowLine("Earlier."))), updatedAt = 0))
            val transport = Transport()
            val today = session(api, transport, Memory(date), store)
            today.open()
            runCurrent()
            assertEquals(StatusSummary.Fallback(listOf(NowLine("Earlier."))), today.state.value.summary.summary)
            transport.connected()
            runCurrent()
            val card = StatusSummary.parse("## Task\nNow: Working.")
            assertEquals(card, today.state.value.summary.summary)
            assertEquals(
                SavedSummary("s1", card, testScheduler.currentTime, "2026-11-05", project, basis = 100, madeWhileBusy = false),
                store.saved,
            )
        }

    @Test
    fun aCardSavedForAnotherSessionIsNotShown() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            val store = Store(SavedSummary("s0", StatusSummary.Fallback(listOf(NowLine("Yesterday."))), updatedAt = 0))
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

    @Test
    fun aCardSavedForTodayInThisProjectShowsBeforeSetupFindsTheSession() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            api.gate = CompletableDeferred()
            val saved = SavedSummary("s1", StatusSummary.Fallback(listOf(NowLine("Earlier."))), -300_000, "2026-11-05", project)
            val today = session(api, Transport(), Memory(date), Store(saved))
            today.open()
            runCurrent()
            assertEquals(SessionSetup.Opening, today.state.value.setup)
            assertEquals(saved.summary, today.state.value.summary.summary)
            assertEquals(
                RefreshLabel.MinutesAgo(5),
                today.state.value.summary
                    .label(0),
            )
            api.gate?.complete(Unit)
            runCurrent()
            // Setup found that session: the card stays.
            assertEquals(SessionSetup.Ready("s1"), today.state.value.setup)
            assertEquals(saved.summary, today.state.value.summary.summary)
        }

    @Test
    fun aCardFromYesterdayOrAnotherProjectDoesNotShowBeforeSetup() =
        runTest {
            for (saved in listOf(
                SavedSummary("s0", StatusSummary.Fallback(listOf(NowLine("Yesterday."))), 0, "2026-11-04", project),
                SavedSummary("s9", StatusSummary.Fallback(listOf(NowLine("Elsewhere."))), 0, "2026-11-05", "/Users/me/Projects/other"),
            )) {
                val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
                api.gate = CompletableDeferred()
                val today = session(api, Transport(), Memory(date), Store(saved))
                today.open()
                runCurrent()
                assertNull(today.state.value.summary.summary)
                api.gate?.complete(Unit)
                runCurrent()
                assertNull(today.state.value.summary.summary)
            }
        }

    @Test
    fun aCardShownBeforeSetupGoesWhenSetupFindsADifferentSession() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            api.gate = CompletableDeferred()
            val saved = SavedSummary("s_old", StatusSummary.Fallback(listOf(NowLine("Another session."))), 0, "2026-11-05", project)
            val today = session(api, Transport(), Memory(date), Store(saved))
            today.open()
            runCurrent()
            assertEquals(saved.summary, today.state.value.summary.summary)
            api.gate?.complete(Unit)
            runCurrent()
            assertEquals(SessionSetup.Ready("s1"), today.state.value.setup)
            assertNull(today.state.value.summary.summary)
        }

    @Test
    fun nothingNewSinceTheSavedCardMakesNoRequest() =
        runTest {
            val store = Store()
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            // The first launch asks, and saves the card with its basis.
            val first = Transport()
            session(api, first, Memory(date), store).open()
            runCurrent()
            first.connected()
            runCurrent()
            assertEquals(1, log.count { it.startsWith("summary") })
            assertEquals(100L, store.saved?.basis)
            assertFalse(store.saved!!.madeWhileBusy)
            // The second launch finds nothing new: no request, the saved card shows.
            log.clear()
            val second = Transport()
            val today = session(api, second, Memory(date), store)
            today.open()
            runCurrent()
            second.connected()
            runCurrent()
            assertEquals(0, log.count { it.startsWith("summary") })
            assertEquals(store.saved?.summary, today.state.value.summary.summary)
            // Something new (a message moved time.updated): the next connect asks.
            api.updated = 200
            second.end()
            runCurrent()
            advanceTimeBy(1_300)
            second.connected()
            runCurrent()
            assertEquals(1, log.count { it.startsWith("summary") })
            // ↻ always asks.
            today.refresh()
            runCurrent()
            assertEquals(2, log.count { it.startsWith("summary") })
        }

    @Test
    fun aFailedReplyNeverMovesTheBasis() =
        runTest {
            val store = Store()
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            val transport = Transport()
            val today = session(api, transport, Memory(date), store)
            today.open()
            runCurrent()
            transport.connected()
            runCurrent()
            api.updated = 300
            api.reply = ""
            today.refresh()
            runCurrent()
            assertTrue(today.state.value.summary.failed)
            assertEquals(100L, store.saved?.basis)
        }

    @Test
    fun theAppsOwnModelSwitchMovesTheBasis() =
        runTest {
            val store = Store()
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            val transport = Transport()
            val today = session(api, transport, Memory(date), store)
            today.open()
            runCurrent()
            transport.connected()
            runCurrent()
            api.updated = 150
            today.switchModel(ModelChoice("opencode", "beam-free"))
            runCurrent()
            assertEquals(150L, store.saved?.basis)
        }

    @Test
    fun aDeliveredMessageShowsAsYouUntilTheCardAfterItsRun() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            val transport = Transport()
            val today = session(api, transport, Memory(date))
            today.open()
            runCurrent()
            transport.connected()
            runCurrent()
            transport.send(
                """{"type":"session.inbox.enqueued","data":{"sessionID":"s1","inboxID":"m1",""" +
                    """"item":{"type":"user","payload":{"text":"Also add a test"},"delivery":"steer"}}}""",
            )
            transport.send("""{"type":"session.inbox.delivered","data":{"sessionID":"s1","inboxID":"m1"}}""")
            transport.send(event("session.execution.started", """{"sessionID":"s1"}""", created = 60))
            runCurrent()
            assertEquals("Also add a test", today.state.value.you)
            // ↻ mid-run: a new card, which keeps the You line and drops the reply's Next.
            api.reply = "## Task\nNow: Adding the test.\nNext: Run it"
            today.refresh()
            runCurrent()
            assertEquals("Also add a test", today.state.value.you)
            assertNull((today.state.value.summary.summary as StatusSummary.Card).next)
            // The run ends: the card made then replaces the You line.
            api.reply = "## Task\nNow: Test added; 5 pass.\nNext: Commit it"
            transport.send(event("session.execution.succeeded", """{"sessionID":"s1"}"""))
            runCurrent()
            assertNull(today.state.value.you)
            assertEquals("Commit it", (today.state.value.summary.summary as StatusSummary.Card).next)
        }

    @Test
    fun theYouLineUsesWhatThePhoneSent() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            val transport = Transport()
            val today = session(api, transport, Memory(date))
            today.open()
            runCurrent()
            transport.connected()
            runCurrent()
            val id = today.send("Rename it", "steer")
            transport.send("""{"type":"session.inbox.delivered","data":{"sessionID":"s1","inboxID":"$id"}}""")
            runCurrent()
            assertEquals("Rename it", today.state.value.you)
        }

    @Test
    fun theYouLineKnowsTheImagesAndFilesThatWentWithIt() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            val transport = Transport()
            val today = session(api, transport, Memory(date))
            today.open()
            runCurrent()
            transport.connected()
            runCurrent()
            transport.send(
                """{"type":"session.inbox.enqueued","data":{"sessionID":"s1","inboxID":"m1","item":{"type":"user",""" +
                    """"payload":{"text":"Look\nAttached file: `/t/uploads/u1/report.pdf`","files":[{"name":"Photo 1.jpg",""" +
                    """"mime":"image/jpeg","data":"AAAA"}]},"delivery":"steer"}}}""",
            )
            transport.send("""{"type":"session.inbox.delivered","data":{"sessionID":"s1","inboxID":"m1"}}""")
            runCurrent()
            assertEquals("Look\nAttached file: `/t/uploads/u1/report.pdf`", today.state.value.you)
            assertEquals(listOf("Photo 1.jpg", "report.pdf"), today.state.value.youWith)
        }

    @Test
    fun theYouLineOfWhatThePhoneSentKnowsItsImages() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            val transport = Transport()
            val today = session(api, transport, Memory(date))
            today.open()
            runCurrent()
            transport.connected()
            runCurrent()
            val id = today.send("Look\nAttached file: `/t/a.zip`", "steer", listOf(PromptImage("Photo 1.jpg", java.io.File("/x.jpg"))))
            transport.send("""{"type":"session.inbox.delivered","data":{"sessionID":"s1","inboxID":"$id"}}""")
            runCurrent()
            assertEquals(listOf("Photo 1.jpg", "a.zip"), today.state.value.youWith)
        }

    @Test
    fun aFailedReadBackOnReconnectAsksForTheSummary() =
        runTest {
            val store = Store()
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            val transport = Transport()
            session(api, transport, Memory(date), store).open()
            runCurrent()
            transport.connected()
            runCurrent()
            assertEquals(1, log.count { it.startsWith("summary") })
            // The card is current, but without the read-back nothing says so.
            api.snapshotFails = true
            transport.end()
            runCurrent()
            advanceTimeBy(1_300)
            transport.connected()
            runCurrent()
            assertEquals(2, log.count { it.startsWith("summary") })
        }

    // app-plan.md, Today's session on screen › Empty session: never "Nothing running yet" once a message went.

    @Test
    fun anEmptySessionSaysWorkingOnYourMessageOnceAMessageIsSent() =
        runTest {
            val api = Api()
            api.snapshot = api.snapshot.copy(hasRun = false)
            val transport = Transport()
            val today = session(api, transport, Memory(date))
            today.open()
            runCurrent()
            transport.connected()
            runCurrent()
            assertEquals(StatusSummary.Empty("Thursday 5 November"), today.state.value.summary.summary)
            today.send("Make a quiz", "steer")
            runCurrent()
            assertEquals(StatusSummary.Empty("Thursday 5 November", started = true), today.state.value.summary.summary)
        }

    @Test
    fun anEmptySessionThatStartsRunningSaysWorkingOnYourMessage() =
        runTest {
            val api = Api()
            api.snapshot = api.snapshot.copy(hasRun = false)
            val transport = Transport()
            val today = session(api, transport, Memory(date))
            today.open()
            runCurrent()
            transport.connected()
            transport.send(event("session.execution.started", """{"sessionID":"new1"}""", created = 1_000))
            runCurrent()
            assertEquals(StatusSummary.Empty("Thursday 5 November", started = true), today.state.value.summary.summary)
        }

    @Test
    fun anEmptyCardReadBackAsBusyOnReconnectSaysWorkingOnYourMessage() =
        runTest {
            val api = Api()
            api.snapshot = api.snapshot.copy(hasRun = false)
            val transport = Transport()
            val today = session(api, transport, Memory(date))
            today.open()
            runCurrent()
            transport.connected()
            runCurrent()
            api.snapshot = api.snapshot.copy(busySince = 50, hasRun = true)
            transport.end()
            runCurrent()
            advanceTimeBy(1_300)
            transport.connected()
            runCurrent()
            assertEquals(StatusSummary.Empty("Thursday 5 November", started = true), today.state.value.summary.summary)
            assertEquals(0, log.count { it.startsWith("summary") })
        }

    // app-plan.md, Settings and finish (M6) › Start a new session.

    @Test
    fun aNewSessionIsTheNextNumberStartsEmptyAndTheStreamFollowsIt() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            api.snapshot = api.snapshot.copy(busySince = 50, inbox = listOf(InboxEntry("m1", "queue")))
            val memory = Memory(date)
            val transport = Transport()
            val today = session(api, transport, memory)
            today.open()
            runCurrent()
            transport.connected()
            runCurrent()
            transport.send("""{"type":"session.inbox.delivered","data":{"sessionID":"s1","inboxID":"m0"}}""")
            assertEquals(1, today.state.value.waiting)
            log.clear()
            api.snapshot =
                LiveSnapshot(busySince = null, hasRun = false, children = emptyList(), pending = emptyList(), inbox = emptyList())
            assertEquals(NewSessionOutcome.Started, today.startNew())
            runCurrent()
            assertEquals(listOf("list", "create 2026-11-05 (2)", "rules new1", "stream"), log)
            transport.connected()
            runCurrent()
            val state = today.state.value
            assertEquals(SessionSetup.Ready("new1"), state.setup)
            assertEquals(0, state.waiting)
            assertNull(state.you)
            assertNull(state.busySince)
            assertFalse(state.newDayNotice)
            assertEquals(StatusSummary.Empty("Thursday 5 November"), state.summary.summary)
            assertEquals("snapshot new1", log.last())
            assertEquals("new1", memory.lastId)
            assertEquals(date, memory.last)
            // The old session is never touched: no interrupt, no inbox cancel.
            assertFalse(log.any { it.contains("s1") })
        }

    @Test
    fun creatingANewSessionFailsAndTodaysStaysOpen() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            val transport = Transport()
            val today = session(api, transport, Memory(date))
            today.open()
            runCurrent()
            api.createFails = true
            assertEquals(NewSessionOutcome.Failed, today.startNew())
            assertEquals(SessionSetup.Ready("s1"), today.state.value.setup)
        }

    @Test
    fun theNewSessionsRulesFailingIsASetupFailureAndTryAgainMakesNoThird() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            val transport = Transport()
            val today = session(api, transport, Memory(date))
            today.open()
            runCurrent()
            api.rulesFail = true
            assertEquals(NewSessionOutcome.SetupFailed, today.startNew())
            runCurrent()
            assertEquals(SessionSetup.Failed(), today.state.value.setup)
            api.rulesFail = false
            log.clear()
            today.retry()
            runCurrent()
            assertEquals(SessionSetup.Ready("new1"), today.state.value.setup)
            assertFalse(log.any { it.startsWith("create") })
        }

    // app-plan.md, Settings and finish (M6) › Signed out.

    @Test
    fun aRefusedSignInShowsSignedOutAndOpeningAgainAfterPairingClearsIt() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50))).apply { signedOut = true }
            var upkeep = 0
            val today =
                TodaySession(
                    scope = backgroundScope,
                    setup = DailySessionSetup(api, Memory(date)),
                    api = api,
                    transport = Transport(),
                    directory = { project },
                    model = { model },
                    today = { date },
                    clock = { testScheduler.currentTime },
                    beforeSetup = { upkeep += 1 },
                )
            today.open()
            runCurrent()
            assertEquals(SessionSetup.SignedOut, today.state.value.setup)
            assertNull(today.state.value.agent)
            // Pair again replaced the token: setup runs again, after its upkeep.
            api.signedOut = false
            today.open()
            runCurrent()
            assertEquals(SessionSetup.Ready("s1"), today.state.value.setup)
            assertEquals(2, upkeep)
        }
}
