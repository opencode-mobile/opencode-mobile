package dev.opencode.testguard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Locale
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class TestIsolationGuardTest {
    @Test
    fun theListenerInstalledTheNetworkBlockForThisJvm() {
        assertTrue(ProxySelector.getDefault() is NetworkBlock)
    }

    @Test
    fun httpUrlConnectionToARealHostFailsAndIsRecorded() {
        val (_, violations) =
            TestIsolationGuard.capturingViolations {
                val connection = URI("https://example.com/private/path?token=secret").toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                try {
                    connection.responseCode
                    fail("a real host must not be reachable from a unit test")
                } catch (expected: IOException) {
                    // Refused by the network block's dead-end proxy.
                } finally {
                    connection.disconnect()
                }
            }

        assertEquals(1, violations.size)
        assertTrue(violations.single(), violations.single().contains("https://example.com"))
        assertFalse("never the path or query", violations.single().contains("private") || violations.single().contains("secret"))
    }

    @Test
    fun aPlainSocketToARealAddressFailsAndIsRecorded() {
        val (_, violations) =
            TestIsolationGuard.capturingViolations {
                Socket().use { socket ->
                    try {
                        // A documentation address (RFC 5737): without the guard this would time out.
                        socket.connect(InetSocketAddress(InetAddress.getByName("203.0.113.7"), 443), 5_000)
                        fail("a real address must not be reachable from a unit test")
                    } catch (expected: IOException) {
                        // Refused by the network block's dead-end SOCKS proxy.
                    }
                }
            }

        assertEquals(1, violations.size)
        assertTrue(violations.single(), violations.single().contains("socket://203.0.113.7"))
    }

    @Test
    fun loopbackServersStillWork() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val responder =
                thread {
                    server.accept().use { client ->
                        client.getInputStream().bufferedReader().readLine()
                        client.getOutputStream().write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                }
            val (code, violations) =
                TestIsolationGuard.capturingViolations {
                    val connection = URI("http://127.0.0.1:${server.localPort}/").toURL().openConnection() as HttpURLConnection
                    try {
                        connection.responseCode
                    } finally {
                        connection.disconnect()
                    }
                }
            responder.join(5_000)

            assertEquals(204, code)
            assertEquals(emptyList<String>(), violations)
        }
    }

    @Test
    fun loopbackIsDecidedFromTheLiteralWithoutResolving() {
        listOf("localhost", "LOCALHOST.", "api.localhost", "127.0.0.1", "127.1.2.3", "::1", "[::1]").forEach {
            assertTrue(it, NetworkBlock.isLoopback(it))
        }
        listOf("example.com", "localhost.example.com", "127.0.0.1.example.com", "10.0.0.1", "192.168.1.2", "").forEach {
            assertFalse(it, NetworkBlock.isLoopback(it))
        }
    }

    @Test
    fun setMainWithoutResetMainIsReportedAndReset() {
        val (_, violations) =
            TestIsolationGuard.capturingViolations {
                Dispatchers.setMain(StandardTestDispatcher())
                TestIsolationGuard.containerFinished("LeakySampleTest")
            }

        assertEquals(1, violations.size)
        assertTrue(violations.single(), violations.single().contains("LeakySampleTest leaked Dispatchers.Main"))
        val main = Dispatchers.Main
        assertNotSame("the guard ran resetMain", main, runCatching { main.immediate }.getOrNull())
    }

    @Test
    fun aChangedDefaultLocaleIsReportedAndRestored() {
        val original = Locale.getDefault()
        val other = if (original == Locale.JAPAN) Locale.GERMANY else Locale.JAPAN
        val (_, violations) =
            TestIsolationGuard.capturingViolations {
                Locale.setDefault(other)
                TestIsolationGuard.containerFinished("LocaleSampleTest")
            }

        assertEquals(1, violations.size)
        assertTrue(violations.single(), violations.single().contains("LocaleSampleTest leaked Locale.getDefault()"))
        assertEquals(original, Locale.getDefault())
    }

    @Test
    fun replacingTheProxySelectorIsReportedAndTheBlockReinstalled() {
        val block = ProxySelector.getDefault()
        val (_, violations) =
            TestIsolationGuard.capturingViolations {
                ProxySelector.setDefault(null)
                TestIsolationGuard.containerFinished("ProxySampleTest")
            }

        assertEquals(1, violations.size)
        assertTrue(violations.single(), violations.single().contains("ProxySampleTest replaced the default ProxySelector"))
        assertTrue(ProxySelector.getDefault() === block)
    }
}
