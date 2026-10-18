package dev.opencode.mobile.domain.feedback

import dev.opencode.mobile.domain.connection.ConnectionProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FeedbackLogRedactorTest {
    private val profile =
        ConnectionProfile(
            baseUrl = "https://zz-devbox.tail1234.ts.net:4096/",
            username = "zz-secret-user",
            password = "zz-secret-password",
            name = "zz-home-mac",
            serverUrls = listOf("http://192.168.1.20:4096/"),
        )

    @Test
    fun redact_stripsAddressesButKeepsTimesAndText() {
        val known = FeedbackLogRedactor.knownValues(profile)

        val redacted =
            FeedbackLogRedactor.redact(
                "17:51:19 SSE attempt cause=ConnectException: Failed to connect to /10.0.2.2:4403 " +
                    "url=https://zz-devbox.tail1234.ts.net:4096/api/event host=localhost:4096 " +
                    "ip6=[fe80::1]:4096 profile zz-home-mac user zz-secret-user dev zz-devbox.tail1234.ts.net",
                known,
            )

        listOf("10.0.2.2", "4403", "4096", "zz-devbox", "localhost:4096", "fe80", "zz-home-mac", "zz-secret-user").forEach {
            assertFalse("still contains $it: $redacted", redacted.contains(it))
        }
        assertEquals(true, redacted.startsWith("17:51:19 SSE attempt cause=ConnectException: Failed to connect to /[REDACTED-HOST]"))
    }

    @Test
    fun knownValues_skipDefaultUsername() {
        val known = FeedbackLogRedactor.knownValues(profile.copy(username = "opencode"))

        assertFalse(known.contains("opencode"))
        assertEquals(true, known.contains("192.168.1.20"))
    }

    @Test
    fun redactJsonl_rewritesOnlyEntryMessages() {
        val jsonl =
            """{"type":"meta","entryCount":1}""" + "\n" +
                """{"type":"entry","id":"a","area":"sse","level":"warning","message":"connect 10.0.2.2:4096 failed"}""" + "\n"

        val lines = FeedbackLogRedactor.redactJsonl(jsonl).lines()

        assertEquals("""{"type":"meta","entryCount":1}""", lines[0])
        val entry = Json.parseToJsonElement(lines[1]).jsonObject
        assertEquals("connect [REDACTED-HOST] failed", entry.getValue("message").jsonPrimitive.content)
        assertEquals("sse", entry.getValue("area").jsonPrimitive.content)
    }
}
