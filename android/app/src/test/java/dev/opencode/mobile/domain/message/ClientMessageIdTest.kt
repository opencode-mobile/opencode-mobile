package dev.opencode.mobile.domain.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ClientMessageIdTest {
    @Test
    fun next_matchesServerAscendingIdFormat() {
        val id = ClientMessageId.next(nowMs = 1_761_216_000_000L, random = Random(7))

        assertTrue(id, Regex("^msg_[0-9a-f]{12}[0-9A-Za-z]{14}$").matches(id))
    }

    @Test
    fun next_encodesLow48BitsOfMillisTimesFourKPlusCounter() {
        val nowMs = 1_761_216_000_123L
        val first = ClientMessageId.next(nowMs = nowMs, random = Random(1))
        val second = ClientMessageId.next(nowMs = nowMs, random = Random(1))

        // Like the server, only the low 48 bits of the value are kept.
        val mask = 0xFFFF_FFFF_FFFFL
        assertEquals((nowMs * 0x1000L + 1) and mask, first.substring(4, 16).toLong(16))
        assertEquals((nowMs * 0x1000L + 2) and mask, second.substring(4, 16).toLong(16))
        assertTrue(first < second)
    }

    @Test
    fun isClientId_onlyForMessageIds() {
        assertTrue(ClientMessageId.isClientId(ClientMessageId.next()))
        assertFalse(ClientMessageId.isClientId("pending-0E7A"))
    }
}
