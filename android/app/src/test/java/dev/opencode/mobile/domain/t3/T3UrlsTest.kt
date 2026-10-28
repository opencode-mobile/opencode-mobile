package dev.opencode.mobile.domain.t3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class T3UrlsTest {
    @Test
    fun withDefaultScheme_prefersHttpsWhenNoneGiven() {
        assertEquals("https://t3.example:3000", T3Urls.withDefaultScheme("t3.example:3000"))
        assertEquals("https://t3.example:3000", T3Urls.withDefaultScheme("  t3.example:3000  "))
        assertEquals("http://10.0.0.2:3000/", T3Urls.withDefaultScheme("http://10.0.0.2:3000/"))
        assertEquals("https://t3.example/", T3Urls.withDefaultScheme("https://t3.example/"))
        assertEquals("", T3Urls.withDefaultScheme("   "))
    }

    @Test
    fun parsePairingLink_readsDesktopLinkWithHashToken() {
        val link = T3Urls.parsePairingLink("http://192.168.0.160:3773/pair#token=pair-abc")
        assertEquals("http://192.168.0.160:3773/", link?.baseUrl)
        assertEquals("pair-abc", link?.pairingToken)
        assertEquals("", link?.label)
    }

    @Test
    fun parsePairingLink_readsDesktopLinkWithQueryToken() {
        val link = T3Urls.parsePairingLink("https://desk.tail1234.ts.net/pair?token=pair%20xyz")
        assertEquals("https://desk.tail1234.ts.net/", link?.baseUrl)
        assertEquals("pair xyz", link?.pairingToken)
    }

    @Test
    fun parsePairingLink_readsHostedLinkBackendHost() {
        val link =
            T3Urls.parsePairingLink(
                "https://app.t3.codes/pair?host=https%3A%2F%2Fdesk.tail1234.ts.net%3A3773&label=Desk#token=pair-hosted",
            )
        assertEquals("https://desk.tail1234.ts.net:3773/", link?.baseUrl)
        assertEquals("pair-hosted", link?.pairingToken)
        assertEquals("Desk", link?.label)
    }

    @Test
    fun parsePairingLink_rejectsNonPairingPayloads() {
        assertNull(T3Urls.parsePairingLink("""{"urls":["http://192.168.0.160:4096"],"username":"opencode"}"""))
        assertNull(T3Urls.parsePairingLink("http://192.168.0.160:3773/"))
        assertNull(T3Urls.parsePairingLink("http://192.168.0.160:3773/pair"))
        assertNull(T3Urls.parsePairingLink("ftp://192.168.0.160/pair#token=x"))
        assertNull(T3Urls.parsePairingLink(""))
    }
}
