package dev.opencode.mobile.network

import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveConnectionManagerProtocolTest {
    @Test
    fun applyProfile_cachesDetectedProtocol() =
        runBlocking {
            val store = FakeProfileStore()
            val manager = ActiveConnectionManager(store)

            manager.applyProfile(
                ConnectionProfile(baseUrl = "http://127.0.0.1:4096/", username = "opencode", password = "x"),
                serverProtocol = ServerProtocol.V2,
            )

            assertEquals(ServerProtocol.V2, manager.currentProtocol())
            assertEquals(false, manager.currentCapabilities().supportsSessionMessageDiff)
        }

    @Test
    fun updateProtocol_mutatesActiveConnection() =
        runBlocking {
            val store = FakeProfileStore()
            val manager = ActiveConnectionManager(store)
            manager.applyProfile(
                ConnectionProfile(baseUrl = "http://127.0.0.1:4096/", username = "opencode", password = ""),
            )
            assertNull(manager.currentProtocol())

            manager.updateProtocol(ServerProtocol.V1)

            assertEquals(ServerProtocol.V1, manager.currentProtocol())
            assertEquals(true, manager.currentCapabilities().supportsSessionMessageDiff)
        }

    @Test
    fun disconnect_clearsCachedProtocol() =
        runBlocking {
            val store = FakeProfileStore()
            val manager = ActiveConnectionManager(store)
            manager.applyProfile(
                ConnectionProfile(baseUrl = "http://127.0.0.1:4096/", username = "opencode", password = ""),
                serverProtocol = ServerProtocol.V2,
            )

            manager.disconnect()

            assertNull(manager.current())
            assertNull(manager.currentProtocol())
        }
}

private class FakeProfileStore : ConnectionProfileStore {
    private var profile: ConnectionProfile? = null

    override suspend fun activeProfile(): ConnectionProfile? = profile

    override suspend fun saveProfile(profile: ConnectionProfile) {
        this.profile = profile
    }

    override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = emptyList()

    override suspend fun addWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) {}

    override suspend fun removeWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) {}

    override suspend fun setAddedWorktrees(
        serverBaseUrl: String,
        worktrees: List<String>,
    ) {}

    override suspend fun clearAll() {
        profile = null
    }
}
