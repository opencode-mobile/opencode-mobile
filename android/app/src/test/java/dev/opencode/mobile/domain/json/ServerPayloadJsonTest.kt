package dev.opencode.mobile.domain.json

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPayloadJsonTest {
    @Test
    fun decodeFailure_omitsThePayload() {
        val secret = "prompt-secret-token-9f3a"
        val body = """{"name":"$secret"}"""
        val hidden = runCatching { serverPayloadJson().decodeFromString<CountOnly>(body) }.exceptionOrNull()
        val shown = runCatching { Json.decodeFromString<CountOnly>(body) }.exceptionOrNull()
        assertFalse(hidden!!.message.orEmpty().contains(secret))
        assertTrue(shown!!.message.orEmpty().contains(secret))
    }

    @Test
    fun unknownKeys_stillDecode() {
        assertEquals(2, serverPayloadJson().decodeFromString<CountOnly>("""{"count":2,"extra":true}""").count)
    }

    @Serializable
    private data class CountOnly(
        val count: Int,
    )
}
