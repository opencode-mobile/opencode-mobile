package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.domain.project.ServerProbeError
import dev.opencode.mobile.network.ServerConnection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressProbeTest {
    private val lan = "http://192.168.1.60:4096/"
    private val tailscale = "http://100.64.0.15:4096/"

    @Test
    fun resolve_sendsCredentialsOnlyToTheChosenHost() =
        runTest {
            val probe = RecordingCredentialProbe()
            val resolution =
                ServerAddressProbe.resolve(listOf(lan, tailscale), "opencode", "secret", lan, probe)

            assertEquals(tailscale, resolution.chosen)
            assertEquals(ServerProtocol.V2, resolution.reachableProtocol)
            assertEquals(setOf(lan, tailscale), probe.unauthenticated.toSet())
            assertEquals(listOf(tailscale), probe.authenticated)
            assertTrue(resolution.unreachable.isEmpty())
        }

    @Test
    fun resolve_rejectedCredentialsAreAFailureAndNoOtherHostGetsThem() =
        runTest {
            val probe = RecordingCredentialProbe(acceptsPassword = false)
            val resolution =
                ServerAddressProbe.resolve(listOf(lan, tailscale), "opencode", "wrong", lan, probe)

            assertEquals(tailscale, resolution.chosen)
            assertNull(resolution.reachableProtocol)
            assertEquals(ProbeStatus.AuthFailed, resolution.failure?.toProbeStatus())
            assertEquals(listOf(tailscale), probe.authenticated)
        }

    @Test
    fun resolve_silentHostsAreUnreachableAndNeverReceiveCredentials() =
        runTest {
            val probe = RecordingCredentialProbe(silentHosts = setOf("100.64.0.15"))
            val resolution =
                ServerAddressProbe.resolve(listOf(lan, tailscale), "opencode", "secret", lan, probe)

            assertEquals(lan, resolution.chosen)
            assertEquals(setOf(tailscale), resolution.unreachable)
            assertEquals(listOf(lan), probe.authenticated)
        }

    @Test
    fun resolve_nothingAnswered_choosesNobodyAndSendsNoCredentials() =
        runTest {
            val probe = RecordingCredentialProbe(silentHosts = setOf("192.168.1.60", "100.64.0.15"))
            val resolution =
                ServerAddressProbe.resolve(listOf(lan, tailscale), "opencode", "secret", lan, probe)

            assertNull(resolution.chosen)
            assertTrue(resolution.nothingAnswered)
            assertTrue(probe.authenticated.isEmpty())
            assertEquals(setOf(lan, tailscale), resolution.unreachable)
        }

    @Test
    fun resolve_httpsBaseUrl_ignoresCleartextCandidates() =
        runTest {
            val tunnel = "https://box.opentunnel.xyz/"
            val probe = RecordingCredentialProbe(silentHosts = setOf("box.opentunnel.xyz"))
            val resolution =
                ServerAddressProbe.resolve(listOf(tunnel, lan), "opencode", "secret", tunnel, probe)

            assertNull(resolution.chosen)
            assertTrue(probe.authenticated.isEmpty())
        }

    @Test
    fun resolve_emptyPassword_reusesTheUnauthenticatedAnswer() =
        runTest {
            val probe = RecordingCredentialProbe()
            val resolution = ServerAddressProbe.resolve(listOf(lan), "opencode", "", lan, probe)

            assertEquals(lan, resolution.chosen)
            assertEquals(ProbeStatus.AuthFailed, resolution.failure?.toProbeStatus())
            assertEquals(listOf(lan), probe.unauthenticated)
            assertTrue(probe.authenticated.isEmpty())
        }

    @Test
    fun resolve_openServerWithoutPassword_isReachable() =
        runTest {
            val probe = RecordingCredentialProbe(openProtocol = ServerProtocol.V1)
            val resolution = ServerAddressProbe.resolve(listOf(lan), "opencode", "", lan, probe)

            assertEquals(ServerProtocol.V1, resolution.reachableProtocol)
            assertTrue(probe.authenticated.isEmpty())
        }
}

/**
 * `401` to requests without a password unless [openProtocol] is set, [acceptsPassword] decides
 * the authenticated answer, and [silentHosts] fail at the transport layer.
 */
private class RecordingCredentialProbe(
    private val acceptsPassword: Boolean = true,
    private val silentHosts: Set<String> = emptySet(),
    private val openProtocol: ServerProtocol? = null,
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
        if (!hasPassword && openProtocol != null) return openProtocol
        throw ServerProbeError.Http(401, "Unauthorized")
    }
}
