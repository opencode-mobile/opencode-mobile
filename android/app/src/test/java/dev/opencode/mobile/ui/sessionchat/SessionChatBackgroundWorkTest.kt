package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.ui.sessionchat.SessionChatSubagentStatus.Done
import dev.opencode.mobile.ui.sessionchat.SessionChatSubagentStatus.Running
import dev.opencode.mobile.ui.sessionchat.SessionChatSubagentStatus.WaitingForInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionChatBackgroundWorkTest {
    @Test
    fun subagents_runningPartsCountAsRunning() {
        val rows =
            subagents(
                task("prt-1", ToolStatus.Running, child = "ses-a"),
                task("prt-2", ToolStatus.Pending, child = "ses-b"),
                task("prt-3", ToolStatus.Completed, child = "ses-c"),
            )

        assertEquals(listOf(Running, Running, Done), rows.map { it.status })
        assertEquals("2 subagents", SessionChatBackgroundWork.pill(rows)?.label)
    }

    @Test
    fun subagents_busyChildKeepsFinishedPartRunning() {
        // v1 background tasks complete their tool call while the child session still works.
        val rows = subagents(task("prt-1", ToolStatus.Completed, child = "ses-a"), busy = setOf("ses-a"))

        assertEquals(listOf(Running), rows.map { it.status })
    }

    @Test
    fun subagents_requestFromChildOrItsDescendantIsWaiting() {
        val rows =
            subagents(
                task("prt-1", ToolStatus.Running, child = "ses-a", agent = "general"),
                task("prt-2", ToolStatus.Running, child = "ses-b", agent = "explore"),
                task("prt-3", ToolStatus.Running, child = "ses-c", agent = "review"),
                sessions = listOf(session("ses-grandchild", parent = "ses-b")),
                requests = listOf("ses-grandchild", "ses-root"),
            )

        assertEquals(listOf("prt-2", "prt-1", "prt-3"), rows.map { it.id })
        assertEquals(listOf(WaitingForInput, Running, Running), rows.map { it.status })
    }

    @Test
    fun subagents_waitingSortsFirstThenRunningThenDoneInSpawnOrder() {
        val rows =
            subagents(
                task("prt-1", ToolStatus.Completed, child = "ses-a"),
                task("prt-2", ToolStatus.Running, child = "ses-b"),
                task("prt-3", ToolStatus.Running, child = "ses-c"),
                task("prt-4", ToolStatus.Running, child = "ses-d"),
                requests = listOf("ses-d"),
            )

        assertEquals(listOf("prt-4", "prt-2", "prt-3", "prt-1"), rows.map { it.id })
    }

    @Test
    fun subagents_ignoreOtherTools() {
        val shell = MessagePart.Tool(toolName = "bash", status = ToolStatus.Running, id = "prt-1")

        val rows = subagents(shell)

        assertTrue(rows.isEmpty())
    }

    @Test
    fun subagents_agentNameFallsBackToSessionAgentThenTitleSuffix() {
        val rows =
            subagents(
                task("prt-1", ToolStatus.Running, child = "ses-a", agent = null),
                task("prt-2", ToolStatus.Running, child = "ses-b", agent = null),
                task("prt-3", ToolStatus.Running, child = null, agent = null),
                sessions =
                    listOf(
                        session("ses-a", parent = "ses-root", agentName = "explore"),
                        session("ses-b", parent = "ses-root", title = "Fix tests (@review subagent)"),
                    ),
            )

        assertEquals(listOf("explore", "review", "Task"), rows.map { it.agentName })
    }

    @Test
    fun pill_hiddenWhenNothingWorks() {
        val rows = subagents(task("prt-1", ToolStatus.Completed, child = "ses-a"))

        assertNull(SessionChatBackgroundWork.pill(rows))
        assertNull(SessionChatBackgroundWork.pill(emptyList()))
    }

    @Test
    fun pill_singleRunningSubagentIsSingular() {
        val pill = SessionChatBackgroundWork.pill(subagents(task("prt-1", ToolStatus.Running, child = "ses-a")))

        assertEquals(
            SessionChatBackgroundWorkPill("1 subagent", needsInput = false, accessibilityLabel = "1 subagent running"),
            pill,
        )
    }

    @Test
    fun pill_oneWaitingNamesTheAgentAndCountsTheRest() {
        val rows =
            subagents(
                task("prt-1", ToolStatus.Running, child = "ses-a", agent = "general"),
                task("prt-2", ToolStatus.Running, child = "ses-b"),
                task("prt-3", ToolStatus.Running, child = "ses-c"),
                requests = listOf("ses-a"),
            )

        assertEquals(
            SessionChatBackgroundWorkPill(
                label = "general needs input · 2 running",
                needsInput = true,
                accessibilityLabel = "general needs input, 2 subagents running",
            ),
            SessionChatBackgroundWork.pill(rows),
        )
    }

    @Test
    fun pill_waitingAloneDropsRunningCount() {
        val rows =
            subagents(
                task("prt-1", ToolStatus.Running, child = "ses-a", agent = "explore"),
                requests = listOf("ses-a"),
            )

        assertEquals("explore needs input", SessionChatBackgroundWork.pill(rows)?.label)
    }

    @Test
    fun pill_severalWaitingCountsThem() {
        val rows =
            subagents(
                task("prt-1", ToolStatus.Running, child = "ses-a"),
                task("prt-2", ToolStatus.Running, child = "ses-b"),
                task("prt-3", ToolStatus.Running, child = "ses-c"),
                requests = listOf("ses-a", "ses-b"),
            )

        assertEquals("2 subagents need input · 1 running", SessionChatBackgroundWork.pill(rows)?.label)
    }

    @Test
    fun elapsed_runningCountsFromStartAndDoneFreezes() {
        val running = row(status = Running)
        val done = row(status = Done, finished = START + 65_000)
        val doneUnknown = row(status = Done)

        assertEquals("0:05", running.elapsed(START + 5_000))
        assertEquals("1:05", done.elapsed(START + 1_000_000))
        assertNull(doneUnknown.elapsed(START + 1_000_000))
    }

    @Test
    fun finishTimes_recordsOnlyRowsSeenFinishing() {
        val before = listOf(row("prt-1", Running), row("prt-2", Done))
        val after = listOf(row("prt-1", Done), row("prt-2", Done))

        val times = SessionChatBackgroundWork.finishTimes(before, after, known = emptyMap(), nowMs = 42, seenLive = true)

        assertEquals(mapOf("prt-1" to 42L), times)
    }

    @Test
    fun finishTimes_keepsKnownAndDropsGoneOrRestarted() {
        val current = listOf(row("prt-1", Done), row("prt-2", Running))

        val times =
            SessionChatBackgroundWork.finishTimes(
                previous = current,
                current = current,
                known = mapOf("prt-1" to 10L, "prt-2" to 20L, "prt-gone" to 30L),
                nowMs = 99,
                seenLive = true,
            )

        assertEquals(mapOf("prt-1" to 10L), times)
    }

    @Test
    fun subagents_doneRowCarriesRecordedFinishTime() {
        val rows =
            SessionChatBackgroundWork.subagents(
                messages = listOf(assistant(task("prt-1", ToolStatus.Completed, child = "ses-a"))),
                sessions = emptyList(),
                busySessionIds = emptySet(),
                requestSessionIds = emptyList(),
                localFinishedAtMs = mapOf("prt-1" to START + 5_000L),
            )

        assertEquals(START + 5_000L, rows.first().finishedAtMs)
    }

    @Test
    fun subagents_resumedChildKeepsOneRowFromTheLatestPart() {
        // v1 resume (`task_id`) writes the same child session id on a new tool part.
        val rows =
            subagents(
                task("prt-1", ToolStatus.Completed, child = "ses-a", end = START + 30_000),
                task("prt-2", ToolStatus.Running, child = "ses-a", start = START + 60_000),
                busy = setOf("ses-a"),
            )

        assertEquals(listOf("prt-2"), rows.map { it.id })
        assertEquals(Running, rows.single().status)
        assertEquals(START + 60_000, rows.single().startedAtMs)
        assertEquals("1 subagent", SessionChatBackgroundWork.pill(rows)?.label)
    }

    @Test
    fun subagents_resumedChildWaitingCountsOnce() {
        val rows =
            subagents(
                task("prt-1", ToolStatus.Completed, child = "ses-a", agent = "general"),
                task("prt-2", ToolStatus.Running, child = "ses-a", agent = "general"),
                busy = setOf("ses-a"),
                requests = listOf("ses-a"),
            )

        assertEquals("general needs input", SessionChatBackgroundWork.pill(rows)?.label)
    }

    @Test
    fun subagents_partsWithoutChildStaySeparate() {
        val rows = subagents(task("prt-1", ToolStatus.Running, child = null), task("prt-2", ToolStatus.Running, child = null))

        assertEquals(2, rows.size)
    }

    @Test
    fun finishTime_usesTheToolEndFromTheServer() {
        val rows =
            subagents(
                task("prt-1", ToolStatus.Completed, child = "ses-a", end = START + 45_000),
                sessions = listOf(session("ses-a", parent = "ses-root", updatedAt = START + 600_000)),
            )

        assertEquals(START + 45_000, rows.single().finishedAtMs)
        assertEquals("0:45", rows.single().elapsed(START + 600_000))
    }

    @Test
    fun finishTime_backgroundCallUsesTheChildSessionUpdate() {
        // A background call settles at spawn; its end is not when the child finished.
        val rows =
            subagents(
                task("prt-1", ToolStatus.Completed, child = "ses-a", end = START + 1_000, background = true),
                sessions = listOf(session("ses-a", parent = "ses-root", updatedAt = START + 90_000)),
            )

        assertEquals(START + 90_000, rows.single().finishedAtMs)
    }

    @Test
    fun finishTime_serverTimeWinsOverALocalStampAndIgnoresTimesBeforeStart() {
        val tool =
            MessagePart.Tool(
                toolName = "task",
                status = ToolStatus.Completed,
                timeStartEpochMs = START,
                timeEndEpochMs = START + 20_000,
            )
        val stale = tool.copy(timeEndEpochMs = START - 5_000)

        assertEquals(START + 20_000, SessionChatBackgroundWork.finishTime(tool, session = null, local = START + 99_000))
        assertEquals(START + 7_000, SessionChatBackgroundWork.finishTime(stale, session = null, local = START + 7_000))
    }

    @Test
    fun finishTimes_findingAFinishAfterAGapStampsNothing() {
        // Running at 12:00:00, app away, done found by the reconnect refresh ten minutes later.
        val before = listOf(row("prt-1", Running))
        val after = listOf(row("prt-1", Done))

        val times = SessionChatBackgroundWork.finishTimes(before, after, emptyMap(), nowMs = START + 600_000, seenLive = false)

        assertTrue(times.isEmpty())
        assertNull(after.single().elapsed(START + 600_000))
    }

    @Test
    fun finishTimes_skipsRowsWithAServerFinish() {
        val before = listOf(row("prt-1", Running))
        val after = listOf(row("prt-1", Done, finished = START + 45_000))

        val times = SessionChatBackgroundWork.finishTimes(before, after, emptyMap(), nowMs = START + 50_000, seenLive = true)

        assertTrue(times.isEmpty())
    }

    @Test
    fun subagents_errorIsFailedAndInterruptedIsStopped_neitherCounts() {
        val rows =
            subagents(
                task("prt-1", ToolStatus.Error, child = "ses-a"),
                task("prt-2", ToolStatus.Error, child = "ses-b", interrupted = true),
                busy = setOf("ses-a", "ses-b"),
            )

        assertEquals(listOf(SessionChatSubagentStatus.Failed, SessionChatSubagentStatus.Stopped), rows.map { it.status })
        assertTrue(rows.none { it.isActive })
        assertNull(SessionChatBackgroundWork.pill(rows))
    }

    @Test
    fun needsInputTarget_pagesToTheWaitingSubagentsPermission() {
        val target =
            SessionChatBackgroundWork.needsInputTarget(
                subagents = listOf(waitingRow("prt-a", "ses-a"), waitingRow("prt-b", "ses-b")),
                sessions = emptyList(),
                permissions = listOf(permission("per-root", "ses-root"), permission("per-a", "ses-a")),
                questions = listOf(question("que-b", "ses-b")),
                shownPermissionId = "per-root",
            )

        assertEquals(SessionChatInboxTarget.Permission("per-a"), target)
    }

    @Test
    fun needsInputTarget_findsARequestFromTheSubagentsOwnSubagent() {
        val target =
            SessionChatBackgroundWork.needsInputTarget(
                subagents = listOf(waitingRow("prt-a", "ses-a")),
                sessions = listOf(session("ses-grandchild", parent = "ses-a")),
                permissions = emptyList(),
                questions = listOf(question("que-root", "ses-root"), question("que-a", "ses-grandchild")),
                shownPermissionId = null,
            )

        assertEquals(SessionChatInboxTarget.Question("que-a"), target)
    }

    @Test
    fun needsInputTarget_questionBehindAPermissionOpensTheShownPermission() {
        val target =
            SessionChatBackgroundWork.needsInputTarget(
                subagents = listOf(waitingRow("prt-a", "ses-a")),
                sessions = emptyList(),
                permissions = listOf(permission("per-root", "ses-root"), permission("per-other", "ses-root")),
                questions = listOf(question("que-a", "ses-a")),
                shownPermissionId = "per-other",
            )

        assertEquals(SessionChatInboxTarget.Permission("per-other"), target)
    }

    @Test
    fun needsInputTarget_nullWithoutAWaitingSubagentOrItsRequest() {
        assertNull(
            SessionChatBackgroundWork.needsInputTarget(
                subagents = listOf(waitingRow("prt-a", "ses-a").copy(status = Running)),
                sessions = emptyList(),
                permissions = listOf(permission("per-a", "ses-a")),
                questions = emptyList(),
                shownPermissionId = null,
            ),
        )
        assertNull(
            SessionChatBackgroundWork.needsInputTarget(
                subagents = listOf(waitingRow("prt-a", "ses-a")),
                sessions = emptyList(),
                permissions = listOf(permission("per-root", "ses-root")),
                questions = emptyList(),
                shownPermissionId = null,
            ),
        )
    }

    private fun waitingRow(
        id: String,
        child: String,
    ) = SessionChatSubagent(
        id = id,
        childSessionId = child,
        agentName = "general",
        title = null,
        status = WaitingForInput,
        startedAtMs = null,
        finishedAtMs = null,
    )

    private fun permission(
        id: String,
        session: String,
    ) = PermissionRequest(
        id = id,
        sessionId = session,
        permission = "bash",
        patterns = listOf("npm test"),
        metadata = JsonObject(emptyMap()),
        always = emptyList(),
        toolMessageId = null,
        toolCallId = null,
    )

    private fun question(
        id: String,
        session: String,
    ) = QuestionRequest(id = id, sessionId = session, questions = emptyList(), toolMessageId = null, toolCallId = null)

    private fun subagents(
        vararg parts: MessagePart,
        sessions: List<Session> = emptyList(),
        busy: Set<String> = emptySet(),
        requests: List<String> = emptyList(),
    ) = SessionChatBackgroundWork.subagents(
        messages = listOf(assistant(*parts)),
        sessions = sessions,
        busySessionIds = busy,
        requestSessionIds = requests,
    )

    private fun assistant(vararg parts: MessagePart) =
        Message(id = "msg-1", role = MessageRole.Assistant, text = "", parts = parts.toList())

    private fun task(
        id: String,
        status: ToolStatus,
        child: String?,
        agent: String? = "general",
        start: Long = START,
        end: Long? = null,
        background: Boolean = false,
        interrupted: Boolean = false,
    ) = MessagePart.Tool(
        toolName = "task",
        status = status,
        id = id,
        input =
            buildJsonObject {
                put("description", "Look around")
                agent?.let { put("subagent_type", it) }
            },
        metadata =
            buildJsonObject {
                child?.let { put("sessionId", it) }
                if (background) put("background", true)
            }.takeIf { it.isNotEmpty() },
        timeStartEpochMs = start,
        timeEndEpochMs = end,
        isInterrupted = interrupted,
    )

    private fun session(
        id: String,
        parent: String?,
        title: String = "Subagent",
        agentName: String? = null,
        updatedAt: Long? = null,
    ) = Session(id = id, title = title, parentId = parent, agentName = agentName, updatedAtEpochMs = updatedAt)

    private fun row(
        id: String = "prt-1",
        status: SessionChatSubagentStatus,
        finished: Long? = null,
    ) = SessionChatSubagent(
        id = id,
        childSessionId = "ses-$id",
        agentName = "general",
        title = null,
        status = status,
        startedAtMs = START,
        finishedAtMs = finished,
    )

    private companion object {
        const val START = 1_700_000_000_000L
    }
}
