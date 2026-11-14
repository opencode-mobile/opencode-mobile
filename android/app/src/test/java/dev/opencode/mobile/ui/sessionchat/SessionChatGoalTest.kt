package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.goal.SessionGoal
import dev.opencode.mobile.domain.goal.SessionGoalAction
import dev.opencode.mobile.domain.goal.SessionGoalChange
import dev.opencode.mobile.domain.goal.SessionGoalRepository
import dev.opencode.mobile.domain.goal.SessionGoalStatus
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.ui.components.OpenCodeSessionBranchChangedFile
import dev.opencode.mobile.ui.components.OpenCodeSessionBranchSummary
import dev.opencode.mobile.ui.sessionchat.region.SessionChatGoal
import dev.opencode.mobile.ui.sessionchat.region.SessionChatGoalRegionState
import dev.opencode.mobile.ui.text.english
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionChatGoalTest {
    private val repository = FakeGoalRepository()
    private val connection = MutableStateFlow(EventStreamConnectionState.Connected)

    // MARK: Read and follow

    @Test
    fun bindReadsTheGoalAtTheSessionLocation() =
        runTest {
            repository.goals += goal()
            val region = region()

            assertEquals(goal(), region.state.value.goal)
            assertEquals(listOf("ses_1" to "/Users/example/project"), repository.reads)
        }

    @Test
    fun v1ShowsNoGoalAndReadsNothing() =
        runTest {
            repository.available = false
            val region = region()

            assertNull(region.state.value.goal)
            assertTrue(repository.reads.isEmpty())
        }

    @Test
    fun aFailingReadShowsNoGoalAndRetriesQuietlyAfterOneTwoAndFourSeconds() =
        runTest {
            repository.failReads = true
            val region = region()
            assertEquals(1, repository.reads.size)
            assertNull(region.state.value.goal)
            assertNull(region.state.value.notice)

            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, repository.reads.size)
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(3, repository.reads.size)
            repository.failReads = false
            repository.goals += goal()
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals(4, repository.reads.size)
            assertEquals(goal(), region.state.value.goal)
        }

    @Test
    fun retriesStopAfterTheThirdFailure() =
        runTest {
            repository.failReads = true
            region()

            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(4, repository.reads.size)
        }

    @Test
    fun aChangeEventForThisSessionReplacesTheGoal() =
        runTest {
            repository.goals += goal()
            val region = region()

            repository.changes.emit(SessionGoalChange("ses_other", null))
            runCurrent()
            assertEquals(goal(), region.state.value.goal)

            val paused = goal(status = SessionGoalStatus.Paused, reason = "Paused by the user")
            repository.changes.emit(SessionGoalChange("ses_1", paused))
            runCurrent()
            assertEquals(paused, region.state.value.goal)

            repository.changes.emit(SessionGoalChange("ses_1", null))
            runCurrent()
            assertNull(region.state.value.goal)
        }

    @Test
    fun aChangeThatLandsDuringAReadWinsOverTheOlderReply() =
        runTest {
            val reply = CompletableDeferred<SessionGoal?>()
            repository.pendingRead = reply
            val region = region()

            val changed = goal(rounds = 4)
            repository.changes.emit(SessionGoalChange("ses_1", changed))
            runCurrent()
            reply.complete(goal(rounds = 3))
            runCurrent()

            assertEquals(changed, region.state.value.goal)
        }

    @Test
    fun reconnectingDimsTheGoalAndReadsItAgainOnceConnected() =
        runTest {
            repository.goals += goal()
            val region = region()

            connection.value = EventStreamConnectionState.Reconnecting
            runCurrent()
            assertTrue(region.state.value.isStale)
            assertEquals(goal(), region.state.value.goal)

            repository.goals += goal(rounds = 5)
            connection.value = EventStreamConnectionState.Connected
            runCurrent()
            assertFalse(region.state.value.isStale)
            assertEquals(goal(rounds = 5), region.state.value.goal)
            assertEquals(2, repository.reads.size)
        }

    @Test
    fun refreshReadsAgainWhenTheSessionOpensAgain() =
        runTest {
            repository.goals += goal()
            val region = region()
            repository.goals += goal(rounds = 6)

            region.refresh()
            runCurrent()

            assertEquals(goal(rounds = 6), region.state.value.goal)
        }

    // MARK: Actions

    @Test
    fun anActionShowsProgressUntilTheGoalChangedEvent() =
        runTest {
            repository.goals += goal()
            val region = region()

            region.send(SessionGoalAction.Pause)
            runCurrent()
            assertEquals(listOf("ses_1" to SessionGoalAction.Pause), repository.sent)
            assertEquals(SessionGoalAction.Pause, region.state.value.pending)

            // A second tap while the first is in flight sends nothing.
            region.send(SessionGoalAction.Pause)
            runCurrent()
            assertEquals(1, repository.sent.size)

            val paused = goal(status = SessionGoalStatus.Paused, reason = "Paused by the user")
            repository.changes.emit(SessionGoalChange("ses_1", paused))
            runCurrent()
            assertNull(region.state.value.pending)
            assertEquals(paused, region.state.value.goal)
            assertNull(region.state.value.notice)
        }

    @Test
    fun anActionFreesItsButtonAfterFifteenSecondsWithoutAChange() =
        runTest {
            repository.goals += goal()
            val region = region()

            region.send(SessionGoalAction.Pause)
            advanceTimeBy(14_999)
            runCurrent()
            assertEquals(SessionGoalAction.Pause, region.state.value.pending)

            advanceTimeBy(1)
            runCurrent()
            assertNull(region.state.value.pending)
            // The timeout reads the goal again; changes may have been missed.
            assertEquals(2, repository.reads.size)
        }

    @Test
    fun aFailedActionShowsTheServerMessageAndReadsTheGoalAgain() =
        runTest {
            repository.goals += goal()
            val region = region()
            repository.sendError = IllegalStateException("No active goal to pause.")

            region.send(SessionGoalAction.Pause)
            runCurrent()

            assertNull(region.state.value.pending)
            assertEquals(UiText.resource(R.string.session_chat_goal_pause_failed), region.state.value.notice)
            assertEquals(2, repository.reads.size)

            region.clearNotice()
            assertNull(region.state.value.notice)
        }

    @Test
    fun actionsAreOffWhileReconnecting() =
        runTest {
            repository.goals += goal()
            val region = region()
            connection.value = EventStreamConnectionState.Reconnecting
            runCurrent()

            region.send(SessionGoalAction.Clear)
            runCurrent()

            assertTrue(repository.sent.isEmpty())
        }

    @Test
    fun theSheetClosesWhenTheGoalIsCleared() =
        runTest {
            repository.goals += goal()
            val region = region()
            region.openSheet()
            assertTrue(region.state.value.sheetOpen)

            repository.changes.emit(SessionGoalChange("ses_1", null))
            runCurrent()

            assertFalse(region.state.value.sheetOpen)
        }

    // MARK: Screen state

    @Test
    fun resolveReadsWorkingWaitingPausedAndMet() {
        val region = SessionChatGoalRegionState(goal = goal())

        assertEquals(SessionChatGoalActivity.Working, resolve(region, busy = true)?.activity)
        // Idle between turns (the reviewer checks): still working, without a spinner on the pill.
        assertEquals(SessionChatGoalActivity.Working, resolve(region)?.activity)
        assertEquals(SessionChatGoalWait.Permission, resolve(region, busy = true, permission = true)?.wait)
        assertEquals(SessionChatGoalActivity.Waiting, resolve(region, busy = true, permission = true)?.activity)
        assertEquals(SessionChatGoalWait.Shell("./gradlew connectedCheck"), resolve(region, shell = "./gradlew connectedCheck")?.wait)
        assertEquals(SessionChatGoalWait.Subagent, resolve(region, subagent = true)?.wait)
        assertEquals(
            SessionChatGoalActivity.Paused,
            resolve(region.copy(goal = goal(status = SessionGoalStatus.Paused)))?.activity,
        )
        assertEquals(SessionChatGoalActivity.Met, resolve(region.copy(goal = goal(status = SessionGoalStatus.Complete)))?.activity)
    }

    @Test
    fun resolveHidesTheGoalInASubagentSessionAndWithoutOne() {
        assertNull(resolve(SessionChatGoalRegionState(goal = goal()), child = true))
        assertNull(resolve(SessionChatGoalRegionState()))
    }

    @Test
    fun resolveTurnsActionsOffWhileStalePendingOrReadOnly() {
        val region = SessionChatGoalRegionState(goal = goal())

        assertTrue(resolve(region)!!.actionsEnabled)
        assertFalse(resolve(region.copy(isStale = true))!!.actionsEnabled)
        assertFalse(resolve(region.copy(pending = SessionGoalAction.Pause))!!.actionsEnabled)
        assertFalse(resolve(region, canMutate = false)!!.actionsEnabled)
    }

    @Test
    fun pauseTitlesAndReasonLines() {
        assertEquals("You paused the goal", pausedTitle("Paused by the user"))
        assertNull(SessionChatGoalCopy.pauseReason(goal(status = SessionGoalStatus.Paused, reason = "Paused by the user")))
        assertEquals("Goal paused after 50 rounds", pausedTitle("Reached the limit of 50 rounds"))
        assertEquals("Goal paused: no progress for 3 rounds", pausedTitle("Not making progress: 1 test fails"))
        assertEquals(
            "Not making progress: 1 test fails",
            SessionChatGoalCopy.pauseReason(goal(status = SessionGoalStatus.Paused, reason = "Not making progress: 1 test fails")),
        )
        assertEquals("Goal paused", pausedTitle("Needs a decision on the sort order"))
    }

    @Test
    fun theComposerAsksForAReplyOnlyForTheReviewersOwnWords() {
        val reviewer = SessionChatGoalRegionState(goal = goal(status = SessionGoalStatus.Paused, reason = "Needs a decision"))
        val byUser = SessionChatGoalRegionState(goal = goal(status = SessionGoalStatus.Paused, reason = "Paused by the user"))

        assertTrue(SessionChatGoalCopy.resumesAfterReply(resolve(reviewer)))
        assertFalse(SessionChatGoalCopy.resumesAfterReply(resolve(byUser)))
        assertFalse(SessionChatGoalCopy.resumesAfterReply(resolve(SessionChatGoalRegionState(goal = goal()))))
    }

    @Test
    fun progressLines() {
        assertEquals("First turn · round 0 of 50", SessionChatGoalCopy.progress(goal(rounds = 0)).english())
        assertEquals("Round 3 of 50", SessionChatGoalCopy.progress(goal()).english())
        assertEquals("Met in round 4", SessionChatGoalCopy.progress(goal(status = SessionGoalStatus.Complete, rounds = 4)).english())
        assertNull(SessionChatGoalCopy.stalls(goal(stalls = 0)))
        assertEquals("No progress for 2 rounds; pauses after 3", SessionChatGoalCopy.stalls(goal(stalls = 2))?.english())
        assertEquals("Make the tests pass", SessionChatGoalCopy.goalSetValue("Goal: Make the tests pass"))
    }

    @Test
    fun theGoalCommandRowWarnsBeforeReplacing() {
        assertNull(SessionChatGoalCopy.commandSubtitle(null))
        assertEquals("Replaces your current goal", SessionChatGoalCopy.commandSubtitle(goal())?.english())
        assertEquals(
            "Replaces your current goal (met)",
            SessionChatGoalCopy.commandSubtitle(goal(status = SessionGoalStatus.Complete))?.english(),
        )
    }

    // MARK: Status row

    @Test
    fun statusRowIsPlacedByUpdatedAtAfterEverythingSentByThen() {
        val messages = listOf(message("u1", 10), message("a1", 20), message("u2", 40))
        val paused = resolve(SessionChatGoalRegionState(goal = goal(status = SessionGoalStatus.Paused, updatedAt = 30)))

        val rows = SessionChatGoalTimeline.withStatusRow(messages, paused)

        assertEquals(listOf("u1", "a1", "goal-status:goal_1", "u2"), rows.map { it.id })
        assertEquals(MessageRole.Notice, rows[2].role)
        assertTrue(SessionChatGoalTimeline.isStatusRow(rows[2]))
        // Paused after everything: last.
        val late = resolve(SessionChatGoalRegionState(goal = goal(status = SessionGoalStatus.Complete, updatedAt = 50)))
        assertEquals("goal-status:goal_1", SessionChatGoalTimeline.withStatusRow(messages, late).last().id)
    }

    @Test
    fun anActiveGoalOrNoGoalHasNoStatusRow() {
        val messages = listOf(message("u1", 10))

        assertSame(messages, SessionChatGoalTimeline.withStatusRow(messages, resolve(SessionChatGoalRegionState(goal = goal()))))
        assertSame(messages, SessionChatGoalTimeline.withStatusRow(messages, null))
    }

    // MARK: Pill slot

    @Test
    fun theGoalPillComesFirstOfThree() {
        val branch =
            SessionBranchPillUiState(
                OpenCodeSessionBranchSummary("main", files = listOf(OpenCodeSessionBranchChangedFile("a.kt", 1, 2))),
            )
        val goal = resolve(SessionChatGoalRegionState(goal = goal()))!!

        assertEquals(
            listOf(SessionChatContextItem.Goal(goal), SessionChatContextItem.Branch(branch), SessionChatContextItem.Todos(2, 5)),
            SessionChatPillSlots.context(2, 5, branch, goal),
        )
        assertEquals(listOf(SessionChatContextItem.Goal(goal)), SessionChatPillSlots.context(null, null, null, goal))
    }

    // MARK: Helpers

    private fun TestScope.region(): SessionChatGoal =
        SessionChatGoal(repository, connection, backgroundScope).also {
            it.bind("ses_1", "/Users/example/project")
            runCurrent()
        }

    private fun resolve(
        region: SessionChatGoalRegionState,
        busy: Boolean = false,
        permission: Boolean = false,
        shell: String? = null,
        subagent: Boolean = false,
        child: Boolean = false,
        canMutate: Boolean = true,
    ) = SessionChatGoalUiState.resolve(
        region = region,
        isChildSession = child,
        isSessionBusy = busy,
        hasPermission = permission,
        hasQuestion = false,
        runningShellCommand = shell,
        hasRunningSubagent = subagent,
        canMutate = canMutate,
    )

    private fun pausedTitle(reason: String): String =
        SessionChatGoalCopy.pauseTitle(goal(status = SessionGoalStatus.Paused, reason = reason)).english()

    private fun message(
        id: String,
        sentAt: Long,
    ) = SessionChatMessageItem(id = id, role = MessageRole.User, text = id, metadataLabel = "", sentAtEpochMs = sentAt)

    private fun goal(
        status: SessionGoalStatus = SessionGoalStatus.Active,
        rounds: Int = 3,
        reason: String? = null,
        stalls: Int? = null,
        updatedAt: Long = 1,
    ) = SessionGoal(
        id = "goal_1",
        objective = "Make the tests pass",
        status = status,
        reason = reason,
        rounds = rounds,
        maxRounds = 50,
        stalls = stalls,
        updatedAtEpochMs = updatedAt,
    )

    private class FakeGoalRepository : SessionGoalRepository {
        var available = true
        var failReads = false
        var pendingRead: CompletableDeferred<SessionGoal?>? = null
        var sendError: Throwable? = null

        /** Answers in order; the last one repeats. */
        val goals = mutableListOf<SessionGoal?>()
        val reads = mutableListOf<Pair<String, String?>>()
        val sent = mutableListOf<Pair<String, SessionGoalAction>>()
        val changes = MutableSharedFlow<SessionGoalChange>()

        override suspend fun isAvailable(): Boolean = available

        override suspend fun goal(
            sessionId: String,
            directory: String?,
        ): SessionGoal? {
            reads += sessionId to directory
            pendingRead?.let { reply ->
                pendingRead = null
                return reply.await()
            }
            if (failReads) error("no goal plugin")
            return goals.lastOrNull()
        }

        override suspend fun send(
            sessionId: String,
            action: SessionGoalAction,
        ) {
            sent += sessionId to action
            sendError?.let { throw it }
        }

        override fun changes(): Flow<SessionGoalChange> = changes
    }
}
