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

/** Today's goal and Ask on the side through today's session, with fakes (app-plan.md, Goal and Ask › Testing). */
@OptIn(ExperimentalCoroutinesApi::class)
class TodaySessionGoalTest {
    private val project = "/p"
    private val log = mutableListOf<String>()
    private var date = LocalDate.of(2026, 11, 7)

    private inner class Api :
        DailySessionApi,
        LiveSessionApi {
        var listed = mutableListOf(SessionListing("ses_1", "2026-11-07", project, 1, busySince = 50))
        var busy = false
        var goals = mutableMapOf<String, Goal?>()
        var goalItems = mapOf<String, String?>()
        var commandFails: Exception? = null
        var reply = "## Task\nNow: Working."
        var summaryGate: CompletableDeferred<Unit>? = null
        var askReply: suspend () -> String = { "An answer." }

        override suspend fun sessionsPage(
            directory: String,
            search: String,
            limit: Int,
            cursor: String?,
        ): SessionListingPage {
            log += "list"
            return SessionListingPage(listed.filter { it.title == search }, null)
        }

        override suspend fun create(
            title: String,
            directory: String,
            model: ModelChoice?,
        ) = SessionListing("ses_new", title, directory, 1).also { listed.add(0, it) }

        override suspend fun putMessageRules(sessionId: String) = Unit

        override suspend fun snapshot(rootId: String) =
            LiveSnapshot(
                if (busy) 60 else null,
                hasRun = true,
                children = emptyList(),
                pending = emptyList(),
                inbox = emptyList(),
                goalItems = goalItems,
            )

        override suspend fun summarise(
            sessionId: String,
            prompt: String,
        ): String {
            log += "summary"
            summaryGate?.await()
            return reply
        }

        override suspend fun ask(
            sessionId: String,
            prompt: String,
        ): String {
            log += "ask"
            return askReply()
        }

        override suspend fun send(
            sessionId: String,
            text: String,
            delivery: String,
            images: List<PromptImage>,
        ) = "msg_1"

        override suspend fun cancelQueued(
            sessionId: String,
            itemId: String,
        ) {
            log += "delete $itemId"
        }

        override suspend fun switchModel(
            sessionId: String,
            model: ModelChoice,
        ) = Unit

        override suspend fun goal(
            sessionId: String,
            directory: String,
        ): Goal? {
            log += "get $sessionId"
            return goals[sessionId]
        }

        override suspend fun goalCommand(
            sessionId: String,
            text: String,
            images: List<PromptImage>,
        ) {
            log += "goal \"$text\"" + if (images.isEmpty()) "" else " ${images.map { it.name }}"
            commandFails?.let { throw it }
        }
    }

    private class Memory(
        var last: LocalDate?,
        var lastId: String? = null,
    ) : DailySessionMemory {
        override suspend fun lastOpenedOn() = last

        override suspend fun setLastOpenedOn(date: LocalDate) {
            last = date
        }

        override suspend fun lastSessionId() = lastId

        override suspend fun setLastSessionId(id: String) {
            lastId = id
        }
    }

    private class Transport : EventTransport {
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

        fun connected() = lines.trySend("""{"type":"server.connected","data":{}}""")

        fun end() = lines.trySend(null)
    }

    private val api = Api()
    private val transport = Transport()

    private fun TestScope.open(memory: Memory = Memory(date)): TodaySession {
        val today =
            TodaySession(
                scope = backgroundScope,
                setup = DailySessionSetup(api, memory),
                api = api,
                transport = transport,
                directory = { project },
                model = { null },
                today = { date },
                clock = { testScheduler.currentTime },
            )
        today.open()
        runCurrent()
        transport.connected()
        runCurrent()
        log.clear()
        return today
    }

    private fun goalJson(
        status: String,
        rounds: Int,
        id: String = "g1",
    ) = """{"id":"$id","objective":"Ship it","status":"$status","rounds":$rounds,"maxRounds":50}"""

    private fun goalChanged(
        goal: String?,
        session: String = "ses_1",
    ) = transport.send("rpc.opencode.goal.changed", """{"sessionID":"$session","goal":$goal}""")

