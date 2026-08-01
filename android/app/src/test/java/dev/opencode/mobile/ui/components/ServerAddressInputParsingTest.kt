package dev.opencode.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerAddressInputParsingTest {
    @Test
    fun parse_defaultsToHttpAndStripsPath() {
        val parts = parseServerAddressValue("http://127.0.0.1:4096/extra")
        assertEquals(ServerAddressScheme.Http, parts.scheme)
        assertEquals("127.0.0.1:4096", parts.hostPort)
        assertEquals("http://127.0.0.1:4096/", parts.toBaseUrl())
    }

    @Test
    fun parse_detectsHttps() {
        val parts = parseServerAddressValue("HTTPS://192.168.1.10:8080/")
        assertEquals(ServerAddressScheme.Https, parts.scheme)
        assertEquals("192.168.1.10:8080", parts.hostPort)
    }

    @Test
    fun filter_stripsDisallowedCharacters() {
        assertEquals("10.0.2.2:4096host", filterServerAddressHostPort("10.0.2.2:4096 host!"))
        assertEquals("[::1]:4096", filterServerAddressHostPort("[::1]:4096"))
        assertEquals("macbook.local:4096", filterServerAddressHostPort("macbook.local:4096"))
        assertEquals("my-host_01.lan", filterServerAddressHostPort("my-host_01.lan!"))
    }

    @Test
    fun parse_keepsHostname() {
        val parts = parseServerAddressValue("https://macbook.local:8080/")
        assertEquals(ServerAddressScheme.Https, parts.scheme)
        assertEquals("macbook.local:8080", parts.hostPort)
        assertEquals("https://macbook.local:8080/", parts.toBaseUrl())
    }

    @Test
    fun hostEdit_pasteFullUrlUpdatesScheme() {
        val parts = applyServerAddressHostEdit("https://10.0.0.5:443/", ServerAddressScheme.Http)
        assertEquals(ServerAddressScheme.Https, parts.scheme)
        assertEquals("10.0.0.5:443", parts.hostPort)
        assertEquals("https://10.0.0.5:443/", parts.toBaseUrl())
    }

    @Test
    fun hostEdit_withoutSchemeKeepsCurrentScheme() {
        val parts = applyServerAddressHostEdit("127.0.0.1:4096", ServerAddressScheme.Https)
        assertEquals(ServerAddressScheme.Https, parts.scheme)
        assertEquals("127.0.0.1:4096", parts.hostPort)
    }

    @Test
    fun emptyHost_emitsSchemePrefixOnly() {
        assertEquals("http://", ServerAddressParts(ServerAddressScheme.Http, "").toBaseUrl())
    }
}
