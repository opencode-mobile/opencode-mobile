package dev.opencode.mobile.network

import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.IdentityHashMap

class OpencodeHttpClientBindingTest {
    private val serverA = ConnectionProfile(id = "a", baseUrl = "http://192.0.2.1:4096/", username = "opencode", password = "test-password")
    private val serverB = ConnectionProfile(id = "b", baseUrl = "http://192.0.2.2:4096/", username = "opencode", password = "test-password")
    private val builtFor = IdentityHashMap<HttpClient, String>()

    private fun factory(connection: ServerConnection): HttpClient =
        HttpClient(MockEngine { respondOk() }).also { builtFor[it] = connection.baseUrl }

    private fun HttpClient.isOpen(): Boolean = coroutineContext[Job]?.isActive == true

    @Test
    fun get_afterABind_neverReturnsThePreviousServersClient() =
        runTest {
            val manager = ActiveConnectionManager(SingleProfileStore())
            manager.applyProfile(serverA)
            val http = OpencodeHttpClient(manager, ::factory)
            val clientA = http.get()

            manager.applyProfile(serverB)
            val inTheGap = http.get()

            assertEquals(serverB.baseUrl, builtFor[inTheGap])
            assertFalse(clientA.isOpen())
        }

    @Test
    fun rebind_keepsTheClientAlreadySwappedForTheBoundConnection() =
        runTest {
            val manager = ActiveConnectionManager(SingleProfileStore())
            manager.applyProfile(serverA)
            val http = OpencodeHttpClient(manager, ::factory)
            http.get()
            manager.applyProfile(serverB)
            val inFlight = http.get()

            http.rebind()

            assertSame(inFlight, http.get())
            assertTrue(inFlight.isOpen())
        }

    @Test
    fun get_afterAPasswordChange_doesNotKeepTheOldCredential() =
        runTest {
            val manager = ActiveConnectionManager(SingleProfileStore())
            manager.applyProfile(serverA)
            val http = OpencodeHttpClient(manager, ::factory)
            val old = http.get()

            manager.applyProfile(serverA.copy(password = "other-test-password"))

            assertNotSame(old, http.get())
        }

    @Test
    fun pinnedGet_afterABind_failsAndNeverReturnsTheNewServersClient() =
        runTest {
            val manager = ActiveConnectionManager(SingleProfileStore())
            manager.applyProfile(serverA)
            val http = OpencodeHttpClient(manager, ::factory)
            val clientA = http.get()
            val pin = manager.pinConnection()
            withContext(manager.pinContext(pin)) { assertSame(clientA, http.get()) }

            manager.applyProfile(serverB)
            assertEquals(serverB.baseUrl, builtFor[http.get()])

            val failure = withContext(manager.pinContext(pin)) { runCatching { http.get() } }.exceptionOrNull()
            assertTrue(failure is ConnectionChangedException)
        }

    @Test
    fun sseGet_afterABind_neverReturnsThePreviousServersClient() =
        runTest {
            val manager = ActiveConnectionManager(SingleProfileStore())
            manager.applyProfile(serverA)
            val sse = OpencodeSseHttpClient(manager, ::factory)

            manager.applyProfile(serverB)

            assertEquals(serverB.baseUrl, builtFor[sse.get()])
        }

    @Test
    fun sseRebuild_stillClosesDialsOfThePreviousConnection() =
        runTest {
            val manager = ActiveConnectionManager(SingleProfileStore())
            manager.applyProfile(serverA)
            val sse = OpencodeSseHttpClient(manager, ::factory)
            val dial = sse.openDial(1)

            manager.applyProfile(serverB)
            sse.get()
            sse.rebuild()

            assertFalse(dial.isOpen())
            assertEquals(serverB.baseUrl, builtFor[sse.openDial(2)])
        }
}

private class SingleProfileStore : ConnectionProfileStore {
    private var profile: ConnectionProfile? = null

    override suspend fun activeProfile(): ConnectionProfile? = profile

    override suspend fun saveProfile(profile: ConnectionProfile) {
        this.profile = profile
    }

    override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = emptyList()

    override suspend fun addWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) = Unit

    override suspend fun removeWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) = Unit

    override suspend fun setAddedWorktrees(
        serverBaseUrl: String,
        worktrees: List<String>,
    ) = Unit

    override suspend fun clearAll() {
        profile = null
    }
}
