package dev.opencode.mobile.ui.components

import dev.opencode.mobile.theme.LetterAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionListFilterTest {
    private val sessions =
        listOf(
            session(id = "1", worktree = "/a"),
            session(id = "2", worktree = "/b"),
            session(id = "3", worktree = "/a"),
        )

    @Test
    fun visibleForFilter_allReturnsEverySession() {
        val visible = sessions.visibleForFilter(SessionListFilterTab.ALL_ID)
        assertEquals(listOf("1", "2", "3"), visible.map { it.id })
    }

    @Test
    fun visibleForFilter_worktreeReturnsMatchingSessionsOnly() {
        val visible = sessions.visibleForFilter("/a")
        assertEquals(listOf("1", "3"), visible.map { it.id })
    }

    @Test
    fun visibleForFilter_unknownWorktreeReturnsEmpty() {
        val visible = sessions.visibleForFilter("/missing")
        assertEquals(emptyList<String>(), visible.map { it.id })
    }

    @Test
    fun worktreeFilterClear_emptyWorktreeOffersTheProjectWhenItHasSessions() {
        val clear = worktree().worktreeFilterClear(broaderTabs = broader(projectCount = 4, allCount = 6), visibleIsEmpty = true)

        assertEquals("proj", clear?.filterId)
        assertEquals(SessionListWorktreeEmptyCopy.OTHER_WORKTREES, clear?.message)
    }

    @Test
    fun worktreeFilterClear_emptyWorktreeOffersAllWhenOnlyOtherProjectsHaveSessions() {
        val clear = worktree().worktreeFilterClear(broaderTabs = broader(projectCount = 0, allCount = 3), visibleIsEmpty = true)

        assertEquals(SessionListFilterTab.ALL_ID, clear?.filterId)
        assertEquals(SessionListWorktreeEmptyCopy.OTHER_PROJECTS, clear?.message)
    }

    @Test
    fun worktreeFilterClear_emptyWorktreeStillClearsWhenNothingElseHasSessions() {
        val clear = worktree().worktreeFilterClear(broaderTabs = broader(projectCount = 0, allCount = 0), visibleIsEmpty = true)

        assertEquals("proj", clear?.filterId)
        assertEquals(SessionListWorktreeEmptyCopy.EVERY_WORKTREE, clear?.message)
    }

    @Test
    fun worktreeFilterClear_skipsWhileTheProjectIsLoadingOrTheWorktreeHasRows() {
        val loading = broader(projectCount = 0, allCount = 0, projectLoad = SessionListScopeLoad.Loading())
        assertNull(worktree().worktreeFilterClear(broaderTabs = loading, visibleIsEmpty = true))
        assertNull(worktree().worktreeFilterClear(broaderTabs = broader(projectCount = 2, allCount = 2), visibleIsEmpty = false))
        assertNull(
            SessionListFilterTab.project(worktree = "proj", title = "repo", count = 0)
                .worktreeFilterClear(broaderTabs = broader(projectCount = 0, allCount = 2), visibleIsEmpty = true),
        )
    }

    private fun session(
        id: String,
        worktree: String,
    ): SessionListSession =
        SessionListSession(
            id = id,
            title = "Session $id",
            worktree = worktree,
            letter = 'S',
            letterAccent = LetterAccent.Violet,
            subtitle = worktree,
            status = SessionListStatus.Idle,
            caption = "",
        )

    private fun worktree(): SessionListFilterTab =
        SessionListFilterTab(
            id = "worktree:proj\u001F/repo-wt",
            title = "feature/x",
            count = 0,
            parentId = "proj",
        )

    private fun broader(
        projectCount: Int,
        allCount: Int,
        projectLoad: SessionListScopeLoad? = null,
    ): List<SessionListFilterTab> =
        listOf(
            SessionListFilterTab.all(projectCount = 2, sessionCount = allCount),
            SessionListFilterTab.project(worktree = "proj", title = "repo", count = projectCount, load = projectLoad)
                .copy(id = "proj"),
        )
}
