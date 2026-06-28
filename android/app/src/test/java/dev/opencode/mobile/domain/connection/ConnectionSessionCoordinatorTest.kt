package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.domain.model.SessionModelSelection
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.OpencodeSseHttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionSessionCoordinatorTest {
    @Test
    fun clearConnectionData_clearsModelSelectionCache() =
        runBlocking {
            val store = FakeSessionModelSelectionStore()
            store.seed(
                serverBaseUrl = "http://127.0.0.1:4096/",
                worktree = "/repo",
                sessionId = "ses-1",
                selection = SessionModelSelection(providerId = "openai", modelId = "gpt-4"),
            )
            val json = Json { ignoreUnknownKeys = true }
            val connectionProfileStore = FakeConnectionProfileStore()
            val activeConnectionManager = ActiveConnectionManager(connectionProfileStore)
            activeConnectionManager.applyProfile(
                ConnectionProfile(baseUrl = "http://127.0.0.1:4096/", username = "opencode", password = ""),
            )
            val coordinator =
                ConnectionSessionCoordinator(
                    activeConnectionManager = activeConnectionManager,
                    connectionProfileStore = connectionProfileStore,
                    secureCredentialStore = FakeSecureCredentialStore(),
                    sessionModelSelectionStore = store,
                    httpClient = OpencodeHttpClient(json, activeConnectionManager),
                    sseHttpClient = OpencodeSseHttpClient(json, activeConnectionManager),
                )

            coordinator.clearConnectionData()

            assertNull(
                store.load(
                    serverBaseUrl = "http://127.0.0.1:4096/",
                    worktree = "/repo",
                    sessionId = "ses-1",
                ),
            )
        }
}

private class FakeSessionModelSelectionStore : SessionModelSelectionStore {
    private val saved = mutableMapOf<String, SessionModelSelection>()

    fun seed(
        serverBaseUrl: String,
        worktree: String,
        sessionId: String,
        selection: SessionModelSelection,
    ) {
        saved[key(serverBaseUrl, worktree, sessionId)] = selection
    }

    override suspend fun load(
        serverBaseUrl: String,
        worktree: String,
        sessionId: String,
    ): SessionModelSelection? = saved[key(serverBaseUrl, worktree, sessionId)]

    override suspend fun save(
        serverBaseUrl: String,
        worktree: String,
        sessionId: String,
        selection: SessionModelSelection,
    ) {
        saved[key(serverBaseUrl, worktree, sessionId)] = selection
    }

    override suspend fun clearAll() {
        saved.clear()
    }

    private fun key(
        serverBaseUrl: String,
        worktree: String,
        sessionId: String,
    ): String = "$serverBaseUrl|$worktree|$sessionId"
}

private class FakeConnectionProfileStore : ConnectionProfileStore {
    override suspend fun activeProfile(): ConnectionProfile? = null

    override suspend fun saveProfile(profile: ConnectionProfile) = Unit

    override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = emptyList()

    override suspend fun addWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) = Unit

    override suspend fun setAddedWorktrees(
        serverBaseUrl: String,
        worktrees: List<String>,
    ) = Unit

    override suspend fun clearAll() = Unit
}

private class FakeSecureCredentialStore : SecureCredentialStore {
    override fun readPassword(serverBaseUrl: String): String? = null

    override fun writePassword(
        serverBaseUrl: String,
        password: String,
    ) = Unit

    override fun deletePassword(serverBaseUrl: String) = Unit

    override fun clearAll() = Unit
}
