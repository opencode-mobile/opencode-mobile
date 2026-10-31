package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.ui.sessionchat.SessionChatSubagentStatus.Done
import dev.opencode.mobile.ui.sessionchat.SessionChatSubagentStatus.Running
import dev.opencode.mobile.ui.sessionchat.SessionChatSubagentStatus.WaitingForInput
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

        val times = SessionChatBackgroundWork.finishTimes(before, after, known = emptyMap(), nowMs = 42)

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
                finishedAtMs = mapOf("prt-1" to 5_000L),
            )

        assertEquals(5_000L, rows.first().finishedAtMs)
    }

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
    ) = MessagePart.Tool(
        toolName = "task",
        status = status,
        id = id,
        input =
            buildJsonObject {
                put("description", "Look around")
                agent?.let { put("subagent_type", it) }
            },
        metadata = child?.let { buildJsonObject { put("sessionId", it) } },
        timeStartEpochMs = START,
    )

    private fun session(
        id: String,
        parent: String?,
        title: String = "Subagent",
        agentName: String? = null,
    ) = Session(id = id, title = title, parentId = parent, agentName = agentName)

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
