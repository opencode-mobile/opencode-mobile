package dev.opencode.mobile.domain.terminal

import dev.opencode.mobile.domain.connection.ServerProtocol
import io.ktor.http.Url
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class TerminalConnectRequestTest {
    @Test
    fun v1Https_usesWssDirectoryAndCursor() {
        val request =
            connectRequest(
                id = "pty_1",
                directory = "/repo",
                protocol = ServerProtocol.V1,
                baseUrl = "https://example.test/",
                cursor = "0",
                username = "opencode",
                password = "",
            )

        val url = Url(request.url)
        assertEquals("wss", url.protocol.name)
        assertEquals("/pty/pty_1/connect", url.encodedPath)
        assertEquals("/repo", url.parameters["directory"])
        assertEquals("0", url.parameters["cursor"])
        assertNull(url.parameters["location[directory]"])
        assertNull(url.parameters["auth_token"])
        assertNull(request.authorization)
        assertNull(url.parameters["ticket"])
    }

    @Test
    fun v2Https_usesWssLocationDirectoryCursorAndAuthToken() {
        val request =
            connectRequest(
                id = "pty_2",
                directory = "/repo",
                protocol = ServerProtocol.V2,
                baseUrl = "https://example.test",
                cursor = "12",
                username = "opencode",
                password = "secret",
            )

        val url = Url(request.url)
        assertEquals("wss", url.protocol.name)
        assertEquals("/api/pty/pty_2/connect", url.encodedPath)
        assertEquals("/repo", url.parameters["location[directory]"])
        assertEquals("12", url.parameters["cursor"])
        assertNull(url.parameters["directory"])
        val expected =
            Base64.getEncoder().encodeToString("opencode:secret".toByteArray(Charsets.UTF_8))
        assertEquals(expected, url.parameters["auth_token"])
        assertEquals("Basic $expected", request.authorization)
        assertNull(url.parameters["ticket"])
    }

    @Test
    fun v1Http_usesWsAndAuthToken() {
        val request =
            connectRequest(
                id = "pty_1",
                directory = "/repo",
                protocol = ServerProtocol.V1,
                baseUrl = "http://127.0.0.1:4096",
                cursor = "3",
                username = "opencode",
                password = "secret",
            )

        val url = Url(request.url)
        assertEquals("ws", url.protocol.name)
        assertEquals("/pty/pty_1/connect", url.encodedPath)
        val expected =
            Base64.getEncoder().encodeToString("opencode:secret".toByteArray(Charsets.UTF_8))
        assertEquals(expected, url.parameters["auth_token"])
        assertEquals("Basic $expected", request.authorization)
        assertNull(url.parameters["ticket"])
    }

    @Test
    fun ticket_overridesAuthToken() {
        val request =
            connectRequest(
                id = "pty_1",
                directory = "/repo",
                protocol = ServerProtocol.V1,
                baseUrl = "https://example.test",
                cursor = "0",
                ticket = "tick-1",
                username = "opencode",
                password = "secret",
            )

        val url = Url(request.url)
        assertEquals("tick-1", url.parameters["ticket"])
        assertNull(url.parameters["auth_token"])
        assertNull(request.authorization)
    }

    @Test
    fun v2Ticket_setsTicketWithoutAuthToken() {
        val request =
            connectRequest(
                id = "pty_9",
                directory = "/work",
                protocol = ServerProtocol.V2,
                baseUrl = "https://example.test",
                cursor = "1",
                ticket = "tick-2",
                username = "opencode",
                password = "secret",
            )

        val url = Url(request.url)
        assertEquals("/api/pty/pty_9/connect", url.encodedPath)
        assertEquals("/work", url.parameters["location[directory]"])
        assertEquals("tick-2", url.parameters["ticket"])
        assertNull(url.parameters["auth_token"])
        assertNull(request.authorization)
    }
}
