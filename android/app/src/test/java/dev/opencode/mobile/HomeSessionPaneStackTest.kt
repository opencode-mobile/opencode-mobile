package dev.opencode.mobile

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSessionPaneStackTest {
    private val sessionA = SessionChat(sessionId = "a", sessionTitle = "A", worktree = "/repo")
    private val sessionB = SessionChat(sessionId = "b", sessionTitle = "B", worktree = "/repo")
    private val contextA = SessionContext(sessionId = "a", worktree = "/repo")
    private val childOfA =
        SessionChat(sessionId = "child", sessionTitle = "Child", worktree = "/repo", parentId = "a")

    private fun emptyPane(): MutableList<NavKey> = mutableListOf(HomeSessionPanePlaceholder)

    @Test
    fun paneSelection_isNullForThePlaceholderRoot() {
        assertNull(emptyPane().paneSelection)
    }

    @Test
    fun selectPaneSession_replacesThePlaceholder() {
        val pane = emptyPane()

        pane.selectPaneSession(sessionA)

        assertEquals(listOf<NavKey>(sessionA), pane)
        assertEquals(sessionA, pane.paneSelection)
    }

    @Test
    fun selectPaneSession_replacesTheWholeStackForAnotherSession() {
        val pane = mutableListOf<NavKey>(sessionA, contextA, childOfA)

        pane.selectPaneSession(sessionB)

        assertEquals(listOf<NavKey>(sessionB), pane)
    }

    @Test
    fun selectPaneSession_keepsPushedScreensWhenReselectingTheRoot() {
        val pane = mutableListOf<NavKey>(sessionA, contextA)

        pane.selectPaneSession(sessionA.copy(sessionTitle = "Renamed"))

        assertEquals(listOf<NavKey>(sessionA, contextA), pane)
    }

    @Test
    fun selectPaneSession_treatsEachOptimisticSessionAsDistinct() {
        val first = SessionChat("pending", "New Session", "/repo", optimisticNavigationInstanceId = "1")
        val second = first.copy(optimisticNavigationInstanceId = "2")
        val pane = mutableListOf<NavKey>(first)

        pane.selectPaneSession(second)

        assertEquals(listOf<NavKey>(second), pane)
    }

    @Test
    fun navigateBackInPane_popsAScreenAboveTheRoot() {
        val pane = mutableListOf<NavKey>(sessionA, contextA)

        pane.navigateBackInPane()

        assertEquals(listOf<NavKey>(sessionA), pane)
    }

    @Test
    fun navigateBackInPane_clearsTheSelectionFromTheRoot() {
        val pane = mutableListOf<NavKey>(sessionA)

        pane.navigateBackInPane()

        assertEquals(listOf<NavKey>(HomeSessionPanePlaceholder), pane)
    }

    @Test
    fun replacePaneSession_replacesTheRootWhenNothingIsPushed() {
        val pane = mutableListOf<NavKey>(sessionA)

        pane.replacePaneSession(sessionB)

        assertEquals(listOf<NavKey>(sessionB), pane)
    }

    @Test
    fun replacePaneSession_replacesOnlyTheTopAboveTheRoot() {
        val pane = mutableListOf<NavKey>(sessionA, childOfA)

        pane.replacePaneSession(sessionB)

        assertEquals(listOf<NavKey>(sessionA, sessionB), pane)
    }

    @Test
    fun resetPane_leavesOnlyThePlaceholder() {
        val pane = mutableListOf<NavKey>(sessionA, contextA)

        pane.resetPane()

        assertEquals(listOf<NavKey>(HomeSessionPanePlaceholder), pane)
    }

    @Test
    fun resetPane_keepsAnEmptyPaneAsIs() {
        val pane = emptyPane()

        pane.resetPane()

        assertEquals(listOf<NavKey>(HomeSessionPanePlaceholder), pane)
    }

    @Test
    fun handOffPane_returnsTheScreensRootFirstAndResetsThePane() {
        val pane = mutableListOf<NavKey>(sessionA, contextA)

        val routes = pane.handOffPane()

        assertEquals(listOf<NavKey>(sessionA, contextA), routes)
        assertEquals(listOf<NavKey>(HomeSessionPanePlaceholder), pane)
    }

    @Test
    fun handOffPane_returnsNothingForAnEmptyPane() {
        val pane = emptyPane()

        assertTrue(pane.handOffPane().isEmpty())
        assertEquals(listOf<NavKey>(HomeSessionPanePlaceholder), pane)
    }

    @Test
    fun paneNavigator_pushesAndPopsInsideThePane() {
        val pane = mutableListOf<NavKey>(sessionA)
        val navigator = pane.paneChatFlowNavigator()

        navigator.push(contextA)
        assertEquals(listOf<NavKey>(sessionA, contextA), pane)

        navigator.navigateBack()
        navigator.navigateBack()
        assertEquals(listOf<NavKey>(HomeSessionPanePlaceholder), pane)
    }

    @Test
    fun rootNavigator_replacesTheTopSessionAndKeepsTheRoot() {
        val stack = mutableListOf<NavKey>(ProjectListHome, sessionA)
        val navigator = stack.rootChatFlowNavigator()

        navigator.replaceSession(sessionB)
        assertEquals(listOf<NavKey>(ProjectListHome, sessionB), stack)

        navigator.navigateBack()
        navigator.navigateBack()
        assertEquals(listOf<NavKey>(ProjectListHome), stack)
    }
}
