package dev.opencode.mobile.domain.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingQRPayloadTest {
    @Test
    fun parse_selectsFirstNonLoopbackUrl() {
        val raw =
            """{"urls":["http://127.0.0.1:4096","http://192.168.1.60:4096"],"username":"opencode","password":"secret"}"""
        val result = PairingQRPayload.parse(raw)
        assertTrue(result is PairingQRParseResult.Ready)
        val credentials = (result as PairingQRParseResult.Ready).credentials
        assertEquals("http://192.168.1.60:4096/", credentials.baseUrl)
        assertEquals(listOf("http://192.168.1.60:4096/"), credentials.urls)
        assertEquals("opencode", credentials.username)
        assertEquals("secret", credentials.password)
        assertNull(credentials.code)
    }

    @Test
    fun parse_keepsLanAndPrefersTailscaleForDisplay() {
        val raw =
            """{"urls":["http://192.168.1.60:4096","http://100.64.0.15:4096"],"username":"opencode","password":"secret"}"""
        val result = PairingQRPayload.parse(raw)
        val credentials = (result as PairingQRParseResult.Ready).credentials
        assertEquals("http://100.64.0.15:4096/", credentials.baseUrl)
        assertEquals(
            listOf("http://192.168.1.60:4096/", "http://100.64.0.15:4096/"),
            credentials.urls,
        )
    }

    @Test
    fun parse_prefersMagicDnsTailscaleHost() {
        val raw =
            """{"urls":["http://192.168.1.60:4096","http://mybox.tailxyz.ts.net:4096"],"username":"opencode","password":"secret"}"""
        val result = PairingQRPayload.parse(raw)
        val credentials = (result as PairingQRParseResult.Ready).credentials
        assertEquals("http://mybox.tailxyz.ts.net:4096/", credentials.baseUrl)
    }

    @Test
    fun pickReachableUrl_prefersTailscaleWhenItAnswered() {
        val picked =
            PairingQRPayload.pickReachableUrl(
                listOf(
                    "http://192.168.1.60:4096/",
                    "http://100.64.0.15:4096/",
                ),
            )
        assertEquals("http://100.64.0.15:4096/", picked)
    }

    @Test
    fun isRemoteTunnelUrl_matchesOpentunnelHostIgnoringCase() {
        assertTrue(PairingQRPayload.isRemoteTunnelUrl("https://Box.OpenTunnel.XYZ/"))
        assertTrue(PairingQRPayload.isRemoteTunnelUrl("http://box.opentunnel.xyz:443"))
        assertFalse(PairingQRPayload.isRemoteTunnelUrl("https://opentunnel.xyz/"))
        assertFalse(PairingQRPayload.isRemoteTunnelUrl("http://192.168.1.60:4096/"))
        assertFalse(PairingQRPayload.isRemoteTunnelUrl("http://mybox.tailxyz.ts.net:4096/"))
        assertFalse(PairingQRPayload.isRemoteTunnelUrl("http://100.64.0.15:4096/"))
    }

    @Test
    fun pickReachableUrl_prefersTailscaleOverRemoteTunnel() {
        val picked =
            PairingQRPayload.pickReachableUrl(
                listOf(
                    "https://box.opentunnel.xyz/",
                    "http://100.64.0.15:4096/",
                ),
            )
        assertEquals("http://100.64.0.15:4096/", picked)
    }

    @Test
    fun pickReachableUrl_neverReplacesHttpsWithHttp() {
        val picked =
            PairingQRPayload.pickReachableUrl(
                listOf("http://192.168.1.60:4096/", "http://100.64.0.15:4096/"),
                currentBaseUrl = "https://box.opentunnel.xyz/",
            )
        assertNull(picked)
    }

    @Test
    fun pickReachableUrl_httpsCurrentStillPicksAnsweringHttps() {
        val picked =
            PairingQRPayload.pickReachableUrl(
                listOf("https://box.opentunnel.xyz/", "http://100.64.0.15:4096/"),
                currentBaseUrl = "https://box.opentunnel.xyz/",
            )
        assertEquals("https://box.opentunnel.xyz/", picked)
    }

    @Test
    fun pickReachableUrl_httpCurrentMayMoveToHttps() {
        val picked =
            PairingQRPayload.pickReachableUrl(
                listOf("https://box.opentunnel.xyz/"),
                currentBaseUrl = "http://192.168.1.60:4096/",
            )
        assertEquals("https://box.opentunnel.xyz/", picked)
    }

    @Test
    fun pickReachableUrl_usesAnotherAddressWhenTailscaleDidNotAnswer() {
        val picked =
            PairingQRPayload.pickReachableUrl(
                listOf("http://192.168.1.60:4096/"),
            )
        assertEquals("http://192.168.1.60:4096/", picked)
    }

    @Test
    fun parse_stripsPathFromPairingUrl() {
        val raw =
            """{"urls":["http://192.168.1.60:4096/session"],"username":"opencode","password":"secret"}"""
        val result = PairingQRPayload.parse(raw)
        val credentials = (result as PairingQRParseResult.Ready).credentials
        assertEquals("http://192.168.1.60:4096/", credentials.baseUrl)
    }

    @Test
    fun parse_onlyLoopback_isUnreachable() {
        val raw =
            """{"urls":["http://localhost:4096","http://127.0.0.1:4096"],"username":"opencode","password":"x"}"""
        val result = PairingQRPayload.parse(raw)
        assertEquals(PairingQRParseResult.Failed(PairingQRError.NoReachableUrl), result)
    }

    @Test
    fun parse_garbage_isInvalid() {
        val result = PairingQRPayload.parse("not-json")
        assertEquals(PairingQRParseResult.Failed(PairingQRError.InvalidPayload), result)
    }

    @Test
    fun parse_connectLink_extractsOriginAndCode() {
        val result = PairingQRPayload.parse(" http://192.168.1.2:49374/auth/connect/abc_DEF-123 ")
        assertTrue(result is PairingQRParseResult.Ready)
        val credentials = (result as PairingQRParseResult.Ready).credentials
        assertEquals("http://192.168.1.2:49374/", credentials.baseUrl)
        assertEquals(listOf("http://192.168.1.2:49374/"), credentials.urls)
        assertEquals("abc_DEF-123", credentials.code)
        assertEquals("opencode", credentials.username)
        assertEquals("", credentials.password)
    }

    @Test
    fun parse_codeJson_prefersTailscaleAndKeepsBothUrls() {
        val raw =
            """{"code":"abc_DEF-123","urls":["http://192.168.1.2:49374","http://100.64.0.3:49374"]}"""
        val result = PairingQRPayload.parse(raw)
        val credentials = (result as PairingQRParseResult.Ready).credentials
        assertEquals("http://100.64.0.3:49374/", credentials.baseUrl)
        assertEquals(
            listOf("http://192.168.1.2:49374/", "http://100.64.0.3:49374/"),
            credentials.urls,
        )
        assertEquals("abc_DEF-123", credentials.code)
        assertEquals("opencode", credentials.username)
        assertEquals("", credentials.password)
    }

    @Test
    fun parse_codeJson_ignoresEmbeddedPassword() {
        val raw =
            """{"code":"abc_DEF-123","urls":["http://10.0.0.1:4096/"],"username":"other","password":"secret"}"""
        val credentials = (PairingQRPayload.parse(raw) as PairingQRParseResult.Ready).credentials
        assertEquals("opencode", credentials.username)
        assertEquals("", credentials.password)
        assertEquals("abc_DEF-123", credentials.code)
    }

    @Test
    fun parse_codeJson_onlyLoopback_isUnreachable() {
        val raw =
            """{"code":"abc","urls":["http://127.0.0.1:4096","http://localhost:4096"]}"""
        val result = PairingQRPayload.parse(raw)
        assertEquals(PairingQRParseResult.Failed(PairingQRError.NoReachableUrl), result)
    }

    @Test
    fun parse_newFormat_rejectsInvalidPayloads() {
        val samples =
            listOf(
                "http://192.168.1.2:49374/auth/connect/",
                "http://192.168.1.2:49374/auth/connect/abc/extra",
                "http://192.168.1.2:49374/connect#abc",
                "opencode-ios://auth/connect/abc",
                "192.168.1.2:49374",
                "http://user:pass@192.168.1.2:49374/auth/connect/abc_DEF-123",
                "http://192.168.1.2:49374/auth/connect/abc_DEF-123?x=1",
                "http://192.168.1.2:49374/auth/connect/abc_DEF-123#frag",
                """{"code":"abc","urls":[]}""",
                """{"code":"a/b","urls":["http://192.168.1.2:49374"]}""",
                """{"code":"abc","urls":["opencode-ios://host"]}""",
                """{"code":"abc","urls":["ftp://192.168.1.2:49374"]}""",
                "{not json",
            )
        for (sample in samples) {
            assertEquals(
                sample,
                PairingQRParseResult.Failed(PairingQRError.InvalidPayload),
                PairingQRPayload.parse(sample),
            )
        }
    }

    @Test
    fun parse_missingUsername_defaultsToOpencode() {
        val raw = """{"urls":["http://10.0.0.9:4096"],"password":"pw"}"""
        val result = PairingQRPayload.parse(raw)
        val credentials = (result as PairingQRParseResult.Ready).credentials
        assertEquals("opencode", credentials.username)
        assertEquals("pw", credentials.password)
    }
}
