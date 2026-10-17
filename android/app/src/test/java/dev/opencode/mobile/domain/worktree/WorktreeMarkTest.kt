package dev.opencode.mobile.domain.worktree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorktreeMarkTest {
    private val added = "/Users/ivan/Projects/opencode-mobile"
    private val checkout = "/Users/ivan/.local/share/opencode/worktree/2fb98b/add-project-browse"

    @Test
    fun badgesAListedWorktreeOtherThanTheAddedPath() {
        val inventory = WorktreeInventory.loaded(listOf(added, checkout))

        assertTrue(WorktreeMark.showsBadge(checkout, added, inventory))
    }

    @Test
    fun doesNotBadgeTheAddedPathEvenWhenItIsListed() {
        val inventory = WorktreeInventory.loaded(listOf(added, checkout))

        assertFalse(WorktreeMark.showsBadge(added, added, inventory))
        assertFalse(WorktreeMark.showsBadge("$added/", added, inventory))
    }

    @Test
    fun doesNotBadgeASubpathUnlessThatDirectoryIsListed() {
        val inventory = WorktreeInventory.loaded(listOf(checkout))

        assertFalse(WorktreeMark.showsBadge("$added/packages/app", added, inventory))
        assertTrue(WorktreeMark.showsBadge("$checkout/packages/app", added, WorktreeInventory.loaded(listOf("$checkout/packages/app"))))
    }

    @Test
    fun doesNotBadgeUntilTheInventoryHasLoaded() {
        assertFalse(WorktreeMark.showsBadge(checkout, added, WorktreeInventory.Unloaded))
        assertFalse(WorktreeMark.showsBadge(checkout, added, WorktreeInventory.Failed))
        assertFalse(WorktreeMark.showsBadge(checkout, added, WorktreeInventory.loaded(emptyList())))
        assertFalse(WorktreeMark.showsBadge(null, added, WorktreeInventory.loaded(listOf(checkout))))
        assertFalse(WorktreeMark.showsBadge("  ", added, WorktreeInventory.loaded(listOf(checkout))))
    }

    @Test
    fun doesNotBadgeFromAnotherProjectsInventory() {
        val other = WorktreeInventory.loaded(listOf("/other/crisp-cactus"))

        assertFalse(WorktreeMark.showsBadge(checkout, added, other))
    }

    @Test
    fun checkoutNameIsTheLastPathSegment() {
        assertEquals("add-project-browse", WorktreeMark.checkoutName(checkout))
        assertEquals("crisp-cactus", WorktreeMark.checkoutName("/worktrees/crisp-cactus/"))
    }

    @Test
    fun sessionChatNamesTheCheckoutOnlyWhenThePredicateMatches() {
        val inventories =
            mapOf(
                added to WorktreeInventory.loaded(listOf(added, checkout)),
            )

        assertEquals(
            "add-project-browse",
            WorktreeMark.checkoutNameIfMarked(checkout, listOf(added)) { inventories[it] ?: WorktreeInventory.Unloaded },
        )
        assertNull(
            WorktreeMark.checkoutNameIfMarked(added, listOf(added)) { inventories[it] ?: WorktreeInventory.Unloaded },
        )
        assertNull(
            WorktreeMark.checkoutNameIfMarked(
                "$added/packages/app",
                listOf(added),
            ) { inventories[it] ?: WorktreeInventory.Unloaded },
        )
    }
}
