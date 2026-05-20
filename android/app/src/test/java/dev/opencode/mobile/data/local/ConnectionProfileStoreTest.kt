package dev.opencode.mobile.data.local

import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionProfileStoreTest {
  @Test
  fun addWorktree_appendsDistinctPaths() = runTest {
    val store = InMemoryConnectionProfileStore()
    val server = "http://127.0.0.1:4096/"
    store.addWorktree(server, "/repo/a")
    store.addWorktree(server, "/repo/b")
    store.addWorktree(server, "/repo/a")
    assertEquals(listOf("/repo/a", "/repo/b"), store.addedWorktrees(server))
  }

  @Test
  fun setAddedWorktrees_replacesList() = runTest {
    val store = InMemoryConnectionProfileStore()
    val server = "http://127.0.0.1:4096/"
    store.setAddedWorktrees(server, listOf("/one", "/two", "/one"))
    assertEquals(listOf("/one", "/two"), store.addedWorktrees(server))
  }
}

/** Test double mirroring store semantics without DataStore. */
private class InMemoryConnectionProfileStore : ConnectionProfileStore {
  private val data = mutableMapOf<String, List<String>>()

  override suspend fun addedWorktrees(serverBaseUrl: String): List<String> =
    data[serverBaseUrl].orEmpty()

  override suspend fun addWorktree(serverBaseUrl: String, worktree: String) {
    val current = data[serverBaseUrl].orEmpty()
    if (worktree !in current) {
      data[serverBaseUrl] = current + worktree
    }
  }

  override suspend fun setAddedWorktrees(serverBaseUrl: String, worktrees: List<String>) {
    data[serverBaseUrl] = worktrees.distinct()
  }
}
