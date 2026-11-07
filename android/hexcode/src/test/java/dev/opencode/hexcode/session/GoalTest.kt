package dev.opencode.hexcode.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The goal line, the sheet's box and buttons, and the goal text rules (app-plan.md, Goal and Ask › Testing). */
class GoalTest {
    private fun goal(
        status: GoalStatus,
        rounds: Int = 0,
        reason: String? = null,
    ) = Goal("g1", "Ship the login screen", status, reason, rounds)

    @Test
    fun noGoalReadsSetTodaysGoalWhileIdleAndNothingWhileBusy() {
        assertEquals(GoalLine.Empty, GoalLine.of(null, busy = false))
        assertNull(GoalLine.of(null, busy = true))
    }

    @Test
    fun anActiveGoalShowsTheServersRoundsWithStartingAtZero() {
        assertEquals(GoalLine.Phase(GoalPhase.Starting), GoalLine.of(goal(GoalStatus.Active, 0), busy = true))
        assertEquals(GoalLine.Phase(GoalPhase.Round(1, 50)), GoalLine.of(goal(GoalStatus.Active, 1), busy = true))
        assertEquals(GoalLine.Phase(GoalPhase.Round(50, 50)), GoalLine.of(goal(GoalStatus.Active, 50), busy = true))
    }

    @Test
    fun anActiveGoalWhileIdleIsWaiting() {
        assertEquals(GoalLine.Phase(GoalPhase.Waiting), GoalLine.of(goal(GoalStatus.Active, 3), busy = false))
        assertEquals(GoalLine.Phase(GoalPhase.Waiting), GoalLine.of(goal(GoalStatus.Active, 0), busy = false))
    }

    @Test
    fun pausedAndCompleteReadTheSameBusyOrIdle() {
        for (busy in listOf(true, false)) {
            assertEquals(GoalLine.Phase(GoalPhase.Paused), GoalLine.of(goal(GoalStatus.Paused, 50, "Reached the limit of 50 rounds"), busy))
            assertEquals(GoalLine.Phase(GoalPhase.Done), GoalLine.of(goal(GoalStatus.Complete, 2, "Verified"), busy))
        }
    }

    @Test
    fun theReasonShowsOnlyForPausedAndDone() {
        assertNull(goal(GoalStatus.Active, 3, "Only one.txt exists").shownReason)
        assertEquals("Stopped by the user", goal(GoalStatus.Paused, 3, "Stopped by the user").shownReason)
        assertEquals("Verified", goal(GoalStatus.Complete, 2, "Verified").shownReason)
        assertNull(goal(GoalStatus.Paused, 3, "  ").shownReason)
    }

    @Test
    fun theSheetOffersPauseOnlyWhenActiveAndResumeOnlyWhenPaused() {
        assertEquals(listOf(GoalAction.Say), GoalAction.of(null))
        assertEquals(listOf(GoalAction.Clear, GoalAction.Pause, GoalAction.Change), GoalAction.of(goal(GoalStatus.Active)))
        assertEquals(listOf(GoalAction.Clear, GoalAction.Resume, GoalAction.Change), GoalAction.of(goal(GoalStatus.Paused)))
        assertEquals(listOf(GoalAction.Clear, GoalAction.New), GoalAction.of(goal(GoalStatus.Complete)))
    }

    @Test
    fun eachCommandSendsItsText() {
        assertEquals("Fix the login", GoalCommand.Set("Fix the login").text)
        assertEquals("pause", GoalCommand.Pause.text)
        assertEquals("resume", GoalCommand.Resume.text)
        assertEquals("clear", GoalCommand.Clear.text)
    }

    @Test
    fun aSingleCommandWordIsRefusedButALongerGoalIsSent() {
        assertEquals(GoalRefusal.Word, GoalText.refusal("Pause"))
        assertEquals(GoalRefusal.Word, GoalText.refusal("resume."))
        assertEquals(GoalRefusal.Word, GoalText.refusal("CLEAR!"))
        assertEquals(GoalRefusal.Word, GoalText.refusal("  clear ?"))
        assertNull(GoalText.refusal("Pause the deploy"))
        assertNull(GoalText.refusal("Clear the cache before the deploy"))
    }

    @Test
    fun overFourThousandCharactersIsRefused() {
        assertNull(GoalText.refusal("a".repeat(4_000)))
        assertEquals(GoalRefusal.TooLong, GoalText.refusal("a".repeat(4_001)))
    }

    @Test
    fun theGoalIsReadFromTheEvent() {
        val data =
            Json
                .parseToJsonElement(
                    """{"id":"32ebdf97","objective":"Create the files","status":"active","reason":"Only one.txt exists",
                    "rounds":1,"maxRounds":50,"stalls":0}""",
                ).jsonObject
        assertEquals(Goal("32ebdf97", "Create the files", GoalStatus.Active, "Only one.txt exists", 1, 50), Goal.read(data))
    }

    @Test
    fun anUnknownStatusCountsAsPaused() {
        assertEquals(GoalStatus.Paused, GoalStatus.of("blocked"))
        assertEquals(GoalStatus.Complete, GoalStatus.of("complete"))
    }
}
