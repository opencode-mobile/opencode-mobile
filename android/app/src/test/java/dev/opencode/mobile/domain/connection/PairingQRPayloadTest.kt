package dev.opencode.mobile.domain.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingQRPayloadTest {
    @Test
    fun parse_selectsFirstNonLoopbackUrl() {
        val raw =
            """{"urls":["http://127.0.0.1:4096","http://192.168.0.160:4096"],"username":"opencode","password":"secret"}"""
        val result = PairingQRPayload.parse(raw)
        assertTrue(result is PairingQRParseResult.Ready)
        val credentials = (result as PairingQRParseResult.Ready).credentials
        assertEquals("http://192.168.0.160:4096/", credentials.baseUrl)
        assertEquals("opencode", credentials.username)
        assertEquals("secret", credentials.password)
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
    fun parse_missingUsername_defaultsToOpencode() {
        val raw = """{"urls":["http://10.0.0.9:4096"],"password":"pw"}"""
        val result = PairingQRPayload.parse(raw)
        val credentials = (result as PairingQRParseResult.Ready).credentials
        assertEquals("opencode", credentials.username)
        assertEquals("pw", credentials.password)
    }
}
