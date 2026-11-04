package dev.opencode.hexcode.firstrun

import dev.opencode.mobile.domain.connection.ConnectionProbing
import dev.opencode.mobile.domain.connection.DetectedServer
import dev.opencode.mobile.domain.connection.PairingCodeRedeemException
import dev.opencode.mobile.domain.connection.PairingCodeRedeeming
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.project.ServerProbeError
import dev.opencode.mobile.network.ServerConnection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingConnectorTest {
    private val lan = "http://192.168.1.2:4096"
    private val tailnet = "http://100.64.0.3:4096"
    private val code = """{"code":"7Q4K9M","urls":["$lan","$tailnet"]}"""

    /** A server that wants a password, at [answering] addresses, on [protocol] [version]. */
    private class FakeServer(
        val answering: Set<String>,
        val protocol: ServerProtocol = ServerProtocol.V2,
        val version: String? = "2.2.5",
        val token: String = "test-password",
    ) : ConnectionProbing {
        val passwordsSent = mutableListOf<Pair<String, String?>>()

        override suspend fun probe(connection: ServerConnection): ServerProtocol = probeServer(connection).protocol

        override suspend fun probeServer(connection: ServerConnection): DetectedServer {
            val host = connection.baseUrl.trimEnd('/')
            passwordsSent += host to connection.password
            if (host !in answering) throw ServerProbeError.Transport(java.io.IOException("no route"))
            if (connection.password != token) throw ServerProbeError.Http(401, "Unauthorized")
            return DetectedServer(protocol, version)
        }
    }

    private class Redeem(
        val token: String = "test-password",
        val error: PairingCodeRedeemException? = null,
    ) : PairingCodeRedeeming {
        var redeemedOn: String? = null

        override suspend fun redeem(
            baseUrl: String,
            code: String,
        ): String {
            redeemedOn = baseUrl
            error?.let { throw it }
            return token
        }
    }

    private fun connector(
        server: FakeServer,
        redeem: Redeem = Redeem(),
        missing: List<String> = emptyList(),
    ) = PairingConnector(server, redeem, { missing })

    @Test
    fun pairsOnTheTailscaleAddressAndSavesV2() =
        runTest {
            val server = FakeServer(answering = setOf(lan, tailnet))
            val redeem = Redeem()
            val hosts = mutableListOf<String>()
            val result = connector(server, redeem).connect(code) { hosts += (it as ConnectProgress.Connecting).host }
            result as ConnectResult.Connected
            assertEquals(tailnet, redeem.redeemedOn?.trimEnd('/'))
            assertEquals("100.64.0.3", result.host)
            assertEquals("2.2.5", result.version)
            assertEquals(ServerProtocol.V2, result.profile.serverProtocol)
            assertEquals("test-password", result.profile.password)
            assertEquals(2, result.profile.serverUrls.size)
            assertTrue(hosts.isNotEmpty())
        }

    @Test
    fun thePasswordOnlyGoesToTheChosenHost() =
        runTest {
            val server = FakeServer(answering = setOf(lan, tailnet))
            connector(server).connect(code)
            val withPassword =
                server.passwordsSent
                    .filter { !it.second.isNullOrEmpty() }
                    .map { it.first }
                    .toSet()
            assertEquals(setOf(tailnet), withPassword)
        }

    @Test
    fun aUsedCodeSaysSo() =
        runTest {
            val result = connector(FakeServer(setOf(lan)), Redeem(error = PairingCodeRedeemException.Expired())).connect(code)
            assertEquals(ConnectResult.Failed(ConnectFailure.CodeUsed), result)
        }

    @Test
    fun nothingAnsweringIsUnreachable() =
        runTest {
            val result = connector(FakeServer(emptySet())).connect(code)
            result as ConnectResult.Failed
            assertEquals(ServerCheck.Reach, result.failure.check)
        }

    @Test
    fun aV1ServerIsNotOpenCode2() =
        runTest {
            val result = connector(FakeServer(setOf(lan, tailnet), protocol = ServerProtocol.V1)).connect(code)
            assertEquals(ConnectResult.Failed(ConnectFailure.NotOpenCode2), result)
        }

    @Test
    fun anOlderReleaseIsTooOld() =
        runTest {
            val result = connector(FakeServer(setOf(lan, tailnet), version = "2.2.2")).connect(code)
            assertEquals(ConnectResult.Failed(ConnectFailure.TooOld("2.2.2")), result)
        }

    @Test
    fun missingRoutesStopPairing() =
        runTest {
            val result = connector(FakeServer(setOf(lan, tailnet)), missing = listOf("/api/experimental/generate")).connect(code)
            assertEquals(ConnectResult.Failed(ConnectFailure.MissingRoutes(listOf("/api/experimental/generate"))), result)
        }

    @Test
    fun anythingElseIsNotAPairingCode() =
        runTest {
            assertEquals(ConnectResult.Failed(ConnectFailure.NotAPairingCode), connector(FakeServer(emptySet())).connect("hello"))
        }

    @Test
    fun onlyLocalAddressesCannotBeUsed() =
        runTest {
            val result = connector(FakeServer(emptySet())).connect("""{"code":"7Q4K9M","urls":["http://127.0.0.1:4096"]}""")
            assertEquals(ConnectResult.Failed(ConnectFailure.OnlyLoopback), result)
        }

    @Test
    fun plainHttpOutsideTheLanIsRefusedBeforeAnythingIsSent() =
        runTest {
            val server = FakeServer(setOf("http://203.0.113.7:4096"))
            val redeem = Redeem()
            val result = connector(server, redeem).connect("""{"code":"7Q4K9M","urls":["http://203.0.113.7:4096"]}""")
            assertEquals(ConnectResult.Failed(ConnectFailure.NotEncrypted("203.0.113.7")), result)
            assertTrue(server.passwordsSent.isEmpty())
            assertEquals(null, redeem.redeemedOn)
        }

    @Test
    fun anEncryptedTunnelAddressIsFine() =
        runTest {
            val tunnel = "https://example.opentunnel.xyz"
            val result = connector(FakeServer(setOf(tunnel))).connect("$tunnel/auth/connect/7Q4K9M")
            assertTrue(result is ConnectResult.Connected)
        }

    @Test
    fun versionFloorIsTwoTwoFive() {
        assertTrue(ServerVersion.isSupported("2.2.5"))
        assertTrue(ServerVersion.isSupported("v2.3.0"))
        assertTrue(ServerVersion.isSupported("3.0.0"))
        assertFalse(ServerVersion.isSupported("2.2.4"))
        assertFalse(ServerVersion.isSupported("2.1.9"))
        assertFalse(ServerVersion.isSupported("1.19.5"))
        // Source and preview builds can't be judged by number; the route check decides.
        assertTrue(ServerVersion.isSupported("local"))
        assertTrue(ServerVersion.isSupported("0.0.0-beta-12"))
        assertTrue(ServerVersion.isSupported(null))
    }
}
