package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.shell.AgentShell
import dev.opencode.mobile.domain.shell.AgentShellStatus
import dev.opencode.mobile.ui.sessionchat.region.SessionChatShellsCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionChatBackgroundWorkShellsTest {
    @Test
    fun pill_shellsAloneShowThePill() {
        assertEquals(
            SessionChatBackgroundWorkPill("1 shell", needsInput = false, accessibilityLabel = "1 shell running"),
            SessionChatBackgroundWork.pill(emptyList(), listOf(shell("sh_1"))),
        )
        assertEquals(
            SessionChatBackgroundWorkPill("3 shells", needsInput = false, accessibilityLabel = "3 shells running"),
            SessionChatBackgroundWork.pill(emptyList(), listOf(shell("sh_1"), shell("sh_2"), shell("sh_3"))),
        )
    }

    @Test
    fun pill_subagentsAndShellsShareTheLabel() {
        val pill = SessionChatBackgroundWork.pill(listOf(subagent("prt_1")), listOf(shell("sh_1"), shell("sh_2")))

        assertEquals(
            SessionChatBackgroundWorkPill(
                "1 subagent · 2 shells",
                needsInput = false,
                accessibilityLabel = "1 subagent and 2 shells running",
            ),
            pill,
        )
    }

    @Test
    fun pill_needsInputCountsShellsAsRunning() {
        val pill =
            SessionChatBackgroundWork.pill(
                listOf(subagent("prt_1", SessionChatSubagentStatus.WaitingForInput), subagent("prt_2")),
                listOf(shell("sh_1")),
            )

        assertEquals(
            SessionChatBackgroundWorkPill(
                "general needs input · 2 running",
                needsInput = true,
                accessibilityLabel = "general needs input, 1 subagent and 1 shell running",
            ),
            pill,
        )
    }

    @Test
    fun pill_endedShellsDoNotCount() {
        val ended = shell("sh_1").copy(status = AgentShellStatus.Exited(0))

        assertNull(SessionChatBackgroundWork.pill(emptyList(), listOf(ended)))
        assertEquals("1 shell", SessionChatBackgroundWork.pill(emptyList(), listOf(ended, shell("sh_2")))?.label)
    }

    @Test
    fun runningShells_oldestFirstAndEndedLeave() {
        val shells =
            listOf(
                shell("sh_new", startedAtMs = 3_000),
                shell("sh_unknown", startedAtMs = null),
                shell("sh_ended", startedAtMs = 1_000).copy(status = AgentShellStatus.Stopped),
                shell("sh_old", startedAtMs = 2_000),
            )

        assertEquals(listOf("sh_old", "sh_new", "sh_unknown"), SessionChatBackgroundWork.runningShells(shells).map { it.id })
        assertEquals(emptyList<AgentShell>(), SessionChatBackgroundWork.runningShells(emptyList()))
    }

    @Test
    fun statusLine() {
        val start = 1_000_000L
        val running = shell("sh_1", startedAtMs = start)

        assertEquals("Running · 1 min 12 s", SessionChatShellsCopy.status(running, nowMs = start + 72_000))
        assertEquals("Running · 4 s", SessionChatShellsCopy.status(running, nowMs = start + 4_000))
        assertEquals("Running", SessionChatShellsCopy.status(running.copy(startedAtMs = null), nowMs = start))

        fun ended(status: AgentShellStatus) = running.copy(status = status, finishedAtMs = start + 72_000)
        assertEquals("Exited · 1 min 12 s", SessionChatShellsCopy.status(ended(AgentShellStatus.Exited(0)), nowMs = 0))
        assertEquals("Exited · 1 min 12 s", SessionChatShellsCopy.status(ended(AgentShellStatus.Exited(null)), nowMs = 0))
        assertEquals("Exited with code 1 · 1 min 12 s", SessionChatShellsCopy.status(ended(AgentShellStatus.Exited(1)), nowMs = 0))
        assertEquals("Timed out · 1 min 12 s", SessionChatShellsCopy.status(ended(AgentShellStatus.TimedOut), nowMs = 0))
        assertEquals("Stopped · 1 min 12 s", SessionChatShellsCopy.status(ended(AgentShellStatus.Stopped), nowMs = 0))
        assertEquals("Failed · 1 min 12 s", SessionChatShellsCopy.status(ended(AgentShellStatus.Failed), nowMs = 0))
        assertEquals("2 h 5 min", SessionChatShellsCopy.duration((2 * 3600 + 5 * 60) * 1000L))
    }

    private fun shell(
        id: String,
        startedAtMs: Long? = 1_793_548_739_990L,
    ) = AgentShell(
        id = id,
        sessionId = "ses_test",
        command = "npm test",
        description = null,
        status = AgentShellStatus.Running,
        startedAtMs = startedAtMs,
        finishedAtMs = null,
        canStop = true,
    )

    private fun subagent(
        id: String,
        status: SessionChatSubagentStatus = SessionChatSubagentStatus.Running,
    ) = SessionChatSubagent(
        id = id,
        childSessionId = "ses-$id",
        agentName = "general",
        title = null,
        status = status,
        startedAtMs = null,
        finishedAtMs = null,
    )
}
