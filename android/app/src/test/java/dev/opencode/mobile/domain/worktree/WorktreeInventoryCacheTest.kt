package dev.opencode.mobile.domain.worktree

import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.ui.sessionchat.SessionChatProjectIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorktreeInventoryCacheTest {
    private val server = "http://127.0.0.1:4096"

    @Test
    fun unknownUntilTheAddedPathsAreRecorded() {
        val cache = WorktreeInventoryCache()
        cache.record(WorktreeInventory.loaded(listOf("/repo", "/wt/tax")), "/repo", server)
        assertNull(cache.snapshot(server))

        cache.setAddedPaths(listOf("/repo"), server)
        assertEquals(WorktreeInventory.loaded(listOf("/repo", "/wt/tax")), cache.snapshot("$server/")?.inventoryFor("/repo"))
    }

    @Test
    fun failedLoadKeepsTheLastInventory() {
        val cache = WorktreeInventoryCache()
        cache.setAddedPaths(listOf("/repo"), server)
        cache.record(WorktreeInventory.loaded(listOf("/repo", "/wt/tax")), "/repo", server)
        cache.record(WorktreeInventory.Failed, "/repo", server)
        assertEquals(WorktreeInventory.loaded(listOf("/repo", "/wt/tax")), cache.snapshot(server)?.inventoryFor("/repo"))
    }

    @Test
    fun removedPathsAndOtherServersDoNotLeak() {
        val cache = WorktreeInventoryCache()
        cache.setAddedPaths(listOf("/repo", "/other"), server)
        cache.record(WorktreeInventory.loaded(listOf("/repo", "/wt/tax")), "/repo", server)
        cache.setAddedPaths(listOf("/other"), server)
        assertEquals(WorktreeInventory.Unloaded, cache.snapshot(server)?.inventoryFor("/repo"))
        assertNull(cache.snapshot("http://10.0.0.2:4096"))

        cache.clear()
        assertNull(cache.snapshot(server))
    }

    @Test
    fun sessionChatIdentityNamesAListedWorktreeAndLettersTheAddedPath() {
        val inventory = { added: String ->
            if (added == "/repo") WorktreeInventory.loaded(listOf("/repo", "/wt/tax")) else WorktreeInventory.Unloaded
        }
        assertEquals(
            SessionChatProjectIdentity(label = "tax", isWorktreeCheckout = true),
            SessionChatProjectIdentity.resolve("/wt/tax", listOf("/repo"), inventory),
        )
        assertEquals(
            SessionChatProjectIdentity(label = "repo", letter = 'R', accent = letterAccentForWorktree("/repo")),
            SessionChatProjectIdentity.resolve("/repo", listOf("/repo"), inventory),
        )
        assertEquals(SessionChatProjectIdentity.None, SessionChatProjectIdentity.resolve(" ", listOf("/repo"), inventory))
    }
}
