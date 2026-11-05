package dev.opencode.hexcode.session

import dev.opencode.hexcode.firstrun.ModelChoice
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * app-plan.md, Testing, M2 cases: "no session today → created, rules added, then the stream opens; one today →
 * reused, nothing created, the rules put again; two today → the newest; a session from yesterday → untouched, a new
 * one created, the notice shown; the date changes in the background → new session on return". The stream and the
 * return to the foreground are in TodaySessionTest.
 */
class DailySessionTest {
    private val today = LocalDate.of(2026, 11, 5)
    private val yesterday = today.minusDays(1)
    private val project = "/Users/me/Projects/hexcode"
    private val model = ModelChoice("opencode", "big-pickle", "low")

    private fun listing(
        id: String,
        title: String = "2026-11-05",
        directory: String = project,
        busy: Long? = null,
    ) = SessionListing(id, title, directory, updatedAt = 1, busySince = busy)

    private class FakeApi(
        /** Pages, in order; the server's newest-first order inside each. */
        val pages: MutableList<List<SessionListing>> = mutableListOf(),
    ) : DailySessionApi {
        val calls = mutableListOf<String>()
        var failRules = false
        var failList = false
        var createdModel: ModelChoice? = null
        private var next = 1

        override suspend fun sessionsPage(
            directory: String,
            search: String,
            limit: Int,
            cursor: String?,
        ): SessionListingPage {
            calls += "list $search ${cursor ?: "-"}"
            if (failList) error("unreachable")
            val index = cursor?.removePrefix("page")?.toInt() ?: 0
            val page = pages.getOrNull(index).orEmpty()
            return SessionListingPage(page, next = "page${index + 1}")
        }

        override suspend fun create(
            title: String,
            directory: String,
            model: ModelChoice?,
        ): SessionListing {
            val id = "new${next++}"
            calls += "create $title"
            createdModel = model
            val session = SessionListing(id, title, directory, updatedAt = 9_999)
            // The newest comes first.
            if (pages.isEmpty()) pages.add(listOf(session)) else pages[0] = listOf(session) + pages[0]
            return session
        }

        override suspend fun putMessageRules(sessionId: String) {
            calls += "rules $sessionId"
            if (failRules) error("HTTP 500")
        }
    }

    private class Memory(
        var last: LocalDate?,
    ) : DailySessionMemory {
        override suspend fun lastOpenedOn() = last

        override suspend fun setLastOpenedOn(date: LocalDate) {
            last = date
        }
    }

    @Test
    fun theTitleIsTheLocalDate() {
        assertEquals("2026-11-05", DailySession.title(today))
        assertEquals("2026-01-09", DailySession.title(LocalDate.of(2026, 1, 9)))
    }

    @Test
    fun noSessionTodayIsCreatedWithTheModelAndGetsTheRules() =
        runTest {
            val api = FakeApi(mutableListOf(listOf(listing("old", "2026-11-04"))))
            val memory = Memory(yesterday)
            val result = DailySessionSetup(api, memory).open(today, project, model)
            assertEquals("new1", (result as DailySessionResult.Opened).session.id)
            assertTrue(result.created)
            assertEquals(model, api.createdModel)
            assertEquals(listOf("list 2026-11-05 -", "create 2026-11-05", "rules new1"), api.calls)
            assertEquals(today, memory.last)
        }

    @Test
    fun oneTodayIsReusedNothingIsCreatedAndTheRulesArePutAgain() =
        runTest {
            val api = FakeApi(mutableListOf(listOf(listing("s1", busy = 7))))
            val result = DailySessionSetup(api, Memory(today)).open(today, project, model) as DailySessionResult.Opened
            assertEquals("s1", result.session.id)
            assertFalse(result.created)
            assertFalse(result.newDay)
            assertEquals(7L, result.session.busySince)
            assertEquals(listOf("list 2026-11-05 -", "rules s1"), api.calls)
        }

