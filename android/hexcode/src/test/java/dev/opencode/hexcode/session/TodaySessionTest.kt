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
    ) = TodaySession(
        scope = backgroundScope,
        setup = DailySessionSetup(api, memory),
        api = api,
        transport = transport,
        directory = { project },
        model = { model },
        today = { date },
        clock = { testScheduler.currentTime },
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
    fun aSessionThatRanIsSummarisedOnOpenAndTheStateReadOnConnect() =
        runTest {
            val api = Api(mutableListOf(SessionListing("s1", "2026-11-05", project, 1, busySince = 50)))
            api.snapshot = api.snapshot.copy(busySince = 50, inbox = listOf(InboxEntry("m1", "queue"), InboxEntry("m2", "steer")))
            val transport = Transport()
            val today = session(api, transport, Memory(date))
            today.open()
            runCurrent()
            transport.connected()
            runCurrent()
            assertEquals(listOf("list", "rules s1", "summary s1", "stream", "snapshot s1"), log)
            assertEquals(AgentStatus.Working(50), today.state.value.agent)
            assertEquals(1, today.state.value.waiting)
            assertFalse(today.state.value.newDayNotice)
        }

    @Test
    fun aRunStartingAndEndingRefreshesTheSummaryOnce() =
        runTest {
            val api = Api()
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
    fun droppingTheStreamHoldsItDownFor5s() =
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
            advanceTimeBy(4_900)
            runCurrent()
            assertEquals(1, transport.attempts)
            advanceTimeBy(200)
            runCurrent()
            assertEquals(2, transport.attempts)
        }
}
