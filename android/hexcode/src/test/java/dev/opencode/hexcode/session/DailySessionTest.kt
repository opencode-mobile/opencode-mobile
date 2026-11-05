package dev.opencode.hexcode.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * app-plan.md, Testing, M2 cases: "no session today → created, rules added, then the stream opens; one today →
 * reused, nothing created; two today → the newest; a session from yesterday → untouched, a new one created, the
 * notice shown; the date changes in the background → new session on return".
 */
class DailySessionTest {
    private val today = LocalDate.of(2026, 11, 5)
    private val yesterday = today.minusDays(1)
    private val project = "/Users/me/Projects/hexcode"

    private class FakeApi(
        val listed: MutableList<SessionListing> = mutableListOf(),
    ) : DailySessionApi {
        val calls = mutableListOf<String>()
        var failRules = false
        var failList = false
        private var next = 1

        override suspend fun sessions(directory: String): List<SessionListing> {
            calls += "list $directory"
            if (failList) error("unreachable")
            return listed.toList()
        }

        override suspend fun create(
            title: String,
            directory: String,
        ): String {
            val id = "new${next++}"
            calls += "create $title"
            listed += SessionListing(id, title, directory, updatedAt = 9_999)
            return id
        }

        override suspend fun putInstruction(
            sessionId: String,
            entryId: String,
            text: String,
        ) {
            calls += "rules $sessionId $entryId"
            if (failRules) error("HTTP 500")
            assertEquals(SessionPrompts.MESSAGE_RULES, text)
        }
    }

    @Test
    fun theTitleIsTheLocalDate() {
        assertEquals("2026-11-05", DailySession.title(today))
        assertEquals("2026-01-09", DailySession.title(LocalDate.of(2026, 1, 9)))
    }

    @Test
    fun noSessionTodayIsCreatedAndGetsTheRules() =
        runTest {
            val api = FakeApi(mutableListOf(SessionListing("old", "2026-11-04", project, 5)))
            val result = DailySessionSetup(api).open(today, project, lastOpenedOn = yesterday)
            assertEquals(DailySessionResult.Opened("new1", created = true, newDay = true), result)
            // Listed, then created, then the rules; the caller opens the stream after Opened.
            assertEquals(listOf("list $project", "create 2026-11-05", "rules new1 message-rules"), api.calls)
        }

    @Test
    fun oneTodayIsReusedAndNothingIsCreated() =
        runTest {
            val api = FakeApi(mutableListOf(SessionListing("s1", "2026-11-05", project, 5)))
            val result = DailySessionSetup(api).open(today, project, lastOpenedOn = today)
            assertEquals(DailySessionResult.Opened("s1", created = false, newDay = false), result)
            assertEquals(listOf("list $project"), api.calls)
        }

    @Test
    fun twoTodayTakesTheNewest() {
        val sessions =
            listOf(
                SessionListing("older", "2026-11-05", project, updatedAt = 100),
                SessionListing("newest", "2026-11-05", project, updatedAt = 300),
                SessionListing("middle", "2026-11-05", project, updatedAt = 200),
            )
        assertEquals(DailySessionPlan.Reuse("newest"), DailySession.plan(today, project, today, sessions))
    }

    @Test
    fun aSessionFromYesterdayIsLeftAndANewOneCreatedWithTheNotice() =
        runTest {
            val api = FakeApi(mutableListOf(SessionListing("old", "2026-11-04", project, 5)))
            val result = DailySessionSetup(api).open(today, project, lastOpenedOn = yesterday)
            assertEquals(DailySessionResult.Opened("new1", created = true, newDay = true), result)
            assertFalse(api.calls.any { it.contains("old") })
        }

    @Test
    fun theDateChangingInTheBackgroundMeansANewSessionOnReturn() =
        runTest {
            val api = FakeApi(mutableListOf(SessionListing("s1", "2026-11-04", project, 5)))
            val setup = DailySessionSetup(api)
            assertEquals(DailySessionResult.Opened("s1", false, false), setup.open(yesterday, project, lastOpenedOn = yesterday))
            // Back in front after midnight.
            assertTrue(DailySession.needsCheck(openedOn = yesterday, today = today))
            assertEquals(DailySessionResult.Opened("new1", true, true), setup.open(today, project, lastOpenedOn = yesterday))
            // Back again later the same day: nothing to look up.
            assertFalse(DailySession.needsCheck(openedOn = today, today = today))
        }

    @Test
    fun theFirstDayAfterFirstRunShowsNoNotice() {
        assertEquals(DailySessionPlan.Create("2026-11-05", newDay = false), DailySession.plan(today, project, null, emptyList()))
    }

    @Test
    fun aMissingSessionOnTheSameDayIsCreatedWithoutTheNotice() {
        // Today's session was deleted on the computer, say: it isn't a new day.
        assertEquals(DailySessionPlan.Create("2026-11-05", newDay = false), DailySession.plan(today, project, today, emptyList()))
    }

    @Test
    fun onlyTheChosenProjectCounts() {
        val sessions =
            listOf(
                SessionListing("other", "2026-11-05", "/Users/me/Projects/notes", 500),
                SessionListing("mine", "2026-11-05", "$project/", 100),
                SessionListing("not-a-date", "Fix the login", project, 900),
            )
        assertEquals(DailySessionPlan.Reuse("mine"), DailySession.plan(today, project, today, sessions))
    }

    @Test
    fun aFailureIsSilentAndTryAgainWorks() =
        runTest {
            val api = FakeApi().apply { failList = true }
            val setup = DailySessionSetup(api)
            assertEquals(DailySessionResult.Failed, setup.open(today, project, yesterday))
            api.failList = false
            assertEquals(DailySessionResult.Opened("new1", true, true), setup.open(today, project, yesterday))
        }

    @Test
    fun rulesThatFailedAreAddedOnTryAgainWithTheNoticeStillDue() =
        runTest {
            val api = FakeApi().apply { failRules = true }
            val setup = DailySessionSetup(api)
            assertEquals(DailySessionResult.Failed, setup.open(today, project, yesterday))
            api.failRules = false
            api.calls.clear()
            assertEquals(DailySessionResult.Opened("new1", created = true, newDay = true), setup.open(today, project, yesterday))
            assertEquals(listOf("list $project", "rules new1 message-rules"), api.calls)
        }
}