    @Test
    fun twoTodayTakesTheNewestWhichTheServerListsFirst() {
        val sessions = listOf(listing("newest"), listing("older"))
        assertEquals(DailySessionPlan.Reuse(listing("newest")), DailySession.plan(today, project, today, sessions))
    }

    @Test
    fun aSessionFromYesterdayIsLeftAndANewOneCreatedWithTheNotice() =
        runTest {
            val api = FakeApi(mutableListOf(listOf(listing("old", "2026-11-04"))))
            val result = DailySessionSetup(api, Memory(yesterday)).open(today, project, model) as DailySessionResult.Opened
            assertTrue(result.created && result.newDay)
            assertFalse(api.calls.any { it.contains("old") })
        }

    @Test
    fun theFirstDayAfterFirstRunShowsNoNotice() {
        assertEquals(DailySessionPlan.Create("2026-11-05", newDay = false), DailySession.plan(today, project, null, emptyList()))
    }

    @Test
    fun todaysSessionGoneMissingIsCreatedAgainWithoutTheNotice() {
        assertEquals(DailySessionPlan.Create("2026-11-05", newDay = false), DailySession.plan(today, project, today, emptyList()))
    }

    @Test
    fun theDirectoryMustMatchExactlyAsTheServerCompares() {
        val sessions =
            listOf(
                listing("other", directory = "/Users/me/Projects/notes"),
                listing("slash", directory = "$project/"),
                listing("not-a-date", title = "Fix the login 2026-11-05"),
            )
        assertNull(DailySession.match(today, project, sessions))
        assertEquals("slash", DailySession.match(today, "$project/", sessions)?.id)
    }

    @Test
    fun aFullPageWithoutAMatchFollowsTheCursor() =
        runTest {
            val noise = (1..DailySessionSetup.PAGE_SIZE).map { listing("x$it", title = "Notes 2026-11-05") }
            val api = FakeApi(mutableListOf(noise, listOf(listing("s1"))))
            val result = DailySessionSetup(api, Memory(today)).open(today, project, model) as DailySessionResult.Opened
            assertEquals("s1", result.session.id)
            assertEquals(listOf("list 2026-11-05 -", "list 2026-11-05 page1", "rules s1"), api.calls)
        }

    @Test
    fun aShortPageWithoutAMatchIsTheEnd() =
        runTest {
            val api = FakeApi(mutableListOf(listOf(listing("x", title = "Notes 2026-11-05"))))
            DailySessionSetup(api, Memory(today)).open(today, project, model)
            assertEquals(listOf("list 2026-11-05 -", "create 2026-11-05", "rules new1"), api.calls)
        }

    @Test
    fun aFailureIsSilentAndSavesNoDate() =
        runTest {
            val api = FakeApi().apply { failList = true }
            val memory = Memory(yesterday)
            assertEquals(DailySessionResult.Failed, DailySessionSetup(api, memory).open(today, project, model))
            assertEquals(yesterday, memory.last)
        }

    @Test
    fun tryAgainAfterTheRulesFailedFindsTheSessionPutsTheRulesAndStillShowsTheNotice() =
        runTest {
            val api = FakeApi().apply { failRules = true }
            val memory = Memory(yesterday)
            val setup = DailySessionSetup(api, memory)
            assertEquals(DailySessionResult.Failed, setup.open(today, project, model))
            api.failRules = false
            api.calls.clear()
            val result = setup.open(today, project, model) as DailySessionResult.Opened
            assertEquals("new1", result.session.id)
            assertFalse(result.created)
            assertTrue(result.newDay)
            // Found again, so never a second session.
            assertEquals(listOf("list 2026-11-05 -", "rules new1"), api.calls)
            assertEquals(today, memory.last)
        }

    @Test
    fun isNewDayOnlyAfterAnEarlierDate() {
        assertTrue(DailySession.isNewDay(yesterday, today))
        assertFalse(DailySession.isNewDay(today, today))
        assertFalse(DailySession.isNewDay(null, today))
        // A clock set back doesn't count as a new day.
        assertFalse(DailySession.isNewDay(today.plusDays(1), today))
    }
}
