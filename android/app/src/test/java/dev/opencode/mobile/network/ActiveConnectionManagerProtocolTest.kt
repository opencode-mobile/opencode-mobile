package dev.opencode.mobile.network

import dev.opencode.mobile.domain.connection.ConnectionProbing
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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

    @Test
    fun currentProtocolOrProbe_returnsCachedProtocolWithoutProbing() =
        runTest {
            var probes = 0
            val manager =
                ActiveConnectionManager(
                    FakeProfileStore(),
                    ConnectionProbing {
                        probes += 1
                        ServerProtocol.V1
                    },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V2)

            assertEquals(ServerProtocol.V2, manager.currentProtocolOrProbe())
            assertEquals(0, probes)
        }

    @Test
    fun currentProtocolOrProbe_probesOnceOnCacheMiss() =
        runTest {
            var probes = 0
            val manager =
                ActiveConnectionManager(
                    FakeProfileStore(),
                    ConnectionProbing {
                        probes += 1
                        ServerProtocol.V2
                    },
                )
            manager.applyProfile(sampleProfile)

            assertEquals(ServerProtocol.V2, manager.currentProtocolOrProbe())
            assertEquals(ServerProtocol.V2, manager.currentProtocol())
            assertEquals(ServerProtocol.V2, manager.currentProtocolOrProbe())
            assertEquals(1, probes)
        }

    @Test
    fun currentProtocolOrProbe_parallelCallersShareOneProbe() =
        runTest {
            var probes = 0
            val manager =
                ActiveConnectionManager(
                    FakeProfileStore(),
                    ConnectionProbing {
                        probes += 1
                        delay(50)
                        ServerProtocol.V2
                    },
                )
            manager.applyProfile(sampleProfile)

            val first = async { manager.currentProtocolOrProbe() }
            val second = async { manager.currentProtocolOrProbe() }

            assertEquals(ServerProtocol.V2, first.await())
            assertEquals(ServerProtocol.V2, second.await())
            assertEquals(1, probes)
        }

    @Test
    fun currentProtocolOrProbe_failedProbeReturnsV1WithoutThrowing() =
        runTest {
            val manager =
                ActiveConnectionManager(
                    FakeProfileStore(),
                    ConnectionProbing { error("unreachable") },
                )
            manager.applyProfile(sampleProfile)

            assertEquals(ServerProtocol.V1, manager.currentProtocolOrProbe())
            assertNull(manager.currentProtocol())
        }

    @Test
    fun currentProtocolOrProbe_failedProbeDoesNotReprobeUntilForced() =
        runTest {
            var probes = 0
            val manager =
                ActiveConnectionManager(
                    FakeProfileStore(),
                    ConnectionProbing {
                        probes += 1
                        error("unreachable")
                    },
                )
            manager.applyProfile(sampleProfile)

            assertEquals(ServerProtocol.V1, manager.currentProtocolOrProbe())
            assertEquals(ServerProtocol.V1, manager.currentProtocolOrProbe())
            assertEquals(1, probes)
            runCatching { manager.probeActive() }
            assertEquals(2, probes)
        }

    @Test
    fun protocolHint_defaultsToV1WhenUnknown() =
        runTest {
            val manager = ActiveConnectionManager(FakeProfileStore())
            manager.applyProfile(sampleProfile)
            assertEquals(ServerProtocol.V1, manager.protocolHint())
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V2)
            assertEquals(ServerProtocol.V2, manager.protocolHint())
        }

    @Test
    fun currentProtocolOrProbe_missingConnectionReturnsV1WithoutProbing() =
        runTest {
            var probes = 0
            val manager =
                ActiveConnectionManager(
                    FakeProfileStore(),
                    ConnectionProbing {
                        probes += 1
                        ServerProtocol.V2
                    },
                )

            assertEquals(ServerProtocol.V1, manager.currentProtocolOrProbe())
            assertEquals(0, probes)
        }

    @Test
    fun currentProtocolOrProbe_doesNotStampProtocolAfterDisconnect() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val manager =
                ActiveConnectionManager(
                    FakeProfileStore(),
                    ConnectionProbing {
                        started.complete(Unit)
                        release.await()
                        ServerProtocol.V2
                    },
                )
            manager.applyProfile(sampleProfile)

            val probe = async { manager.currentProtocolOrProbe() }
            started.await()
            manager.disconnect()
            release.complete(Unit)

            assertEquals(ServerProtocol.V1, probe.await())
            assertNull(manager.current())
            assertNull(manager.currentProtocol())
        }
}

private val sampleProfile =
    ConnectionProfile(baseUrl = "http://127.0.0.1:4096/", username = "opencode", password = "x")

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
