package dev.opencode.hexcode.session

import dev.opencode.hexcode.firstrun.ModelChoice
import dev.opencode.mobile.data.remote.SseAttemptOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/** Approvals, questions and Stop through today's session, with fakes (app-plan.md, Needs you and control › Testing). */
@OptIn(ExperimentalCoroutinesApi::class)
class TodaySessionNeedsTest {
    private val log = mutableListOf<String>()
    private val date = LocalDate.of(2026, 11, 6)

    private inner class Api :
        DailySessionApi,
        LiveSessionApi {
        var snapshot = LiveSnapshot(busySince = 1, hasRun = true, children = emptyList(), pending = emptyList(), inbox = emptyList())
        var gone = false
        var fails = false
        var interrupted = mutableMapOf<String, Boolean>()
        var active = emptySet<String>()

        override suspend fun sessionsPage(
            directory: String,
            search: String,
            limit: Int,
            cursor: String?,
        ) = SessionListingPage(listOf(SessionListing("ses_root", search, directory, 1, busySince = 1)), null)

        override suspend fun create(
            title: String,
            directory: String,
            model: ModelChoice?,
        ) = error("not used")

        override suspend fun putMessageRules(sessionId: String) = Unit

        override suspend fun snapshot(rootId: String) = snapshot

        override suspend fun summarise(
            sessionId: String,
            prompt: String,
        ) = "## Task\nNow: Working."

        override suspend fun switchModel(
            sessionId: String,
            model: ModelChoice,
        ) = Unit

        override suspend fun send(
            sessionId: String,
            text: String,
            delivery: String,
            images: List<PromptImage>,
        ) = "msg_1"

        override suspend fun cancelQueued(
            sessionId: String,
            itemId: String,
        ) = Unit

        override suspend fun replyPermission(
            sessionId: String,
            requestId: String,
            decision: ApprovalDecision,
            message: String?,
        ) {
            log += "reply $sessionId/$requestId ${decision.wire}${message?.let { " \"$it\"" }.orEmpty()}"
            if (gone) throw RequestGoneException("404")
            if (fails) error("HTTP 500")
        }

        override suspend fun replyForm(
            sessionId: String,
            formId: String,
            answer: JsonObject,
        ) {
            log += "answer $sessionId/$formId $answer"
            if (gone) throw RequestGoneException("409 FormAlreadySettledError")
        }

        override suspend fun dismissForm(
            sessionId: String,
            formId: String,
            message: String,
        ) {
            log += "dismiss $sessionId/$formId \"$message\""
        }

        override suspend fun interrupt(sessionId: String): Boolean {
            log += "interrupt $sessionId"
            if (fails) error("unreachable")
            return interrupted[sessionId] ?: true
        }

        override suspend fun activeSessions(): Set<String> {
            log += "active"
            return active
        }
    }

    private class Memory : DailySessionMemory {
        override suspend fun lastOpenedOn(): LocalDate? = null

        override suspend fun setLastOpenedOn(date: LocalDate) = Unit
    }

    private class Transport : EventTransport {
        val lines = Channel<String>(Channel.UNLIMITED)

        override suspend fun connect(
            onActivity: () -> Unit,
            onLine: suspend (String) -> Unit,
        ): SseAttemptOutcome {
            while (true) {
                val line = lines.receive()
                onActivity()
                onLine(line)
            }
        }

        fun send(
            type: String,
            data: String,
        ) = lines.trySend("""{"id":"evt","created":5,"type":"$type","data":$data}""")
    }

    private fun TestScope.open(
        api: Api,
        transport: Transport = Transport(),
    ): Pair<TodaySession, Transport> {
        val session =
            TodaySession(
                scope = backgroundScope,
                setup = DailySessionSetup(api, Memory()),
                api = api,
                transport = transport,
                directory = { "/tmp/p" },
                model = { null },
                today = { date },
                clock = { testScheduler.currentTime },
            )
        session.open()
        transport.lines.trySend("""{"id":"evt_0","type":"server.connected","data":{}}""")
        runCurrent()
        return session to transport
    }

    private fun approval(
        id: String,
        session: String = "ses_root",
    ) = PendingItem(id, session, PendingItem.Kind.Approval, "ls", approval = ApprovalRequest("shell", listOf("ls"), listOf("ls *")))

    private val question = PendingItem("frm_1", "ses_root", PendingItem.Kind.Question, "Which?", form = QuestionForm("Questions"))

    @Test
    fun onceAlwaysAndDenyEachSendTheirBodyOnTheRequestsOwnRoute() =
        runTest {
            val api = Api()
            api.snapshot =
                api.snapshot.copy(children = listOf("ses_child"), pending = listOf(approval("per_1"), approval("per_2", "ses_child")))
            val (today, _) = open(api)

            today.reply(approval("per_1"), ApprovalDecision.Once)
            today.reply(approval("per_2", "ses_child"), ApprovalDecision.Always)
            today.reply(approval("per_3"), ApprovalDecision.Reject)
            today.reply(approval("per_4"), ApprovalDecision.Reject, "Use printf instead of echo.")

            assertEquals(
                listOf(
                    "reply ses_root/per_1 once",
                    "reply ses_child/per_2 always",
                    "reply ses_root/per_3 reject \"${SessionPrompts.DENY_NOTE}\"",
                    "reply ses_root/per_4 reject \"Use printf instead of echo.\"",
                ),
                log,
            )
            // Each answered item left the queue at once.
            runCurrent()
            assertTrue(
                today.state.value.pending
                    .isEmpty(),
            )
        }

    @Test
    fun aRequestThatIsGoneLeavesTheQueueAndSaysSo() =
        runTest {
            val api = Api().apply { gone = true }
            api.snapshot = api.snapshot.copy(pending = listOf(approval("per_1"), approval("per_2")))
            val (today, _) = open(api)

            try {
                today.reply(approval("per_1"), ApprovalDecision.Once)
                fail("a 404 is answered elsewhere")
            } catch (_: RequestGoneException) {
            }

            runCurrent()
            assertEquals(
                listOf("per_2"),
                today.state.value.pending
                    .map { it.id },
            )
        }

    @Test
    fun aFailedReplyKeepsTheItem() =
        runTest {
            val api = Api().apply { fails = true }
            api.snapshot = api.snapshot.copy(pending = listOf(approval("per_1")))
            val (today, _) = open(api)

            val error = runCatching { today.reply(approval("per_1"), ApprovalDecision.Once) }.exceptionOrNull()

            assertTrue(error !is RequestGoneException && error != null)
            runCurrent()
            assertEquals(
                listOf("per_1"),
                today.state.value.pending
                    .map { it.id },
            )
        }

    @Test
    fun questionsAreAnsweredOrDismissedWithTheNoteAndA409CountsAsAnswered() =
        runTest {
            val api = Api()
            api.snapshot = api.snapshot.copy(pending = listOf(question))
            val (today, _) = open(api)
            val answer = Json.parseToJsonElement("""{"q0":"Red","q1":["Apple","Kiwi"]}""").jsonObject

            today.answer(question, answer)
            today.dismiss(question)
            api.gone = true
            val gone = runCatching { today.answer(question, answer) }.exceptionOrNull()

            assertEquals(
                listOf(
                    "answer ses_root/frm_1 {\"q0\":\"Red\",\"q1\":[\"Apple\",\"Kiwi\"]}",
                    "dismiss ses_root/frm_1 \"${SessionPrompts.DISMISS_NOTE}\"",
                    "answer ses_root/frm_1 {\"q0\":\"Red\",\"q1\":[\"Apple\",\"Kiwi\"]}",
                ),
                log,
            )
            assertTrue(gone is RequestGoneException)
        }

    @Test
    fun stopInterruptsTheSessionThenItsRunningSubagents() =
        runTest {
            val api = Api()
            api.snapshot = api.snapshot.copy(children = listOf("ses_b", "ses_a", "ses_idle"), runningChildren = listOf("ses_a", "ses_b"))
            api.active = setOf("ses_root", "ses_a", "ses_b", "ses_elsewhere")
            val (today, _) = open(api)

            val outcome = today.stop()

            assertEquals(listOf("interrupt ses_root", "active", "interrupt ses_a", "interrupt ses_b"), log)
            assertEquals(StopOutcome.Stopped(waiting = false), outcome)
        }

    @Test
    fun eachAnswerHasItsOutcome() =
        runTest {
            val api = Api()
            api.snapshot = api.snapshot.copy(inbox = listOf(InboxEntry("msg_q", "queue", "Also this")))
            val (today, _) = open(api)

            assertEquals(StopOutcome.Stopped(waiting = true), today.stop())
            api.interrupted["ses_root"] = false
            assertEquals(StopOutcome.NothingToStop, today.stop())
            api.fails = true
            assertEquals(StopOutcome.Failed, today.stop())
        }

    @Test
    fun aSubagentsNoteThatWakesTheSessionWithinFiveSecondsGetsOneMoreInterrupt() =
        runTest {
            val api = Api()
            val (today, transport) = open(api)
            today.stop()
            log.clear()
            val note =
                """{"inboxID":"msg_n","sessionID":"ses_root","item":{"type":"synthetic",""" +
                    """"payload":{"text":"<subagent state=\"cancelled\">x</subagent>"},"delivery":"steer"}}"""

            advanceTimeBy(1_000)
            transport.send("session.execution.interrupted", """{"sessionID":"ses_root","reason":"user"}""")
            transport.send("session.inbox.enqueued", note)
            transport.send("session.execution.started", """{"sessionID":"ses_root"}""")
            runCurrent()
            assertEquals(listOf("interrupt ses_root"), log)

            // Only once.
            transport.send("session.inbox.enqueued", note.replace("msg_n", "msg_m"))
            transport.send("session.execution.started", """{"sessionID":"ses_root"}""")
            runCurrent()
            assertEquals(listOf("interrupt ses_root"), log)
        }

    @Test
    fun aStartWithoutANoteOrAfterFiveSecondsIsLeftAlone() =
        runTest {
            val api = Api()
            val (today, transport) = open(api)
            val note =
                """{"inboxID":"msg_n","sessionID":"ses_root","item":{"type":"synthetic",""" +
                    """"payload":{"text":"<subagent>x</subagent>"},"delivery":"steer"}}"""
            today.stop()
            log.clear()

            // The user's own message starts it: not a subagent's note.
            transport.send(
                "session.inbox.enqueued",
                """{"inboxID":"msg_u","sessionID":"ses_root","item":{"type":"user","payload":{"text":"go"},"delivery":"steer"}}""",
            )
            transport.send("session.execution.started", """{"sessionID":"ses_root"}""")
            runCurrent()
            assertEquals(emptyList<String>(), log)

            today.stop()
            log.clear()
            advanceTimeBy(5_001)
            transport.send("session.inbox.enqueued", note)
            transport.send("session.execution.started", """{"sessionID":"ses_root"}""")
            runCurrent()
            assertEquals(emptyList<String>(), log)
        }
}
