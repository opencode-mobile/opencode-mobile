package dev.opencode.mobile.network

import dev.opencode.mobile.domain.connection.ConnectionProbing
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.DetectedServer
import dev.opencode.mobile.domain.connection.ProtocolRedetection
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.project.ServerProbeError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveConnectionManagerProtocolTest {
    @Test
    fun probeActive_keepsServerVersionUntilDisconnect() =
        runTest {
            val manager =
                ActiveConnectionManager(
                    FakeProfileStore(),
                    object : ConnectionProbing {
                        override suspend fun probe(connection: ServerConnection): ServerProtocol = ServerProtocol.V2

                        override suspend fun probeServer(connection: ServerConnection): DetectedServer =
                            DetectedServer(ServerProtocol.V2, version = "1.3.2")
                    },
                )
            manager.applyProfile(sampleProfile)
            assertNull(manager.currentServerVersion())

            manager.probeActive()

            assertEquals("1.3.2", manager.currentServerVersion())
            manager.disconnect()
            assertNull(manager.currentServerVersion())
        }

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
    fun redetectProtocol_movesSavedV1ProfileToV2AndSavesIt() =
        runTest {
            val store = FakeProfileStore()
            val manager = ActiveConnectionManager(store, ConnectionProbing { ServerProtocol.V2 })
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V1)

            val result = manager.redetectProtocol()

            assertEquals(ProtocolRedetection.Detected(ServerProtocol.V2), result)
            assertEquals(ServerProtocol.V2, manager.currentProtocol())
            assertEquals(ServerProtocol.V2, store.lastSaved?.serverProtocol)
            assertEquals(sampleProfile.baseUrl, store.lastSaved?.baseUrl)
        }

    @Test
    fun redetectProtocol_movesSavedV2ProfileBackToV1() =
        runTest {
            val store = FakeProfileStore()
            val manager = ActiveConnectionManager(store, ConnectionProbing { ServerProtocol.V1 })
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V2)

            assertEquals(ProtocolRedetection.Detected(ServerProtocol.V1), manager.redetectProtocol())
            assertEquals(ServerProtocol.V1, store.lastSaved?.serverProtocol)
        }

    @Test
    fun redetectProtocol_noAnswerKeepsSavedProtocol() =
        runTest {
            val store = FakeProfileStore()
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { throw ServerProbeError.Transport(java.io.IOException("refused")) },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V1)

            assertEquals(ProtocolRedetection.Unreachable, manager.redetectProtocol())
            assertEquals(ServerProtocol.V1, manager.currentProtocol())
            assertEquals(ServerProtocol.V1, store.lastSaved?.serverProtocol)
        }

    @Test
    fun redetectProtocol_rejectedCredentialIsNotAnAnswer() =
        runTest {
            val store = FakeProfileStore()
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { throw ServerProbeError.Http(statusCode = 401, message = "Unauthorized") },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V1)

            assertEquals(ProtocolRedetection.Skipped, manager.redetectProtocol())
            assertEquals(ServerProtocol.V1, manager.currentProtocol())
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
    fun currentProtocolOrProbe_cancelledProbeDoesNotLatchMiss() =
        runTest {
            var probes = 0
            val started = CompletableDeferred<Unit>()
            val manager =
                ActiveConnectionManager(
                    FakeProfileStore(),
                    ConnectionProbing {
                        probes += 1
                        if (probes == 1) {
                            started.complete(Unit)
                            awaitCancellation()
                        }
                        ServerProtocol.V2
                    },
                )
            manager.applyProfile(sampleProfile)

            val probe = async { manager.currentProtocolOrProbe() }
            started.await()
            probe.cancelAndJoin()

            assertEquals(ServerProtocol.V2, manager.currentProtocolOrProbe())
            assertEquals(2, probes)
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

    @Test
    fun applyProfile_reselectsTailscaleAndKeepsServerUrls() =
        runTest {
            val lan = "http://192.168.0.160:4096/"
            val tailscale = "http://100.84.57.15:4096/"
            val store = FakeProfileStore()
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { ServerProtocol.V2 },
                )
            manager.applyProfile(
                ConnectionProfile(
                    id = "profile-1",
                    baseUrl = lan,
                    username = "opencode",
                    password = "token",
                    serverUrls = listOf(lan, tailscale),
                ),
            )

            assertEquals(tailscale, manager.current()?.baseUrl)
            val current = manager.currentProfile()
            assertEquals("profile-1", current?.id)
            assertEquals(listOf(lan, tailscale), current?.serverUrls)
            assertEquals("token", current?.password)
            assertEquals(tailscale, store.lastSaved?.baseUrl)
            assertEquals("profile-1", store.lastSaved?.id)
        }

    @Test
    fun ensureLoaded_reselectsTailscaleWhenStoredBaseUrlIsLan() =
        runTest {
            val lan = "http://192.168.0.160:4096/"
            val tailscale = "http://100.84.57.15:4096/"
            val store = FakeProfileStore()
            store.saveProfile(
                ConnectionProfile(
                    id = "profile-1",
                    baseUrl = lan,
                    username = "opencode",
                    password = "token",
                    serverUrls = listOf(lan, tailscale),
                ),
            )
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { ServerProtocol.V2 },
                )

            manager.ensureLoaded()

            assertEquals(tailscale, manager.current()?.baseUrl)
            val current = manager.currentProfile()
            assertEquals("profile-1", current?.id)
            assertEquals(listOf(lan, tailscale), current?.serverUrls)
        }

    @Test
    fun ensureLoaded_sendsThePasswordOnlyToTheChosenAddress() =
        runTest {
            val lan = "http://192.168.0.160:4096/"
            val tailscale = "http://100.84.57.15:4096/"
            val store = FakeProfileStore()
            store.saveProfile(
                ConnectionProfile(
                    id = "profile-1",
                    baseUrl = lan,
                    username = "opencode",
                    password = "token",
                    serverUrls = listOf(lan, tailscale),
                ),
            )
            val probe = CredentialRecordingProbe()
            val manager = ActiveConnectionManager(store, probe)

            manager.ensureLoaded()

            assertEquals(setOf(lan, tailscale), probe.unauthenticated.toSet())
            assertEquals(listOf(tailscale), probe.authenticated)
            assertEquals(tailscale, manager.current()?.baseUrl)
            assertEquals(tailscale, store.lastSaved?.baseUrl)
        }

    @Test
    fun ensureLoaded_keepsTheStoredAddressWhenTheChosenHostRejectsThePassword() =
        runTest {
            val lan = "http://192.168.0.160:4096/"
            val tailscale = "http://100.84.57.15:4096/"
            val store = FakeProfileStore()
            store.saveProfile(
                ConnectionProfile(
                    id = "profile-1",
                    baseUrl = lan,
                    username = "opencode",
                    password = "stale",
                    serverUrls = listOf(lan, tailscale),
                ),
            )
            val probe = CredentialRecordingProbe(acceptsPassword = false)
            val manager = ActiveConnectionManager(store, probe)

            manager.ensureLoaded()

            assertEquals(listOf(tailscale), probe.authenticated)
            assertEquals(lan, manager.current()?.baseUrl)
            assertEquals(lan, manager.currentProfile()?.baseUrl)
        }

    @Test
    fun applyProfile_neverReplacesHttpsWithHttp() =
        runTest {
            val tunnel = "https://box.opentunnel.xyz/"
            val lan = "http://192.168.0.160:4096/"
            val probe = CredentialRecordingProbe(silentHosts = setOf("box.opentunnel.xyz"))
            val manager = ActiveConnectionManager(FakeProfileStore(), probe)

            manager.applyProfile(
                ConnectionProfile(
                    id = "profile-1",
                    baseUrl = tunnel,
                    username = "opencode",
                    password = "token",
                    serverUrls = listOf(tunnel, lan),
                ),
            )

            assertTrue(probe.authenticated.isEmpty())
            assertEquals(tunnel, manager.current()?.baseUrl)
        }

    @Test
    fun applyProfile_whenNoAddressAnswers_keepsStoredBaseUrl() =
        runTest {
            val lan = "http://192.168.0.160:4096/"
            val tailscale = "http://100.84.57.15:4096/"
            val manager =
                ActiveConnectionManager(
                    FakeProfileStore(),
                    ConnectionProbing {
                        throw ServerProbeError.Transport(IllegalStateException("down"))
                    },
                )

            manager.applyProfile(
                ConnectionProfile(
                    id = "profile-1",
                    baseUrl = lan,
                    username = "opencode",
                    password = "token",
                    serverUrls = listOf(lan, tailscale),
                ),
            )

            assertEquals(lan, manager.current()?.baseUrl)
            assertEquals(listOf(lan, tailscale), manager.currentProfile()?.serverUrls)
            assertEquals("profile-1", manager.currentProfile()?.id)
        }
}

