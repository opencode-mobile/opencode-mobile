package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.home.HomeProjectGrouping
import dev.opencode.mobile.domain.home.HomeScope
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionDomainAdapter
import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.theme.LetterAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionSearchViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val searches = mutableListOf<Pair<String, String>>()
    private var serverSessions: Map<String, List<Session>> = emptyMap()
    private var serverFails = false
    private val failingDirectories = mutableSetOf<String>()
    private var archiveSupported = true
    private var archivedFails = false
    private val archivedListings = mutableListOf<List<String>>()
    private var archivedSessions: Map<String, List<Session>> =
        mapOf(
            ACME_PATH to
                listOf(
                    Session(id = "acme-old-invoice", title = "Old invoice audit", archivedAtEpochMs = 40),
                    Session(id = "acme-old-login", title = "Login spike", archivedAtEpochMs = 30),
                ),
            BILLING_PATH to listOf(Session(id = "billing-old-invoice", title = "Invoice retries v1", archivedAtEpochMs = 50)),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun open_startsInHomesProjectScopeWithTheHint() =
        runTest(testDispatcher) {
            val model = model()

            model.open(ACME)

            val state = model.uiState.value
            assertTrue(state.open)
            assertEquals(ACME, state.scope.id)
            assertEquals(listOf(HomeScope.ALL_ID, ACME, BILLING), state.scopeOptions.map { it.id })
            assertEquals(SessionSearchContent.Hint, state.content)
        }

    @Test
    fun open_readsAnAddedPathScopeAsItsProject() =
        runTest(testDispatcher) {
            val model = model()

            model.open(ACME_PATH)

            assertEquals(ACME, model.uiState.value.scope.id)
        }

    @Test
    fun typing_showsHomeMatchesAtOnceAndSearchesTheServerAfterTheDebounce() =
        runTest(testDispatcher) {
            val model = model()
            model.open(HomeScope.ALL_ID)

            model.updateQuery("inv")

            assertEquals(listOf("acme-invoice"), resultIds(model))
            assertEquals(0 until 3, (model.uiState.value.content as SessionSearchContent.Results).results.single().match)
            assertTrue(model.uiState.value.busy)
            advanceTimeBy(SessionSearchViewModel.SEARCH_DEBOUNCE_MS - 1)
            runCurrent()
            assertTrue(searches.isEmpty())

            advanceUntilIdle()

            assertEquals(setOf("inv" to ACME_PATH, "inv" to BILLING_PATH), searches.toSet())
            assertFalse(model.uiState.value.busy)
        }

    @Test
    fun typingAgain_restartsTheDebounce() =
        runTest(testDispatcher) {
            val model = model()
            model.open(ACME)

            model.updateQuery("i")
            advanceTimeBy(100)
            model.updateQuery("in")
            advanceUntilIdle()

            assertEquals(listOf("in" to ACME_PATH), searches)
        }

    @Test
    fun serverMatches_joinHomeRowsAndHomeRowsWin() =
        runTest(testDispatcher) {
            serverSessions =
                mapOf(
                    ACME_PATH to
                        listOf(
                            Session(id = "acme-invoice", title = "Invoice export", updatedAtEpochMs = 1),
                            Session(id = "acme-old", title = "Invoice import", updatedAtEpochMs = 50),
                        ),
                )
            val model = model()
            model.open(ACME)

            model.updateQuery("invoice")
            advanceUntilIdle()

            val results = (model.uiState.value.content as SessionSearchContent.Results).results
            assertEquals(listOf("acme-invoice", "acme-old"), results.map { it.session.id })
            // Home's copy keeps its time (and live status), not the server's.
            assertEquals(100L, results.first().session.updatedAtEpochMs)
            assertEquals(ACME, results.last().session.worktree)
        }

    @Test
    fun scopeChange_searchesThatProjectAtOnce() =
        runTest(testDispatcher) {
            val model = model()
            model.open(HomeScope.ALL_ID)
            model.updateQuery("invoice")
            advanceUntilIdle()
            searches.clear()

            model.selectScope(BILLING)
            runCurrent()

            assertEquals(listOf("invoice" to BILLING_PATH), searches)
            assertEquals(BILLING, model.uiState.value.scope.id)
        }

    @Test
    fun noMatch_offersAllProjectsOnlyWhenAnotherProjectMatches() =
        runTest(testDispatcher) {
            val model = model()
            model.open(BILLING)

            model.updateQuery("invoice")
            advanceUntilIdle()
            assertEquals(SessionSearchContent.NoMatch(offerAllProjects = true, offerArchived = true), model.uiState.value.content)

            model.updateQuery("storm")
            advanceUntilIdle()
            assertEquals(SessionSearchContent.NoMatch(offerAllProjects = false), model.uiState.value.content)

            model.updateQuery("invoice")
            model.searchAllProjects()
            assertEquals(HomeScope.ALL_ID, model.uiState.value.scope.id)
            assertEquals(listOf("acme-invoice"), resultIds(model))
        }

    @Test
    fun searching_showsWhileTheServerAnswersAndNothingMatchedYet() =
        runTest(testDispatcher) {
            val model = model()
            model.open(HomeScope.ALL_ID)

            model.updateQuery("storm")

            assertEquals(SessionSearchContent.Searching, model.uiState.value.content)
        }

    @Test
    fun serverFailure_keepsHomeMatchesAndStopsTheSpinner() =
        runTest(testDispatcher) {
            serverFails = true
            val model = model()
            model.open(HomeScope.ALL_ID)

            model.updateQuery("refactor")
            advanceUntilIdle()

            assertEquals(listOf("billing-refactor"), resultIds(model))
            assertFalse(model.uiState.value.busy)
        }

    @Test
    fun escape_clearsTheQueryFirstThenCloses() =
        runTest(testDispatcher) {
            val model = model()
            model.open(HomeScope.ALL_ID)
            model.updateQuery("inv")

            model.escape()
            assertTrue(model.uiState.value.open)
            assertEquals("", model.uiState.value.query)
            assertEquals(SessionSearchContent.Hint, model.uiState.value.content)

            model.escape()
            assertFalse(model.uiState.value.open)
        }

    @Test
    fun openWhileOpen_refocusesAndKeepsTheQuery() =
        runTest(testDispatcher) {
            val model = model()
            model.open(HomeScope.ALL_ID)
            model.updateQuery("inv")
            val focus = model.uiState.value.focusRequest

            model.open(BILLING)

            assertEquals("inv", model.uiState.value.query)
            assertEquals(HomeScope.ALL_ID, model.uiState.value.scope.id)
            assertEquals(focus + 1, model.uiState.value.focusRequest)
        }

    @Test
    fun homeChange_updatesResultsWithLiveRows() =
        runTest(testDispatcher) {
            val model = model()
            model.open(HomeScope.ALL_ID)
            model.updateQuery("invoice")
            advanceUntilIdle()

            val newSession = SessionSummary(id = "acme-new", title = "Invoice totals", updatedAtEpochMs = 200)
            model.homeChanged(home(acmeSessions = acmeSessions + newSession))

            assertEquals(listOf("acme-new", "acme-invoice"), resultIds(model))
        }

    @Test
    fun includeArchived_isLeftOutWhenTheServerCannotListArchivedSessions() =
        runTest(testDispatcher) {
            archiveSupported = false
            val model = model()

            model.open(HomeScope.ALL_ID)
            advanceUntilIdle()

            assertEquals(null, model.uiState.value.archive)
            assertTrue(archivedListings.isEmpty())
        }

    @Test
    fun includeArchived_countsTheScopesArchivedSessionsAndStartsOff() =
        runTest(testDispatcher) {
            val model = model()

            model.open(ACME)
            advanceUntilIdle()

            assertEquals(SessionSearchArchive(included = false, count = 2), model.uiState.value.archive)
            assertEquals(setOf(listOf(ACME_PATH), listOf(BILLING_PATH)), archivedListings.toSet())
            model.selectScope(HomeScope.ALL_ID)
            assertEquals(SessionSearchArchive(included = false, count = 3), model.uiState.value.archive)
        }

    @Test
    fun includeArchived_addsArchivedMatchesInTheirOwnSection() =
        runTest(testDispatcher) {
            val model = model()
            model.open(HomeScope.ALL_ID)
            model.updateQuery("invoice")
            advanceUntilIdle()
            assertTrue((model.uiState.value.content as SessionSearchContent.Results).archived.isEmpty())

            model.toggleIncludeArchived()

            val content = model.uiState.value.content as SessionSearchContent.Results
            assertEquals(listOf("acme-invoice"), content.results.map { it.session.id })
            assertEquals(listOf("billing-old-invoice", "acme-old-invoice"), content.archived.map { it.session.id })
            assertEquals(4 until 11, content.archived.last().match)
            assertEquals(3, content.count)
        }

    @Test
    fun noMatch_offersIncludeArchivedOnlyWhenAnArchivedSessionInScopeMatches() =
        runTest(testDispatcher) {
            val model = model()
            model.open(ACME)
            model.updateQuery("spike")
            advanceUntilIdle()

            assertEquals(SessionSearchContent.NoMatch(offerAllProjects = false, offerArchived = true), model.uiState.value.content)

            model.includeArchived()

            assertEquals(SessionSearchArchive(included = true, count = 2), model.uiState.value.archive)
            val archived = (model.uiState.value.content as SessionSearchContent.Results).archived
            assertEquals(listOf("acme-old-login"), archived.map { it.session.id })
        }

    @Test
    fun noMatch_countsArchivedMatchesElsewhereOnlyWhileIncluded() =
        runTest(testDispatcher) {
            val model = model()
            model.open(ACME)
            model.updateQuery("retries")
            advanceUntilIdle()
            assertEquals(SessionSearchContent.NoMatch(offerAllProjects = false, offerArchived = false), model.uiState.value.content)

            model.toggleIncludeArchived()

            assertEquals(SessionSearchContent.NoMatch(offerAllProjects = true, offerArchived = false), model.uiState.value.content)
        }

    @Test
    fun archivedListingFailure_leavesTheChipWithoutACount() =
        runTest(testDispatcher) {
            archivedFails = true
            val model = model()
            model.open(HomeScope.ALL_ID)
            model.updateQuery("invoice")
            advanceUntilIdle()

            model.toggleIncludeArchived()

            assertEquals(SessionSearchArchive(included = true, count = null), model.uiState.value.archive)
            assertTrue((model.uiState.value.content as SessionSearchContent.Results).archived.isEmpty())
        }

    @Test
    fun oneFailedList_keepsTheOtherListsServerMatches() =
        runTest(testDispatcher) {
            serverSessions = billingServerOnly
            failingDirectories += ACME_PATH
            val model = model()
            model.open(HomeScope.ALL_ID)

            model.updateQuery("invoice")
            advanceUntilIdle()

            assertEquals(listOf("billing-server", "acme-invoice"), resultIds(model))
            assertFalse(model.uiState.value.busy)
        }

    @Test
    fun typing_keepsServerOnlyMatchesUntilTheNextAnswer() =
        runTest(testDispatcher) {
            serverSessions = billingServerOnly
            val model = model()
            model.open(HomeScope.ALL_ID)
            model.updateQuery("invoice")
            advanceUntilIdle()

            model.updateQuery("invoice a")

            assertTrue(model.uiState.value.busy)
            assertEquals(listOf("billing-server"), resultIds(model))
            advanceUntilIdle()
            assertEquals(listOf("billing-server"), resultIds(model))
        }

    @Test
    fun everyListFailing_keepsTheLastServerAnswer() =
        runTest(testDispatcher) {
            serverSessions = billingServerOnly
            val model = model()
            model.open(HomeScope.ALL_ID)
            model.updateQuery("invoice")
            advanceUntilIdle()

            serverFails = true
            model.updateQuery("invoice a")
            advanceUntilIdle()

            assertEquals(listOf("billing-server"), resultIds(model))
        }

    @Test
    fun removedProject_fallsBackToAllProjectsSearchesAgainAndDropsItsServerRows() =
        runTest(testDispatcher) {
            serverSessions = billingServerOnly
            val model = model()
            model.open(BILLING)
            model.updateQuery("invoice")
            advanceUntilIdle()
            assertEquals(listOf("billing-server"), resultIds(model).take(1))
            searches.clear()

            model.homeChanged(home(withBilling = false))
            runCurrent()

            assertEquals(HomeScope.ALL_ID, model.uiState.value.scope.id)
            assertEquals(listOf("invoice" to ACME_PATH), searches)
            advanceUntilIdle()
            assertEquals(listOf("acme-invoice"), resultIds(model))
        }

    @Test
    fun addedProject_searchesItsListToo() =
        runTest(testDispatcher) {
            val model = model()
            model.homeChanged(home(withBilling = false))
            model.open(HomeScope.ALL_ID)
            model.updateQuery("invoice")
            advanceUntilIdle()
            searches.clear()

            model.homeChanged(home())
            runCurrent()

            assertEquals(setOf("invoice" to ACME_PATH, "invoice" to BILLING_PATH), searches.toSet())
        }

    @Test
    fun liveUpdate_withTheSameLists_doesNotSearchAgain() =
        runTest(testDispatcher) {
            val model = model()
            model.open(HomeScope.ALL_ID)
            model.updateQuery("invoice")
            advanceUntilIdle()
            searches.clear()

            val newSession = SessionSummary(id = "acme-new", title = "Invoice totals", updatedAtEpochMs = 200)
            model.homeChanged(home(acmeSessions = acmeSessions + newSession))
            runCurrent()

            assertTrue(searches.isEmpty())
            assertEquals(listOf("acme-new", "acme-invoice"), resultIds(model))
        }

    private fun resultIds(model: SessionSearchViewModel): List<String> =
        (model.uiState.value.content as SessionSearchContent.Results).results.map { it.session.id }

    private fun model(): SessionSearchViewModel =
        SessionSearchViewModel(
            search = { query, directory ->
                searches += query to directory
                if (serverFails || directory in failingDirectories) error("offline")
                serverSessions[directory].orEmpty()
            },
            sessionDomainAdapter = SessionDomainAdapter(),
            listArchived = { directories ->
                archivedListings += directories
                if (archivedFails) error("offline")
                directories.flatMap { archivedSessions[it].orEmpty() }
            },
            archiveSupported = { archiveSupported },
            nowEpochMs = { 1_000 },
        ).also { it.homeChanged(home()) }

    private val billingServerOnly =
        mapOf(BILLING_PATH to listOf(Session(id = "billing-server", title = "Invoice audit", updatedAtEpochMs = 300)))

    private val acmeSessions =
        listOf(
            SessionSummary(id = "acme-invoice", title = "Invoice export", updatedAtEpochMs = 100),
            SessionSummary(id = "acme-login", title = "Fix login", updatedAtEpochMs = 90),
        )

    private fun home(
        acmeSessions: List<SessionSummary> = this.acmeSessions,
        withBilling: Boolean = true,
    ): SessionSearchHome {
        val paths = if (withBilling) listOf(ACME_PATH, BILLING_PATH) else listOf(ACME_PATH)
        val projects = HomeProjectGrouping.group(paths, emptyMap())
        return SessionSearchHome(
            projects = projects,
            sections =
                listOfNotNull(
                    section(ACME_PATH, "acme", acmeSessions),
                    section(
                        BILLING_PATH,
                        "billing",
                        listOf(SessionSummary(id = "billing-refactor", title = "Refactor auth", updatedAtEpochMs = 80)),
                    ).takeIf { withBilling },
                ),
        )
    }

    private fun section(
        path: String,
        name: String,
        sessions: List<SessionSummary>,
    ) = ProjectSectionUi(
        worktree = path,
        displayName = name,
        pathSubtitle = path,
        letter = name.first().uppercaseChar(),
        letterAccent = LetterAccent.Teal,
        allSessions = sessions,
    )

    private companion object {
        const val ACME_PATH = "/Users/example/acme"
        const val BILLING_PATH = "/Users/example/billing"
        const val ACME = "${HomeProjectGrouping.PATH_KEY_PREFIX}$ACME_PATH"
        const val BILLING = "${HomeProjectGrouping.PATH_KEY_PREFIX}$BILLING_PATH"
    }
}
