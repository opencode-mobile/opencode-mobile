package dev.opencode.mobile.ui.sessionchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionChatTopPillTest {
    private val branch = SessionBranchPillUiState("main", 3, 1)

    @Test
    fun syncingWinsOverTodosAndBranch() {
        val pill =
            SessionChatTopPill.resolve(
                syncingLabel = "Syncing…",
                todosVisible = true,
                todoTotalCount = 2,
                branchPill = branch,
                hideFloatStack = false,
            )

        assertEquals(SessionChatTopPill.Syncing("Syncing…"), pill)
    }

    @Test
    fun todosWinOverBranch() {
        val pill =
            SessionChatTopPill.resolve(
                syncingLabel = null,
                todosVisible = true,
                todoTotalCount = 2,
                branchPill = branch,
                hideFloatStack = false,
            )

        assertEquals(SessionChatTopPill.Todos, pill)
    }

    @Test
    fun branchShowsWhenNothingElseDoes() {
        val hiddenTodos =
            SessionChatTopPill.resolve(
                syncingLabel = null,
                todosVisible = false,
                todoTotalCount = 2,
                branchPill = branch,
                hideFloatStack = false,
            )
        val emptyTodos =
            SessionChatTopPill.resolve(
                syncingLabel = null,
                todosVisible = true,
                todoTotalCount = 0,
                branchPill = branch,
                hideFloatStack = false,
            )

        assertEquals(SessionChatTopPill.Branch(branch), hiddenTodos)
        assertEquals(SessionChatTopPill.Branch(branch), emptyTodos)
    }

    @Test
    fun nothingToShowResolvesToNull() {
        val pill =
            SessionChatTopPill.resolve(
                syncingLabel = null,
                todosVisible = false,
                todoTotalCount = 0,
                branchPill = null,
                hideFloatStack = false,
            )

        assertNull(pill)
    }

    @Test
    fun todosSheetHidesTodosAndBranchButKeepsSyncing() {
        val quiet =
            SessionChatTopPill.resolve(
                syncingLabel = null,
                todosVisible = true,
                todoTotalCount = 2,
                branchPill = branch,
                hideFloatStack = true,
            )
        val syncing =
            SessionChatTopPill.resolve(
                syncingLabel = "Syncing…",
                todosVisible = true,
                todoTotalCount = 2,
                branchPill = branch,
                hideFloatStack = true,
            )

        assertNull(quiet)
        assertEquals(SessionChatTopPill.Syncing("Syncing…"), syncing)
    }
}
