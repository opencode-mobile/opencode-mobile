package dev.opencode.mobile.domain.goal

import dev.opencode.mobile.data.remote.dto.V2SessionMessageDto
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogBuffer
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.message.MessageDomainAdapter
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.TimelineNotice
import dev.opencode.mobile.domain.message.TimelineNoticeCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGoalTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val events = EventDomainAdapter(json)

    // MARK: Goal.State decoding

    @Test
    fun goal_decodesTheRpcOutput() {
        val output =
            json.parseToJsonElement(
                """
                {"id":"goal_1","objective":"Make the tests pass","status":"active","reason":"3 tests still fail",
                 "rounds":3,"maxRounds":50,"stalls":1,"boot":"b1","updatedAt":1790000000000}
                """.trimIndent(),
            )

        assertEquals(
            SessionGoal(
                id = "goal_1",
                objective = "Make the tests pass",
                status = SessionGoalStatus.Active,
                reason = "3 tests still fail",
                rounds = 3,
                maxRounds = 50,
                stalls = 1,
                updatedAtEpochMs = 1790000000000,
            ),
            SessionGoalDomainAdapter.goal(output),
        )
    }

    @Test
    fun goal_readsEveryStatusAndToleratesMissingOptionals() {
        val paused = goalJson("""{"id":"g","objective":"o","status":"paused","rounds":0,"maxRounds":50,"updatedAt":5}""")
        assertEquals(SessionGoalStatus.Paused, paused?.status)
        assertNull(paused?.reason)
        assertNull(paused?.stalls)
        assertEquals(
            SessionGoalStatus.Complete,
            goalJson("""{"id":"g","objective":"o","status":"complete","rounds":4,"maxRounds":50,"updatedAt":5}""")?.status,
        )
    }

    @Test
    fun goal_isNullForNoGoalOrAnUnknownShape() {
        assertNull(SessionGoalDomainAdapter.goal(JsonNull))
        assertNull(SessionGoalDomainAdapter.goal(null))
        assertNull(goalJson("""{"id":"g","objective":"o","status":"exploded","rounds":0,"maxRounds":50,"updatedAt":5}"""))
        assertNull(goalJson("""{"objective":"o","status":"active","rounds":0,"maxRounds":50,"updatedAt":5}"""))
        assertNull(goalJson("""{"id":"g","status":"active","rounds":0,"maxRounds":50,"updatedAt":5}"""))
        assertNull(goalJson("""["g"]"""))
    }

    // MARK: rpc.opencode.goal.changed

    @Test
    fun change_decodesTheLiveRpcEvent() {
        val parsed =
            events.parseEvents(
                """{"id":"evt_1","created":10,"type":"rpc.opencode.goal.changed","location":{"directory":"/Users/example/project"},""" +
                    """"data":{"sessionID":"ses_1","goal":{"id":"goal_1","objective":"Ship it","status":"paused",""" +
                    """"reason":"Paused by the user","rounds":2,"maxRounds":50,"boot":"b","updatedAt":11}}}""",
            )

        val change = parsed.firstNotNullOf(SessionGoalDomainAdapter::change)

        assertEquals("ses_1", change.sessionId)
        assertEquals("goal_1", change.goal?.id)
        assertEquals(SessionGoalStatus.Paused, change.goal?.status)
        assertEquals(11L, change.goal?.updatedAtEpochMs)
    }

    @Test
    fun change_withANullGoalIsAClear() {
        val parsed =
            events.parseEvents(
                """{"id":"evt_2","created":10,"type":"rpc.opencode.goal.changed","location":{"directory":"/Users/example/project"},""" +
                    """"data":{"sessionID":"ses_1","goal":null}}""",
            )

        assertEquals(SessionGoalChange("ses_1", null), parsed.firstNotNullOf(SessionGoalDomainAdapter::change))
    }

    @Test
    fun change_toleratesNullAndFractionalNumbers() {
        val parsed =
            events.parseEvents(
                """{"id":"evt_3","created":10,"type":"rpc.opencode.goal.changed","location":{"directory":"/Users/example/project"},""" +
                    """"data":{"sessionID":"ses_1","goal":{"id":"goal_1","objective":"Ship it","status":"active",""" +
                    """"rounds":null,"maxRounds":50.0,"stalls":1.0,"updatedAt":11}}}""",
            )

        val goal = parsed.firstNotNullOf(SessionGoalDomainAdapter::change).goal

        assertEquals(SessionGoal("goal_1", "Ship it", SessionGoalStatus.Active, null, 0, 50, 1, 11), goal)
    }

    @Test
    fun change_aGoalThatCannotBeReadIsAClearAndIsLogged() {
        DiagnosticLogBuffer.resetSharedForTests()
        try {
            val parsed =
                events.parseEvents(
                    """{"id":"evt_4","created":10,"type":"rpc.opencode.goal.changed","location":{"directory":"/Users/example/project"},""" +
                        """"data":{"sessionID":"ses_1","goal":{"id":"goal_1","status":"active","rounds":0}}}""",
                )

            assertEquals(SessionGoalChange("ses_1", null), parsed.firstNotNullOf(SessionGoalDomainAdapter::change))
            assertEquals(
                "goal change unreadable session=ses_1 reason=no objective",
                DiagnosticLogBuffer.shared
                    .snapshot()
                    .entries
                    .single()
                    .message,
            )
        } finally {
            DiagnosticLogBuffer.resetSharedForTests()
        }
    }

    @Test
    fun change_ignoresOtherEvents() {
        assertNull(SessionGoalDomainAdapter.change(OpencodeEvent(type = "session.idle", sessionId = "ses_1")))
        val other = OpencodeEvent(type = "rpc.other.changed", sessionId = "ses_1", rawProperties = JsonObject(emptyMap()))
        assertNull(SessionGoalDomainAdapter.change(other))
    }

    // MARK: Pause titles

    @Test
    fun pause_classifiesEveryKnownServerReason() {
        val cases =
            listOf(
                "Paused by the user" to SessionGoalPause(SessionGoalPauseKind.ByUser, hidesReason = true),
                "Stopped by the user" to SessionGoalPause(SessionGoalPauseKind.Stopped, hidesReason = true),
                "The session failed: provider returned 429" to SessionGoalPause(SessionGoalPauseKind.Failed, hidesReason = false),
                "Not making progress: 1 test still fails" to SessionGoalPause(SessionGoalPauseKind.NoProgress, hidesReason = false),
                "Reached the limit of 50 rounds" to SessionGoalPause(SessionGoalPauseKind.RoundLimit, hidesReason = true),
                "The server restarted while the goal was active" to
                    SessionGoalPause(SessionGoalPauseKind.ServerRestart, hidesReason = true),
                "The goal reviewer gave no usable verdict" to SessionGoalPause(SessionGoalPauseKind.ReviewerFailed, hidesReason = false),
                "The goal reviewer's request is too large for the model" to
                    SessionGoalPause(SessionGoalPauseKind.ReviewerFailed, hidesReason = false),
                "Nothing ran for 30 minutes while the goal waited on background work" to
                    SessionGoalPause(SessionGoalPauseKind.BackgroundQuiet, hidesReason = false),
            )
        cases.forEach { (reason, expected) -> assertEquals(reason, expected, SessionGoalPause.classify(reason)) }
    }

    @Test
    fun pause_anyOtherReasonIsTheReviewersOwnWords() {
        val pause = SessionGoalPause.classify("Needs a decision: keep the old sort order?")

        assertEquals(SessionGoalPauseKind.Other, pause.kind)
        assertFalse(pause.hidesReason)
        assertFalse(pause.isKnown)
        // A near miss is not a known reason.
        assertEquals(SessionGoalPauseKind.Other, SessionGoalPause.classify("paused by the user").kind)
    }

    @Test
    fun pause_isOnlyReadForAPausedGoal() {
        val goal = SessionGoal("g", "o", SessionGoalStatus.Complete, "Paused by the user", 1, 50, null, 1)

        assertNull(goal.pause)
        assertEquals(SessionGoalPauseKind.ByUser, goal.copy(status = SessionGoalStatus.Paused).pause?.kind)
    }

    // MARK: Kickoff and draft

    @Test
    fun kickoffObjective_readsTheEscapedObjectiveBlock() {
        val text =
            "A goal is now active for this session.\n\n<untrusted_objective>\nKeep a &lt;b&gt; tag &amp; the &amp;lt; entity\n" +
                "</untrusted_objective>\n\nStart now."

        assertEquals("Keep a <b> tag & the &lt; entity", SessionGoalKickoff.objective(text))
        assertNull(SessionGoalKickoff.objective("No block here"))
    }

    @Test
    fun draft_readsTheObjectiveOfAGoalCommandOnly() {
        assertEquals("Ship it", SessionGoalDraft.objective("/goal Ship it"))
        assertEquals("", SessionGoalDraft.objective("/goal "))
        assertTrue(SessionGoalDraft.awaitsObjective("/goal "))
        assertFalse(SessionGoalDraft.awaitsObjective("/goal"))
        assertFalse(SessionGoalDraft.awaitsObjective("/goal x"))
        assertNull(SessionGoalDraft.objective("/goals are nice"))
        assertNull(SessionGoalDraft.objective("Ship it"))
        assertNull(SessionGoalDraft.objective("/goal pause"))
        assertNull(SessionGoalDraft.objective("/goal Clear"))
    }

    @Test
    fun draft_sendStaysOffWhileEmptyOrOverTheLimit() {
        assertFalse(SessionGoalDraft.canSend(""))
        assertTrue(SessionGoalDraft.canSend("x".repeat(SessionGoalLimits.MAX_OBJECTIVE)))
        assertFalse(SessionGoalDraft.canSend("x".repeat(SessionGoalLimits.MAX_OBJECTIVE + 1)))
        assertFalse(SessionGoalDraft.showsCount("x".repeat(SessionGoalDraft.COUNT_FROM - 1)))
        assertTrue(SessionGoalDraft.showsCount("x".repeat(SessionGoalDraft.COUNT_FROM)))
    }

    @Test
    fun commandEcho_onlyAGoalKickoffWithAttachmentsOrAnotherCommandShowsOne() {
        assertFalse(SessionGoalCommandEcho.shows("goal", "Ship it", carriesPromptParts = false))
        assertTrue(SessionGoalCommandEcho.shows("goal", "Ship it", carriesPromptParts = true))
        listOf("pause", "Resume", " clear ").forEach { control ->
            assertFalse(SessionGoalCommandEcho.shows("goal", control, carriesPromptParts = true))
        }
        assertTrue(SessionGoalCommandEcho.shows("init", "please", carriesPromptParts = false))
        assertTrue(SessionGoalCommandEcho.shows("goals", "pause", carriesPromptParts = false))
    }

    // MARK: Kickoff projection

    @Test
    fun projectV2_describedKickoffIsAGoalSetNoticeAndContinuationsStayHidden() {
        val messages =
            projectV2(
                """
                [
                  {"id":"msg_k","type":"synthetic","text":"A goal is now active…","description":"Goal: Make the tests pass",
                   "metadata":{"source":"goal","goalID":"goal_1"},"time":{"created":1}},
                  {"id":"msg_c","type":"synthetic","text":"Continue working toward the active goal.",
                   "metadata":{"source":"goal","goalID":"goal_1"},"time":{"created":2}}
                ]
                """,
            )

        assertEquals(MessageRole.Notice, messages[0].role)
        assertEquals(
            TimelineNotice.GoalSet(goalId = "goal_1", description = "Goal: Make the tests pass"),
            messages[0]
                .parts
                .filterIsInstance<MessagePart.Notice>()
                .single()
                .notice,
        )
        val continuation = messages[1].parts.single() as MessagePart.Text
        assertTrue(continuation.isSynthetic)
    }

    @Test
    fun projectV2_kickoffSentAsAPromptShowsTheObjectiveOnly() {
        val messages =
            projectV2(
                """
                [
                  {"id":"msg_u","type":"user",
                   "text":"A goal is now active for this session.\n\n<untrusted_objective>\nMatch the screenshot\n</untrusted_objective>\n\nStart now.",
                   "metadata":{"source":"goal","goalID":"goal_2"},"time":{"created":1}},
                  {"id":"msg_p","type":"user","text":"A plain prompt","time":{"created":2}}
                ]
                """,
            )

        val kickoff = messages[0].parts.filterIsInstance<MessagePart.Text>().single()
        assertEquals(MessageRole.User, messages[0].role)
        assertEquals("Match the screenshot", kickoff.text)
        assertEquals("goal_2", kickoff.goalKickoffId)
        assertNull(
            messages[1]
                .parts
                .filterIsInstance<MessagePart.Text>()
                .single()
                .goalKickoffId,
        )
    }

    @Test
    fun parseEvents_liveKickoffSyntheticIsAGoalSetNotice() {
        val parsed =
            events.parseEvents(
                """{"id":"evt_k","created":30,"type":"session.synthetic",""" +
                    """"data":{"sessionID":"ses_1","text":"A goal is now active…","description":"Goal: Ship it",""" +
                    """"metadata":{"source":"goal","goalID":"goal_1"}}}""",
            )
        val part = parsed.single { it.type == "message.part.updated" }.rawProperties!!["part"]!!.jsonObject

        assertEquals(
            TimelineNotice.GoalSet("goal_1", "Goal: Ship it"),
            TimelineNoticeCodec.decode(part["notice"]!!.jsonObject),
        )

        val continuation =
            events.parseEvents(
                """{"id":"evt_c","created":31,"type":"session.synthetic",""" +
                    """"data":{"sessionID":"ses_1","text":"Continue.","metadata":{"source":"goal","goalID":"goal_1"}}}""",
            )
        assertTrue(continuation.none { it.type == "message.part.updated" })
    }

    @Test
    fun parseEvents_liveKickoffInboxItemIsAGoalSetNoticeUnderTheInboxId() {
        val parsed =
            events.parseEvents(
                """{"id":"evt_e1","created":40,"type":"session.inbox.enqueued","location":{"directory":"/repo"},""" +
                    """"data":{"inboxID":"msg_k","sessionID":"ses_1","item":{"type":"synthetic","delivery":"steer",""" +
                    """"payload":{"text":"A goal is now active for this session. <untrusted_objective>\nShip it\n</untrusted_objective>",""" +
                    """"description":"Goal: Ship it","metadata":{"source":"goal","goalID":"goal_1"}}}}}""",
            )

        val info = parsed.single { it.type == "message.updated" }.rawProperties!!["info"]!!.jsonObject
        val part = parsed.single { it.type == "message.part.updated" }.rawProperties!!["part"]!!.jsonObject
        assertEquals("msg_k", info["id"]!!.jsonPrimitive.content)
        assertEquals("notice", info["role"]!!.jsonPrimitive.content)
        assertEquals("msg_k", part["messageID"]!!.jsonPrimitive.content)
        assertEquals(TimelineNotice.GoalSet("goal_1", "Goal: Ship it"), TimelineNoticeCodec.decode(part["notice"]!!.jsonObject))

        // `inbox.delivered` carries only the id: it must not turn the row back into a user message.
        val delivered =
            events.parseEvents(
                """{"id":"evt_d1","created":41,"type":"session.inbox.delivered","data":{"inboxID":"msg_k","sessionID":"ses_1"}}""",
            )
        assertTrue(
            delivered
                .filter { it.type == "message.updated" }
                .all {
                    it.rawProperties!!["info"]!!
                        .jsonObject["role"]!!
                        .jsonPrimitive.content == "notice"
                },
        )
    }

    @Test
    fun parseEvents_otherSyntheticInboxItemsStayHiddenUserText() {
        val continuation =
            events.parseEvents(
                """{"type":"session.inbox.enqueued","data":{"inboxID":"msg_c","sessionID":"ses_1","item":{"type":"synthetic",""" +
                    """"delivery":"steer","payload":{"text":"Continue.","metadata":{"source":"goal","goalID":"goal_1"}}}}}""",
            )
        val info = continuation.single { it.type == "message.updated" }.rawProperties!!["info"]!!.jsonObject
        val part = continuation.single { it.type == "message.part.updated" }.rawProperties!!["part"]!!.jsonObject
        assertEquals("user", info["role"]!!.jsonPrimitive.content)
        assertEquals("true", part["synthetic"]!!.jsonPrimitive.content)

        val delivered =
            events.parseEvents(
                """{"type":"session.inbox.delivered","data":{"inboxID":"msg_c","sessionID":"ses_1"}}""",
            )
        assertEquals(
            "user",
            delivered
                .single()
                .rawProperties!!["info"]!!
                .jsonObject["role"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun parseEvents_liveKickoffPromptCarriesTheGoalAndTheObjective() {
        val parsed =
            events.parseEvents(
                """{"type":"session.next.prompted","data":{"sessionID":"ses_1","messageID":"msg_1","prompt":{""" +
                    """"text":"Start.\n<untrusted_objective>\nMatch the screenshot\n</untrusted_objective>",""" +
                    """"files":[{"uri":"data:image/png;base64,AA==","mime":"image/png","name":"shot.png"}],""" +
                    """"metadata":{"source":"goal","goalID":"goal_2"}},"delivery":"steer"}}""",
            )
        val text = parsed.filter { it.type == "message.part.updated" }.map { it.rawProperties!!["part"]!!.jsonObject }.first()

        assertEquals("Match the screenshot", text["text"]!!.jsonPrimitive.content)
        assertEquals("goal_2", text["goalID"]!!.jsonPrimitive.content)
    }

    @Test
    fun noticeCodec_roundTripsGoalSet() {
        val notice = TimelineNotice.GoalSet("goal_1", "Goal: Ship it")

        assertEquals(notice, TimelineNoticeCodec.decode(TimelineNoticeCodec.encode(notice)))
    }

    private fun goalJson(text: String): SessionGoal? = SessionGoalDomainAdapter.goal(json.parseToJsonElement(text))

    private fun projectV2(body: String) =
        MessageDomainAdapter().projectV2(
            json.decodeFromString<List<V2SessionMessageDto>>(body.trimIndent()),
        )
}
