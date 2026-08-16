package dev.opencode.mobile.data.local

import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileCatalog
import dev.opencode.mobile.domain.connection.ConnectionProfileIdentity
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.SecureCredentialStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            assertEquals(null, loaded?.serverProtocol)
            assertTrue(loaded?.id.isNullOrBlank().not())
        }

    @Test
    fun saveAndLoadPersistsProtocolHint() =
        runTest {
            val store = InMemoryConnectionProfileStore()
            store.saveProfile(
                ConnectionProfile(
                    baseUrl = server,
                    username = "opencode",
                    password = "secret",
                    serverProtocol = dev.opencode.mobile.domain.connection.ServerProtocol.V2,
                ),
            )
            assertEquals(
                dev.opencode.mobile.domain.connection.ServerProtocol.V2,
                store.activeProfile()?.serverProtocol,
            )
        }

    @Test
    fun addWorktree_appendsDistinctPaths() =
        runTest {
            val store = InMemoryConnectionProfileStore()
            store.addWorktree(server, "/repo/a")
            store.addWorktree(server, "/repo/b")
            store.addWorktree(server, "/repo/a")
            assertEquals(listOf("/repo/a", "/repo/b"), store.addedWorktrees(server))
        }

    @Test
    fun setAddedWorktrees_replacesList() =
        runTest {
            val store = InMemoryConnectionProfileStore()
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

    @Test
    fun listProfiles_returnsSavedInOrder() =
        runTest {
            val store = InMemoryConnectionProfileStore()
            store.saveProfile(ConnectionProfile(baseUrl = "http://10.0.0.1:4096/", username = "opencode", name = "Home"))
            store.saveProfile(ConnectionProfile(baseUrl = "http://10.0.0.2:4096/", username = "opencode", name = "Work"))
            assertEquals(listOf("Home", "Work"), store.listProfiles().map { it.displayName })
            assertEquals("Work", store.activeProfile()?.displayName)
        }

    @Test
    fun applyProfile_switchesActiveWithoutDroppingTheOther() =
        runTest {
            val store = InMemoryConnectionProfileStore()
            store.saveProfile(ConnectionProfile(id = "home", baseUrl = "http://10.0.0.1:4096/", username = "opencode", name = "Home"))
            store.saveProfile(ConnectionProfile(id = "work", baseUrl = "http://10.0.0.2:4096/", username = "opencode", name = "Work"))
            store.applyProfile("home")
            assertEquals("home", store.activeProfile()?.id)
            assertEquals(2, store.listProfiles().size)
        }

    @Test
    fun removeActiveProfile_activatesNeighborAndDropsPassword() =
        runTest {
            val secure = InMemorySecureCredentialStore()
            val store = InMemoryConnectionProfileStore(secure)
            store.saveProfile(
                ConnectionProfile(id = "home", baseUrl = "http://10.0.0.1:4096/", username = "opencode", password = "home-secret"),
            )
            store.saveProfile(
                ConnectionProfile(id = "work", baseUrl = "http://10.0.0.2:4096/", username = "opencode", password = "work-secret"),
            )
            store.removeProfile("work")
            assertEquals("home", store.activeProfile()?.id)
            assertEquals("home-secret", store.activeProfile()?.password)
            assertNull(secure.readPassword("work"))
        }

    @Test
    fun worktreesStayScopedToProfileId() =
        runTest {
            val store = InMemoryConnectionProfileStore()
            store.saveProfile(ConnectionProfile(id = "home", baseUrl = "http://10.0.0.1:4096/", username = "opencode"))
            store.addWorktree("http://10.0.0.1:4096/", "/home-repo")
            store.saveProfile(ConnectionProfile(id = "work", baseUrl = "http://10.0.0.2:4096/", username = "opencode"))
            store.addWorktree("http://10.0.0.2:4096/", "/work-repo")
            store.applyProfile("home")
            assertEquals(listOf("/home-repo"), store.addedWorktrees("http://10.0.0.1:4096/"))
            assertEquals(listOf("/work-repo"), store.addedWorktrees("http://10.0.0.2:4096/"))
        }
}

/** Test double mirroring store semantics without DataStore. */
private class InMemoryConnectionProfileStore(
    private val secureCredentialStore: InMemorySecureCredentialStore = InMemorySecureCredentialStore(),
) : ConnectionProfileStore {
    private val data = mutableMapOf<String, List<String>>()
    private var catalog = ConnectionProfileCatalog()

    override suspend fun activeProfile(): ConnectionProfile? = catalog.activeProfile()?.withPassword()

    override suspend fun listProfiles(): List<ConnectionProfile> = catalog.profiles.map { it.withPassword() }

    override suspend fun saveProfile(profile: ConnectionProfile) {
        catalog = catalog.save(profile)
        val saved =
            catalog.profiles.first { stored ->
                stored.id == profile.id ||
                    ConnectionProfileIdentity.sameConnectionIdentity(
                        stored.baseUrl,
                        stored.username,
                        profile.baseUrl,
                        profile.username,
                    )
            }
        secureCredentialStore.writePassword(saved.id, profile.password)
    }

    override suspend fun applyProfile(id: String) {
        catalog = catalog.apply(id)
    }

    override suspend fun removeProfile(id: String) {
        val existing = catalog.profiles.firstOrNull { it.id == id }
        catalog = catalog.remove(id)
        if (existing != null) {
            secureCredentialStore.deletePassword(existing.id)
            secureCredentialStore.deletePassword(existing.baseUrl)
            data.remove(existing.id)
            data.remove(ConnectionProfileIdentity.normalizeBaseUrl(existing.baseUrl))
        }
    }

    override suspend fun addedWorktrees(serverBaseUrl: String): List<String> {
        val profile = resolveProfile(serverBaseUrl)
        if (profile != null) {
            val byId = data[profile.id]
            if (!byId.isNullOrEmpty()) return byId
        }
        return data[ConnectionProfileIdentity.normalizeBaseUrl(serverBaseUrl)].orEmpty()
    }

    override suspend fun addWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) {
        val key = storageKey(serverBaseUrl)
        val current = data[key].orEmpty()
        if (worktree !in current) {
            data[key] = current + worktree
        }
    }

    override suspend fun removeWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) {
        val key = storageKey(serverBaseUrl)
        data[key] = data[key].orEmpty().filterNot { it == worktree }
    }

    override suspend fun setAddedWorktrees(
        serverBaseUrl: String,
        worktrees: List<String>,
    ) {
        data[storageKey(serverBaseUrl)] = worktrees.distinct()
    }

    override suspend fun clearAll() {
        data.clear()
        catalog = ConnectionProfileCatalog()
    }

    private fun storageKey(serverBaseUrl: String): String =
        resolveProfile(serverBaseUrl)?.id ?: ConnectionProfileIdentity.normalizeBaseUrl(serverBaseUrl)

    private fun resolveProfile(serverBaseUrl: String): ConnectionProfile? {
        val normalized = ConnectionProfileIdentity.normalizeBaseUrl(serverBaseUrl)
        val active = catalog.activeProfile()
        if (active != null && ConnectionProfileIdentity.normalizeBaseUrl(active.baseUrl) == normalized) {
            return active
        }
        return catalog.profiles.firstOrNull {
            ConnectionProfileIdentity.normalizeBaseUrl(it.baseUrl) == normalized
        }
    }

    private fun ConnectionProfile.withPassword(): ConnectionProfile =
        copy(password = secureCredentialStore.readPassword(id) ?: secureCredentialStore.readPassword(baseUrl).orEmpty())
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
