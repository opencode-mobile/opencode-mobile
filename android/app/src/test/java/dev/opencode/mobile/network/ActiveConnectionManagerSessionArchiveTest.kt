package dev.opencode.mobile.network

import dev.opencode.mobile.domain.connection.ConnectionProbing
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.DetectedServer
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.connection.V2ServerFeatures
import dev.opencode.mobile.domain.project.ServerProbeError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveConnectionManagerSessionArchiveTest {
    @Test
    fun v2ServerDeclaringArchive_enablesArchive() =
        runTest {
            val probing = ArchiveProbing(ServerProtocol.V2, archive = { true })
            val manager = connected(probing)

            assertFalse(manager.currentCapabilities().supportsSessionArchive)
            manager.probeActive()

            assertTrue(manager.currentCapabilities().supportsSessionArchive)
            assertEquals(1, probing.archiveProbes)
        }

    @Test
    fun v2ServerDeclaringTurnDiff_enablesLastTurn() =
        runTest {
            val manager = connected(ArchiveProbing(ServerProtocol.V2, archive = { true }))

            assertFalse(manager.currentCapabilities().supportsSessionMessageDiff)
            manager.probeActive()

            assertTrue(manager.currentCapabilities().supportsSessionMessageDiff)
        }

    @Test
    fun olderV2Server_keepsArchiveHidden() =
        runTest {
            val manager = connected(ArchiveProbing(ServerProtocol.V2, archive = { false }))

            manager.probeActive()

            assertFalse(manager.currentCapabilities().supportsSessionArchive)
            assertFalse(manager.currentCapabilities().supportsSessionMessageDiff)
        }

    @Test
    fun unreadableContract_keepsArchiveHiddenAndRetriesNextProbe() =
        runTest {
            var fail = true
            val probing =
                ArchiveProbing(ServerProtocol.V2, archive = {
                    if (fail) throw ServerProbeError.Http(404, "Not Found")
                    true
                })
            val manager = connected(probing)

            manager.probeActive()
            assertFalse(manager.currentCapabilities().supportsSessionArchive)

            fail = false
            manager.probeActive()
            assertTrue(manager.currentCapabilities().supportsSessionArchive)
            assertEquals(2, probing.archiveProbes)
        }

    @Test
    fun sameServerVersion_readsContractOnce() =
        runTest {
            val probing = ArchiveProbing(ServerProtocol.V2, archive = { true })
            val manager = connected(probing)

            manager.probeActive()
            manager.probeActive()

            assertEquals(1, probing.archiveProbes)
            assertTrue(manager.currentCapabilities().supportsSessionArchive)
        }

    @Test
    fun upgradedServer_readsContractAgain() =
        runTest {
            val probing = ArchiveProbing(ServerProtocol.V2, archive = { it == "2.1.0" })
            val manager = connected(probing)

            probing.version = "2.0.31"
            manager.probeActive()
            assertFalse(manager.currentCapabilities().supportsSessionArchive)

            probing.version = "2.1.0"
            manager.probeActive()
            assertTrue(manager.currentCapabilities().supportsSessionArchive)
            assertEquals(2, probing.archiveProbes)
        }

    @Test
    fun v1Server_keepsArchiveWithoutReadingContract() =
        runTest {
            val probing = ArchiveProbing(ServerProtocol.V1, archive = { error("v1 must not read the v2 contract") })
            val manager = connected(probing)

            manager.probeActive()

            assertTrue(manager.currentCapabilities().supportsSessionArchive)
            assertEquals(0, probing.archiveProbes)
        }

    @Test
    fun disconnect_forgetsArchiveSupport() =
        runTest {
            val manager = connected(ArchiveProbing(ServerProtocol.V2, archive = { true }))
            manager.probeActive()

            manager.disconnect()
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V2)

            assertFalse(manager.currentCapabilities().supportsSessionArchive)
        }

    /** Starts from a profile stamped v2, as after an earlier session, so archive waits on the contract. */
    private suspend fun connected(probing: ConnectionProbing): ActiveConnectionManager =
        ActiveConnectionManager(MemoryProfileStore(), probing).also {
            it.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V2)
        }
}

private class ArchiveProbing(
    private val protocol: ServerProtocol,
    private val archive: (String?) -> Boolean,
) : ConnectionProbing {
    var version: String? = "2.1.0"
    var archiveProbes = 0

    override suspend fun probe(connection: ServerConnection): ServerProtocol = protocol

    override suspend fun probeServer(connection: ServerConnection): DetectedServer = DetectedServer(protocol, version)

    override suspend fun probeV2Features(connection: ServerConnection): V2ServerFeatures {
        archiveProbes++
        val supported = archive(version)
        return V2ServerFeatures(sessionArchive = supported, sessionTurnDiff = supported)
    }
}

private class MemoryProfileStore : ConnectionProfileStore {
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

private val sampleProfile =
    ConnectionProfile(baseUrl = "http://127.0.0.1:4096/", username = "opencode", password = "x")
