package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.home.HomeWorktree
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.ui.text.english
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeTextTest {
    @Test
    fun countsUseThePluralResources() {
        assertEquals("1 project", HomeText.projectCount(1).english())
        assertEquals("3 projects", HomeText.projectCount(3).english())
        assertEquals("All 3 worktrees", HomeText.allWorktrees(3).english())
        assertEquals("2 running", HomeText.runningCount(2).english())
        assertEquals("Show all 7", HomeText.showAll(7).english())
        assertEquals("Show fewer", HomeText.showFewer.english())
    }

    @Test
    fun mainCheckoutIsKeyedAndOtherWorktreesShowTheirFolderVerbatim() {
        val main = HomeWorktree("/Users/example/repo", branch = "dev", isMain = true)
        val other = HomeWorktree("/Users/example/repo-723", branch = "feature/x", isMain = false)

        assertEquals("main checkout", HomeText.worktreeSubtitle(main).english())
        assertEquals(UiText.verbatim("repo-723"), HomeText.worktreeSubtitle(other))
    }

    @Test
    fun snackbarsKeepTheProjectNameVerbatim() {
        assertEquals(
            "Same repo as opencode-mobile. Showing it as a worktree there.",
            HomeText.joinedProject("opencode-mobile").english(),
        )
        assertEquals("Removed \"%1\$s\" from home", HomeText.removedProject("%1\$s").english())
        assertEquals("\"acme-invoices\" is already on home", HomeText.alreadyOnHome("acme-invoices").english())
        assertEquals("Drag to reorder. Worktrees stay with their project.", HomeText.reorderNote.english())
    }
}
