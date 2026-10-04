package dev.opencode.mobile.domain.t3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class T3ErrorsTest {
    @Test
    fun from_mapsUnauthorizedAndForbiddenToAccessRevoked() {
        val unauthorized = T3HttpException.from(401, """{"error":"invalid_token"}""")
        val forbidden = T3HttpException.from(403, "Forbidden")
        assertTrue(unauthorized is T3AccessRevokedException)
        assertTrue(forbidden is T3AccessRevokedException)
        assertEquals(T3AccessRevokedException.USER_MESSAGE, unauthorized.message)
        assertEquals("""{"error":"invalid_token"}""", unauthorized.body)
    }

    @Test
    fun from_keepsOtherStatusesAsPlainHttpErrorsWithoutServerBody() {
        val error = T3HttpException.from(500, "<html>stack trace</html>")
        assertFalse(error is T3AccessRevokedException)
        assertEquals("T3 request failed (500)", error.message)
        assertEquals("<html>stack trace</html>", error.body)
    }
}
