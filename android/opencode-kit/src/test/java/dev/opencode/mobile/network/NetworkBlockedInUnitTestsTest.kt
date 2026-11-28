package dev.opencode.mobile.network

import dev.opencode.testguard.TestIsolationGuard
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** The production Ktor client can't reach a real host from a unit test (android/test-guard). */
class NetworkBlockedInUnitTestsTest {
    @Test
    fun productionKtorClientCannotReachARealHost() {
        val (_, violations) =
            TestIsolationGuard.capturingViolations {
                val client = KtorClientFactory.create(ServerConnection(baseUrl = "https://example.com"), Json, probe = true)
                try {
                    runBlocking { client.get("global/health") }
                    fail("a real host must not be reachable from a unit test")
                } catch (expected: IOException) {
                    // Refused by the network block before it leaves the machine.
                } finally {
                    client.close()
                }
            }

        assertEquals(1, violations.size)
        assertTrue(violations.single(), violations.single().contains("https://example.com"))
        assertTrue(violations.single(), violations.single().contains("productionKtorClientCannotReachARealHost"))
    }
}