    @Test
    fun theGoalIsReadOnOpenAndOnEveryReconnect() =
        runTest {
            api.goals["ses_1"] = Goal("g1", "Ship it", GoalStatus.Active, rounds = 2)
            api.busy = true
            val today = open()
            assertEquals(GoalLine.Phase(GoalPhase.Round(2, 50)), today.state.value.goalLine)
            api.goals["ses_1"] = Goal("g1", "Ship it", GoalStatus.Paused, "Stopped by the user", 2)
            transport.end()
            runCurrent()
            advanceTimeBy(1_300)
            transport.connected()
            runCurrent()
            assertTrue("get ses_1" in log)
            assertEquals(
                GoalStatus.Paused,
                today.state.value.goal
                    ?.status,
            )
            assertEquals(GoalLine.Phase(GoalPhase.Paused), today.state.value.goalLine)
        }

    @Test
    fun noGoalShowsSetTodaysGoalWhileIdleAndNoLineWhileBusy() =
        runTest {
            val today = open()
            assertEquals(GoalLine.Empty, today.state.value.goalLine)
            transport.send("session.execution.started", """{"sessionID":"ses_1"}""")
            runCurrent()
            assertNull(today.state.value.goalLine)
        }

    @Test
    fun theEventReplacesTheGoalANullClearsItAndAnotherSessionsIsIgnored() =
        runTest {
            val today = open()
            goalChanged(goalJson("active", 0))
            runCurrent()
            assertEquals(
                "Ship it",
                today.state.value.goal
                    ?.objective,
            )
            goalChanged(goalJson("complete", 0, id = "other"), session = "ses_other")
            runCurrent()
            assertEquals(
                GoalStatus.Active,
                today.state.value.goal
                    ?.status,
            )
            goalChanged(goalJson("complete", 1))
            runCurrent()
            assertEquals(GoalLine.Phase(GoalPhase.Done), today.state.value.goalLine)
            goalChanged(null)
            runCurrent()
            assertNull(today.state.value.goal)
        }

    @Test
    fun aHigherRoundAsksForOneMidRunRefreshAndTheSameRoundAgainDoesnt() =
        runTest {
            api.busy = true
            val today = open()
            goalChanged(goalJson("active", 0))
            runCurrent()
            assertEquals(0, log.count { it == "summary" })
            api.reply = "## Task\nNow: Two files left.\nNext: Commit it"
            goalChanged(goalJson("active", 1))
            runCurrent()
            assertEquals(1, log.count { it == "summary" })
            // A mid-run card: the reply's Next is dropped.
            assertNull((today.state.value.summary.summary as StatusSummary.Card).next)
            goalChanged(goalJson("active", 1))
            runCurrent()
            assertEquals(1, log.count { it == "summary" })
            // Resume starts again at 1: lower, so no refresh; the round after it is.
            goalChanged(goalJson("paused", 1))
            goalChanged(goalJson("active", 1))
            runCurrent()
            assertEquals(1, log.count { it == "summary" })
            goalChanged(goalJson("active", 2))
            runCurrent()
            assertEquals(2, log.count { it == "summary" })
        }

    @Test
    fun aRoundsRefreshIsDroppedWhileOneRunsAndAnAskStillRunsBesideIt() =
        runTest {
            api.busy = true
            val today = open()
            api.summaryGate = CompletableDeferred()
            today.refresh()
            runCurrent()
            today.ask("Why did the tests fail?")
            goalChanged(goalJson("active", 1))
            runCurrent()
            // The Ask didn't wait for the summary; the round's refresh was dropped.
            assertEquals(listOf("summary", "ask"), log)
            assertEquals(
                AskReply.Answered("An answer."),
                today.state.value.ask
                    ?.reply,
            )
            api.summaryGate?.complete(Unit)
            runCurrent()
            assertEquals(1, log.count { it == "summary" })
        }