private val sampleProfile =
    ConnectionProfile(baseUrl = "http://127.0.0.1:4096/", username = "opencode", password = "x")

private class FakeProfileStore : ConnectionProfileStore {
    private var profile: ConnectionProfile? = null
    var lastSaved: ConnectionProfile? = null
        private set

    override suspend fun activeProfile(): ConnectionProfile? = profile

    override suspend fun saveProfile(profile: ConnectionProfile) {
        lastSaved = profile
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

/** 401 without a password; [acceptsPassword] decides the authenticated answer; [silentHosts] time out. */
private class CredentialRecordingProbe(
    private val acceptsPassword: Boolean = true,
    private val silentHosts: Set<String> = emptySet(),
) : ConnectionProbing {
    val unauthenticated = mutableListOf<String>()
    val authenticated = mutableListOf<String>()

    override suspend fun probe(connection: ServerConnection): ServerProtocol {
        val url = connection.baseUrl
        val hasPassword = !connection.password.isNullOrEmpty()
        synchronized(this) {
            if (hasPassword) authenticated += url else unauthenticated += url
        }
        if (silentHosts.any { url.contains(it) }) {
            throw ServerProbeError.Transport(IllegalStateException("down"))
        }
        if (hasPassword && acceptsPassword) return ServerProtocol.V2
        throw ServerProbeError.Http(401, "Unauthorized")
    }
}
