package dev.opencode.hexcode.session

import org.junit.Assert.assertEquals
import org.junit.Test

/** app-plan.md, Testing, M2 cases: "stream down beats everything, pending input beats busy, busy beats idle". */
class AgentStatusTest {
    @Test
    fun streamDownBeatsEverything() {
        assertEquals(AgentStatus.Reconnecting, AgentStatus.of(streamUp = false, pendingInput = true, busySince = 10))
        assertEquals(AgentStatus.Reconnecting, AgentStatus.of(streamUp = false, pendingInput = false, busySince = null))
    }

    @Test
    fun pendingInputBeatsBusy() {
        assertEquals(AgentStatus.NeedsInput, AgentStatus.of(streamUp = true, pendingInput = true, busySince = 10))
        assertEquals(AgentStatus.NeedsInput, AgentStatus.of(streamUp = true, pendingInput = true, busySince = null))
    }

    @Test
    fun busyBeatsIdle() {
        assertEquals(AgentStatus.Working(10), AgentStatus.of(streamUp = true, pendingInput = false, busySince = 10))
        assertEquals(AgentStatus.Idle, AgentStatus.of(streamUp = true, pendingInput = false, busySince = null))
    }

    @Test
    fun workingIsTimedFromTheStartOfTheTurn() {
        val working = AgentStatus.Working(since = 1_000)
        assertEquals("0:00", working.clock(1_000))
        assertEquals("0:07", working.clock(8_999))
        assertEquals("4:12", working.clock(1_000 + 252_000))
        assertEquals("72:03", working.clock(1_000 + (72 * 60 + 3) * 1000L))
        // A clock that is a little behind the turn's start never shows a negative time.
        assertEquals("0:00", working.clock(0))
    }
}
