package dev.opencode.hexcode.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class TodaySessionTest {
    private val today = LocalDate.of(2026, 11, 5)
    private val project = "/Users/me/Projects/hexcode"

    private class Api(
        val listed: List<SessionListing>,
        var fails: Boolean = false,
    ) : DailySessionApi {
        override suspend fun sessions(directory: String): List<SessionListing> {
            if (fails) error("unreachable")
            return listed
        }

        override suspend fun create(
            title: String,
            directory: String,
        ) = "new"

        override suspend fun putInstruction(
            sessionId: String,
            entryId: String,
            text: String,
        ) = Unit
    }

    private class Memory(
        var last: LocalDate?,
    ) : DailySessionMemory {
        override suspend fun lastOpenedOn() = last

        override suspend fun setLastOpenedOn(date: LocalDate) {
            last = date
        }
    }

    private class Source : SummarySource {
        var requests = 0

        override suspend fun summarise(prompt: String): String {
            requests += 1
            return "## Task\nNow: Working."
        }
    }

    private fun TestScope.session(
        api: Api,
        memory: Memory,
        source: Source,
    ) = TodaySession(
        backgroundScope,
        DailySessionSetup(api),
        memory,
        SummaryRefresher(backgroundScope, source) { 0 },
        { project },
        { today },
    )

    @Test
    fun aNewDayShowsTheEmptyCardAndTheNoticeWithoutARequest() =
        runTest {
            val memory = Memory(today.minusDays(1))
            val source = Source()
            val session = session(Api(emptyList()), memory, source)
            session.open()
            runCurrent()
            val state = session.state.value
            assertEquals(SessionSetup.Ready("new"), state.setup)
            assertEquals(StatusSummary.Empty("Thursday 5 November"), state.summary.summary)
            assertTrue(state.newDayNotice)
            assertEquals(0, source.requests)
            assertEquals(today, memory.last)
            session.newDayShown()
            runCurrent()
            assertFalse(session.state.value.newDayNotice)
        }

    @Test
    fun todaysSessionIsReusedAndSummarised() =
        runTest {
            val source = Source()
            val session = session(Api(listOf(SessionListing("s1", "2026-11-05", project, 1))), Memory(today), source)
            session.open()
            runCurrent()
            assertEquals(SessionSetup.Ready("s1"), session.state.value.setup)
            assertEquals(1, source.requests)
            assertFalse(session.state.value.newDayNotice)
        }

    @Test
    fun aFailureShowsTryAgainWhichOpensIt() =
        runTest {
            val api = Api(listOf(SessionListing("s1", "2026-11-05", project, 1)), fails = true)
            val session = session(api, Memory(today), Source())
            session.open()
            runCurrent()
            assertEquals(SessionSetup.Failed, session.state.value.setup)
            api.fails = false
            session.retry()
            runCurrent()
            assertEquals(SessionSetup.Ready("s1"), session.state.value.setup)
        }

    @Test
    fun theTopBarFollowsTheAgent() =
        runTest {
            val session = session(Api(emptyList()), Memory(null), Source())
            session.updateAgent { it.copy(busySince = 5) }
            runCurrent()
            assertEquals(AgentStatus.Working(5), session.state.value.agent)
            session.updateAgent { it.copy(pendingInput = PendingInput(PendingInput.Kind.Question, "Which one?")) }
            runCurrent()
            assertEquals(AgentStatus.NeedsInput, session.state.value.agent)
            session.updateAgent { it.copy(streamUp = false) }
            runCurrent()
            assertEquals(AgentStatus.Reconnecting, session.state.value.agent)
        }
}