    @Test
    fun eachCommandSendsItsText() =
        runTest {
            val today = open()
            assertEquals(GoalOutcome.Done, today.goal(GoalCommand.Set("Ship the login screen")))
            assertEquals(GoalOutcome.Done, today.goal(GoalCommand.Pause))
            assertEquals(GoalOutcome.Done, today.goal(GoalCommand.Resume))
            assertEquals(GoalOutcome.Done, today.goal(GoalCommand.Clear))
            assertEquals(listOf("goal \"Ship the login screen\"", "goal \"pause\"", "goal \"resume\"", "goal \"clear\""), log)
        }

    @Test
    fun imagesGoWithTheGoalAsFiles() =
        runTest {
            val today = open()
            today.goal(GoalCommand.Set("Match this design"), listOf(PromptImage("Photo 1.jpg", java.io.File("x"))))
            assertEquals(listOf("goal \"Match this design\" [Photo 1.jpg]"), log)
        }

    @Test
    fun aStaleCommandReadsTheGoalAgain() =
        runTest {
            val today = open()
            goalChanged(goalJson("active", 3))
            runCurrent()
            log.clear()
            api.goals["ses_1"] = Goal("g1", "Ship it", GoalStatus.Complete, "Verified", 3)
            api.commandFails = GoalChangedException("No active goal to pause.")
            assertEquals(GoalOutcome.Stale, today.goal(GoalCommand.Pause))
            assertEquals(listOf("goal \"pause\"", "get ses_1"), log)
            runCurrent()
            assertEquals(
                GoalStatus.Complete,
                today.state.value.goal
                    ?.status,
            )
            api.commandFails = IllegalStateException("HTTP 502")
            assertEquals(GoalOutcome.Failed, today.goal(GoalCommand.Clear))
        }

    @Test
    fun withoutAnEventWithinTwoSecondsTheGoalIsRead() =
        runTest {
            val today = open()
            api.goals["ses_1"] = Goal("g1", "Ship it", GoalStatus.Active)
            today.goal(GoalCommand.Set("Ship it"))
            advanceTimeBy(TodaySession.GOAL_SETTLE_MS - 100)
            runCurrent()
            assertFalse("get ses_1" in log)
            advanceTimeBy(200)
            runCurrent()
            assertTrue("get ses_1" in log)
            assertEquals(
                "Ship it",
                today.state.value.goal
                    ?.objective,
            )
            // With the event, nothing more is read.
            log.clear()
            today.goal(GoalCommand.Pause)
            goalChanged(goalJson("paused", 0))
            runCurrent()
            advanceTimeBy(TodaySession.GOAL_SETTLE_MS + 100)
            runCurrent()
            assertEquals(listOf("goal \"pause\""), log)
        }

    private fun enqueueKickoff(
        id: String,
        goal: String,
        type: String = "synthetic",
    ) = transport.send(
        "session.inbox.enqueued",
        """{"sessionID":"ses_1","inboxID":"$id","item":{"type":"$type","delivery":"steer",""" +
            """"payload":{"text":"A goal is now active for this session.","description":"Goal: Ship it",""" +
            """"metadata":{"source":"goal","goalID":"$goal"}}}}""",
    )

    @Test
    fun clearAndReplaceTakeBackTheOldKickoffWhileItWaitsAndNothingOnceDelivered() =
        runTest {
            api.busy = true
            val today = open()
            goalChanged(goalJson("active", 0, id = "g1"))
            enqueueKickoff("msg_k1", "g1")
            runCurrent()
            // Replace: the old kickoff goes, the new one stays.
            today.goal(GoalCommand.Set("Something else"))
            goalChanged(goalJson("active", 0, id = "g2"))
            enqueueKickoff("msg_k2", "g2")
            runCurrent()
            assertEquals(listOf("goal \"Something else\"", "delete msg_k1"), log)
            // Delivered: Clear has nothing to take back.
            log.clear()
            transport.send("session.inbox.delivered", """{"sessionID":"ses_1","inboxID":"msg_k2"}""")
            runCurrent()
            today.goal(GoalCommand.Clear)
            assertEquals(listOf("goal \"clear\""), log)
        }

