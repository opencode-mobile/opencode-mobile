package dev.opencode.mobile.data.local

import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.SecureCredentialStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionProfileStoreTest {
    private val server = "http://127.0.0.1:4096/"

    @Test
    fun saveAndLoadActiveProfile() =
        runTest {
            val secure = InMemorySecureCredentialStore()
            val store = InMemoryConnectionProfileStore(secure)
            store.saveProfile(ConnectionProfile(baseUrl = server, username = "opencode", password = "secret"))
            val loaded = store.activeProfile()
            assertEquals(server, loaded?.baseUrl)
            assertEquals("opencode", loaded?.username)
            assertEquals("secret", loaded?.password)
        }

    @Test
    fun addWorktree_appendsDistinctPaths() =
        runTest {
            val store = InMemoryConnectionProfileStore()
            val server = "http://127.0.0.1:4096/"
            store.addWorktree(server, "/repo/a")
            store.addWorktree(server, "/repo/b")
            store.addWorktree(server, "/repo/a")
            assertEquals(listOf("/repo/a", "/repo/b"), store.addedWorktrees(server))
        }

    @Test
    fun setAddedWorktrees_replacesList() =
        runTest {
            val store = InMemoryConnectionProfileStore()
            val server = "http://127.0.0.1:4096/"
            store.setAddedWorktrees(server, listOf("/one", "/two", "/one"))
            assertEquals(listOf("/one", "/two"), store.addedWorktrees(server))
        }

    @Test
    fun removeWorktree_dropsMatchingPath() =
        runTest {
            val store = InMemoryConnectionProfileStore()
            store.setAddedWorktrees(server, listOf("/repo/a", "/repo/b", "/repo/c"))
            store.removeWorktree(server, "/repo/b")
            assertEquals(listOf("/repo/a", "/repo/c"), store.addedWorktrees(server))
            store.removeWorktree(server, "/missing")
            assertEquals(listOf("/repo/a", "/repo/c"), store.addedWorktrees(server))
        }
}

/** Test double mirroring store semantics without DataStore. */
private class InMemoryConnectionProfileStore(
    private val secureCredentialStore: InMemorySecureCredentialStore = InMemorySecureCredentialStore(),
) : ConnectionProfileStore {
    private val data = mutableMapOf<String, List<String>>()
    private var activeProfile: ConnectionProfile? = null

    override suspend fun activeProfile(): ConnectionProfile? =
        activeProfile?.let { profile ->
            profile.copy(password = secureCredentialStore.readPassword(profile.baseUrl).orEmpty())
        }

    override suspend fun saveProfile(profile: ConnectionProfile) {
        val normalized = normalize(profile.baseUrl)
        activeProfile = profile.copy(baseUrl = normalized)
        secureCredentialStore.writePassword(normalized, profile.password)
    }

    override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = data[normalize(serverBaseUrl)].orEmpty()

    override suspend fun addWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) {
        val key = normalize(serverBaseUrl)
        val current = data[key].orEmpty()
        if (worktree !in current) {
            data[key] = current + worktree
        }
    }

    override suspend fun removeWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) {
        val key = normalize(serverBaseUrl)
        data[key] = data[key].orEmpty().filterNot { it == worktree }
    }

    override suspend fun setAddedWorktrees(
        serverBaseUrl: String,
        worktrees: List<String>,
    ) {
        data[normalize(serverBaseUrl)] = worktrees.distinct()
    }

    override suspend fun clearAll() {
        data.clear()
        activeProfile = null
    }

    private fun normalize(baseUrl: String): String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
}

private class InMemorySecureCredentialStore : SecureCredentialStore {
    private val passwords = mutableMapOf<String, String>()

    override fun readPassword(serverBaseUrl: String): String? = passwords[serverBaseUrl]

    override fun writePassword(
        serverBaseUrl: String,
        password: String,
    ) {
        passwords[serverBaseUrl] = password
    }

    override fun deletePassword(serverBaseUrl: String) {
        passwords.remove(serverBaseUrl)
    }

    override fun clearAll() {
        passwords.clear()
    }
}
