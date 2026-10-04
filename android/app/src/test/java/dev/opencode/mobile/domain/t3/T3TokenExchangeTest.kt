package dev.opencode.mobile.domain.t3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class T3TokenExchangeTest {
    @Test
    fun encodeBody_containsOAuthFormFields() {
        val body = T3TokenExchange.encodeBody("pair-token")
        val fields =
            body.split("&").associate { part ->
                val (key, value) = part.split("=", limit = 2)
                decode(key) to decode(value)
            }
        assertEquals(T3TokenExchange.GRANT_TYPE, fields["grant_type"])
        assertEquals("pair-token", fields["subject_token"])
        assertEquals(T3TokenExchange.SUBJECT_TOKEN_TYPE, fields["subject_token_type"])
        assertEquals(T3TokenExchange.REQUESTED_TOKEN_TYPE, fields["requested_token_type"])
        assertEquals(T3TokenExchange.CLIENT_LABEL, fields["client_label"])
        assertEquals(T3TokenExchange.CLIENT_DEVICE_TYPE, fields["client_device_type"])
        assertTrue(fields.size >= 4)
    }

    private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
}