    @Test
    fun aKickoffReadBackOnReconnectIsTakenBackToo() =
        runTest {
            api.goals["ses_1"] = Goal("g1", "Ship it", GoalStatus.Active)
            api.goalItems = mapOf("msg_k1" to "g1", "msg_c" to null)
            val today = open()
            today.goal(GoalCommand.Clear)
            assertEquals(listOf("goal \"clear\"", "delete msg_k1"), log)
        }

    @Test
    fun theGoalsItemsNeverWaitAndNeverSetTheYouLine() =
        runTest {
            api.busy = true
            val today = open()
            enqueueKickoff("msg_k", "g1")
            enqueueKickoff("msg_f", "g1", type = "user")
            transport.send(
                "session.inbox.enqueued",
                """{"sessionID":"ses_1","inboxID":"msg_c","item":{"type":"synthetic","delivery":"steer",""" +
                    """"payload":{"text":"Continue working toward the active goal.","metadata":{"source":"goal"}}}}""",
            )
            runCurrent()
            assertEquals(0, today.state.value.waiting)
            assertEquals(emptyList<InboxEntry>(), today.state.value.inbox)
            for (id in listOf("msg_k", "msg_f", "msg_c")) {
                transport.send("session.inbox.delivered", """{"sessionID":"ses_1","inboxID":"$id"}""")
            }
            runCurrent()
            assertNull(today.state.value.you)
        }

    @Test
    fun aGoalSetFromThePhoneShowsAsGoalUntilTheCardAfterItsRun() =
        runTest {
            val today = open()
            today.goal(GoalCommand.Set("Ship the login screen"))
            enqueueKickoff("msg_k", "g1")
            transport.send("session.inbox.delivered", """{"sessionID":"ses_1","inboxID":"msg_k"}""")
            transport.send("session.execution.started", """{"sessionID":"ses_1"}""")
            runCurrent()
            assertEquals("Ship the login screen", today.state.value.you)
            assertTrue(today.state.value.youGoal)
            // A message after it is a plain You line again.
            val id = today.send("Also the logout", "queue")
            transport.send("session.inbox.delivered", """{"sessionID":"ses_1","inboxID":"$id"}""")
            runCurrent()
            assertEquals("Also the logout", today.state.value.you)
            assertFalse(today.state.value.youGoal)
        }

    @Test
    fun aGoalThatFailedToSetLeavesNoGoalLine() =
        runTest {
            val today = open()
            api.commandFails = IllegalStateException("HTTP 502")
            today.goal(GoalCommand.Set("Ship it"))
            enqueueKickoff("msg_other", "gX")
            transport.send("session.inbox.delivered", """{"sessionID":"ses_1","inboxID":"msg_other"}""")
            runCurrent()
            assertNull(today.state.value.you)
        }

    @Test
    fun aNewDayWithYesterdaysGoalStillActiveSaysItKeepsRunning() =
        runTest {
            date = LocalDate.of(2026, 11, 8)
            api.goals["ses_1"] = Goal("g1", "Ship it", GoalStatus.Active, rounds = 4)
            val today = open(Memory(LocalDate.of(2026, 11, 7), "ses_1"))
            assertTrue(today.state.value.newDayNotice)
            assertTrue(today.state.value.newDayGoal)
            // Yesterday's session keeps its goal; today's starts with none.
            assertNull(today.state.value.goal)
        }

    @Test
    fun aNewDayWithYesterdaysGoalPausedSaysNothingMore() =
        runTest {
            date = LocalDate.of(2026, 11, 8)
            api.goals["ses_1"] = Goal("g1", "Ship it", GoalStatus.Paused, rounds = 4)
            val today = open(Memory(LocalDate.of(2026, 11, 7), "ses_1"))
            assertTrue(today.state.value.newDayNotice)
            assertFalse(today.state.value.newDayGoal)
        }

    @Test
    fun anAskForAGoneSessionRunsSetupAgain() =
        runTest {
            val today = open()
            api.askReply = { throw SessionGoneException() }
            today.ask("Why?")
            runCurrent()
            assertNull(today.state.value.ask)
            assertEquals(SessionSetup.Ready("ses_1"), today.state.value.setup)
            assertEquals(listOf("ask", "list"), log.take(2))
        }
}
