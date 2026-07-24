package dev.opencode.mobile.ui.components

import dev.opencode.mobile.theme.LetterAccent
import org.junit.Assert.assertEquals
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
}
