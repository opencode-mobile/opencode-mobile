package dev.opencode.mobile.domain.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CleartextPolicyTest {
    @Test
    fun trustedCleartext_connectsSilently() {
        listOf(
            "http://127.0.0.1:4096/",
            "http://localhost:4096",
            "http://[::1]:4096/",
            "http://192.168.1.60:4096/",
            "http://10.0.2.2:4096/",
            "http://172.16.4.9:4096/",
            "http://172.31.255.1:4096/",
            "http://169.254.10.1:4096/",
            "http://100.64.0.3:49374/",
            "http://100.127.255.254:4096/",
            "http://[fd7a:115c:a1e0::a033:5957]:4096/",
            "http://[fe80::1%25en0]:4096/",
            "http://mybox.tail1234.ts.net:4096/",
            "http://mybox.local:4096/",
            "http://mybox:4096/",
            "http://mybox.lan:4096/",
            "http://nas.home.arpa:4096/",
        ).forEach { url ->
            assertEquals(url, CleartextPolicy.Verdict.TrustedCleartext, CleartextPolicy.verdict(url))
        }
    }

    @Test
    fun untrustedCleartext_needsAWarning() {
        listOf(
            "http://203.0.113.5:4096/",
            "http://8.8.8.8/",
            "http://172.32.0.1:4096/",
            "http://100.128.0.1:4096/",
            "http://devbox.example.com:4096/",
            "http://box.opentunnel.xyz/",
            "http://[2001:db8::1]:4096/",
        ).forEach { url ->
            assertEquals(url, CleartextPolicy.Verdict.UntrustedCleartext, CleartextPolicy.verdict(url))
        }
    }

    @Test
    fun encrypted_isAlwaysFine() {
        listOf("https://box.opentunnel.xyz/", "https://203.0.113.5:4096/", "wss://devbox.example.com/")
            .forEach { url -> assertEquals(url, CleartextPolicy.Verdict.Encrypted, CleartextPolicy.verdict(url)) }
    }

    @Test
    fun untrustedCleartextHosts_listsOnlyTheRiskyOnesWithPorts() {
        val hosts =
            CleartextPolicy.untrustedCleartextHosts(
                listOf(
                    "http://192.168.1.60:4096/",
                    "http://203.0.113.5:4096/",
                    "https://box.opentunnel.xyz/",
                    "http://devbox.example.com/",
                    "http://203.0.113.5:4096",
                ),
            )
        assertEquals(listOf("203.0.113.5:4096", "devbox.example.com"), hosts)
    }

    @Test
    fun warningMessage_namesTheHosts() {
        assertTrue(CleartextPolicy.warningMessage(listOf("203.0.113.5:4096")).startsWith("203.0.113.5:4096 uses plain http"))
        assertTrue(CleartextPolicy.warningMessage(listOf("a", "b")).startsWith("a, b use plain http"))
    }
}
