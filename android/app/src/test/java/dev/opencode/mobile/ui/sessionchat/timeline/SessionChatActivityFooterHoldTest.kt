package dev.opencode.mobile.ui.sessionchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionChatActivityFooterHoldTest {
    private val running = SessionChatStepStatus.Running
    private val done = SessionChatStepStatus.Completed

    private fun tool(
        id: String,
        status: SessionChatStepStatus = done,
        startedAtMs: Long? = null,
    ) = SessionChatStep(
        id = id,
        kind = SessionChatStepKind.Tool,
        status = status,
        title = id,
        runningTitle = "Running $id",
        settledTitle = "Ran $id",
        family = SessionChatStepFamily.Command,
        startedAtMs = startedAtMs,
    )

    private fun agent(
        id: String,
        status: SessionChatStepStatus = running,
    ) = SessionChatStep(id = id, kind = SessionChatStepKind.ChildSession, status = status, title = "explore", family = SessionChatStepFamily.Agent)

    private val thinking = SessionChatActivityFooter.Idle(SessionChatActivityCard.THINKING)

    private fun runningTool(
        id: String,
        extra: Int = 0,
        startedAtMs: Long? = null,
    ) = SessionChatActivityFooter.Tool(
        stepId = id,
        family = SessionChatStepFamily.Command,
        title = "Running $id",
        running = true,
        extraRunning = extra,
        startedAtMs = startedAtMs,
    )

    private fun settledTool(id: String) =
        SessionChatActivityFooter.Tool(
            stepId = id,
            family = SessionChatStepFamily.Command,
            title = "Ran $id",
            running = false,
            extraRunning = 0,
            startedAtMs = null,
        )

    @Test
    fun firstValueShowsImmediately() {
        val hold = SessionChatActivityFooterHold()
        assertEquals(runningTool("a", startedAtMs = 5), hold.submit(listOf(tool("a", running, startedAtMs = 5)), nowMs = 0))
        assertEquals(1_200L, hold.expiresAtMs)
    }

    @Test
    fun changeInsideTheHoldWaitsAndTheLatestPendingWins() {
        val hold = SessionChatActivityFooterHold()
        hold.submit(emptyList(), nowMs = 0)
        assertEquals(thinking, hold.submit(listOf(tool("a", running)), nowMs = 100))
        assertEquals(thinking, hold.submit(listOf(tool("a"), tool("b", running)), nowMs = 200))
        assertEquals(runningTool("b"), hold.pending)
        assertEquals(runningTool("b"), hold.expire(nowMs = 1_200))
        assertEquals(2_400L, hold.expiresAtMs)
    }

    @Test
    fun shownToolThatSettlesTurnsSettledInPlaceWithoutRestartingTheHold() {
        val hold = SessionChatActivityFooterHold()
        hold.submit(listOf(tool("a", running)), nowMs = 0)
        assertEquals(settledTool("a"), hold.submit(listOf(tool("a")), nowMs = 300))
        assertEquals(1_200L, hold.expiresAtMs)
        assertEquals(thinking, hold.expire(nowMs = 1_200))
    }

    @Test
    fun expiryAppliesThePendingValueAndRestartsTheHold() {
        val hold = SessionChatActivityFooterHold()
        hold.submit(listOf(tool("a", running)), nowMs = 0)
        hold.submit(listOf(tool("a", running), tool("b", running, startedAtMs = 9)), nowMs = 400)
        assertEquals(runningTool("a"), hold.expire(nowMs = 1_199))
        assertEquals(runningTool("b", extra = 1, startedAtMs = 9), hold.expire(nowMs = 1_200))
        assertEquals(2_400L, hold.expiresAtMs)
    }

    @Test
    fun catchUpShowsASkippedToolOnceSettledThenIdle() {
        val hold = SessionChatActivityFooterHold()
        hold.submit(listOf(tool("a", running, startedAtMs = 1)), nowMs = 0)
        hold.submit(listOf(tool("a", startedAtMs = 1), tool("b", running, startedAtMs = 2)), nowMs = 100)
        hold.submit(listOf(tool("a", startedAtMs = 1), tool("b", startedAtMs = 2), tool("c", running, startedAtMs = 3)), nowMs = 200)
        val steps = listOf(tool("a", startedAtMs = 1), tool("b", startedAtMs = 2), tool("c", startedAtMs = 3))
        assertEquals(settledTool("a"), hold.submit(steps, nowMs = 300))
        // b and c ran and finished unseen: the newest, c, shows settled for one hold.
        assertEquals(settledTool("c"), hold.expire(nowMs = 1_200))
        assertEquals(2_400L, hold.expiresAtMs)
        // Only one catch-up per hold: b is skipped, the footer falls to idle.
        assertEquals(thinking, hold.expire(nowMs = 2_400))
        assertEquals(thinking, hold.expire(nowMs = 3_600))
        assertNull(hold.expiresAtMs)
    }

    @Test
    fun toolThatStartsAndFinishesWhileIdleIsShownSettledAfterTheHold() {
        val hold = SessionChatActivityFooterHold()
        hold.submit(emptyList(), nowMs = 0)
        assertEquals(thinking, hold.submit(listOf(tool("a")), nowMs = 100))
        assertEquals(settledTool("a"), hold.expire(nowMs = 1_200))
        assertEquals(thinking, hold.expire(nowMs = 2_400))
    }

    @Test
    fun equalDesiredIsANoOp() {
        val hold = SessionChatActivityFooterHold()
        hold.submit(listOf(tool("a", running)), nowMs = 0)
        assertEquals(runningTool("a"), hold.submit(listOf(tool("a", running)), nowMs = 500))
        assertNull(hold.pending)
        assertEquals(1_200L, hold.expiresAtMs)
        assertEquals(runningTool("a"), hold.expire(nowMs = 1_200))
        assertNull(hold.expiresAtMs)
        // A later change with no hold running shows at once.
        hold.submit(listOf(tool("a"), tool("b", running)), nowMs = 5_000)
        assertEquals(runningTool("b"), hold.displayed)
    }

    @Test
    fun theShownToolPicksUpItsParallelCountInPlace() {
        val hold = SessionChatActivityFooterHold()
        hold.submit(listOf(tool("b", running, startedAtMs = 9)), nowMs = 0)
        val withParallel = listOf(tool("a", running, startedAtMs = 1), tool("b", running, startedAtMs = 9))
        assertEquals(runningTool("b", extra = 1, startedAtMs = 9), hold.submit(withParallel, nowMs = 100))
        assertEquals(1_200L, hold.expiresAtMs)
    }

    @Test
    fun runningChildrenHoldWaitingForAgents() {
        val hold = SessionChatActivityFooterHold()
        hold.submit(emptyList(), nowMs = 0)
        hold.submit(listOf(agent("c1")), nowMs = 100)
        assertEquals(SessionChatActivityFooter.Idle(SessionChatActivityCard.WAITING_FOR_AGENTS), hold.expire(nowMs = 1_200))
    }

    @Test
    fun aSettledSegmentDropsItsHoldAndANewOneStartsFresh() {
        // The Screen Model drops a hold once its card is no longer live; a new hold starts at rule 1.
        val hold = SessionChatActivityFooterHold()
        hold.submit(listOf(tool("a", running)), nowMs = 0)
        val fresh = SessionChatActivityFooterHold()
        assertEquals(thinking, fresh.submit(listOf(tool("a")), nowMs = 100))
        assertNull(fresh.pending)
    }

    @Test
    fun debounceHidesAStepUntilItHasRunFor300Ms() {
        val debounce = SessionChatStepEntryDebounce()
        assertEquals(emptySet<String>(), debounce.submit(emptyList(), nowMs = 0))
        assertEquals(setOf("a"), debounce.submit(listOf(tool("a", running, startedAtMs = 0)), nowMs = 1_000))
        assertEquals(1_300L, debounce.nextDueMs)
        assertEquals(setOf("a"), debounce.submit(listOf(tool("a", running, startedAtMs = 0)), nowMs = 1_299))
        assertEquals(emptySet<String>(), debounce.submit(listOf(tool("a", running, startedAtMs = 0)), nowMs = 1_300))
        assertNull(debounce.nextDueMs)
    }

    @Test
    fun debounceStepThatSettlesWithin300MsIsNeverHiddenOnceSettled() {
        val debounce = SessionChatStepEntryDebounce()
        debounce.submit(emptyList(), nowMs = 0)
        assertEquals(setOf("a"), debounce.submit(listOf(tool("a", running)), nowMs = 100))
        assertEquals(emptySet<String>(), debounce.submit(listOf(tool("a")), nowMs = 200))
        assertNull(debounce.nextDueMs)
    }

    @Test
    fun debounceOnFirstBuildShowsStepsTheServerStartedLongAgo() {
        val debounce = SessionChatStepEntryDebounce()
        val steps = listOf(tool("old", running, startedAtMs = 10_000), tool("new", running, startedAtMs = 19_900), tool("unknown", running))
        assertEquals(setOf("new", "unknown"), debounce.submit(steps, nowMs = 20_000))
        // Later steps always wait, whatever the server says.
        val later = steps + tool("late", running, startedAtMs = 1_000)
        assertEquals(setOf("late"), debounce.submit(later, nowMs = 20_300))
    }

    @Test
    fun debounceNeverHidesChildSessions() {
        val debounce = SessionChatStepEntryDebounce()
        debounce.submit(emptyList(), nowMs = 0)
        assertEquals(emptySet<String>(), debounce.submit(listOf(agent("c1")), nowMs = 10))
    }

    @Test
    fun debouncedToolThatFinishesIsCaughtUpSettledByTheFooter() {
        val debounce = SessionChatStepEntryDebounce()
        val hold = SessionChatActivityFooterHold()
        fun submit(
            steps: List<SessionChatStep>,
            now: Long,
        ): SessionChatActivityFooter {
            val hidden = debounce.submit(steps, now)
            return hold.submit(steps.filter { it.id !in hidden }, now)
        }
        assertEquals(thinking, submit(emptyList(), 0))
        // Hidden while it runs, so the footer never shows "Running a".
        assertEquals(thinking, submit(listOf(tool("a", running)), 100))
        assertEquals(thinking, submit(listOf(tool("a")), 200))
        assertEquals(settledTool("a"), submit(listOf(tool("a")), 1_200))
    }

    @Test
    fun railVisibleStepsDropHiddenStepsAndRecomputeBranches() {
        val steps = SessionChatStepRail.withBranches(listOf(tool("a", running), tool("b", running), tool("c", running)))
        val visible = SessionChatStepRail.visibleSteps(steps, setOf("b"))
        assertEquals(listOf("a", "c"), visible.map { it.id })
        assertEquals(listOf(false, true), visible.map { it.isBranch })
        assertEquals(steps, SessionChatStepRail.visibleSteps(steps, emptySet()))
    }
}
