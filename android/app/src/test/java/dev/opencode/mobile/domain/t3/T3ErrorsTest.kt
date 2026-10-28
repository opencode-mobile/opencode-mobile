package dev.opencode.mobile.domain.t3

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

    @Test
    fun rejectedRpc_keepsServerCauseForDiagnosticsOnly() {
        val error = T3RpcException.rejected("ThreadNotFound: thr-1")
        assertEquals(T3RpcException.REJECTED_MESSAGE, error.message)
        assertEquals("ThreadNotFound: thr-1", error.detail)
        assertEquals("ThreadNotFound: thr-1", T3Errors.detail(error))
    }

    @Test
    fun diagnosticMessage_appendsExitFailureCauseButUserMessageStaysGeneric() {
        val cause =
            buildJsonObject {
                put("_tag", JsonPrimitive("Fail"))
                put("error", JsonPrimitive("SchemaError: Missing key createdBy"))
            }
        val error = T3RpcException.rejected(cause)
        val logged = T3Errors.diagnosticMessage("sendPromptAsync failed", error)
        assertTrue(logged.startsWith("sendPromptAsync failed detail="))
        assertTrue(logged.contains("SchemaError: Missing key createdBy"))
        assertEquals(T3RpcException.REJECTED_MESSAGE, T3Errors.userMessage(error))
        assertEquals("plain", T3Errors.diagnosticMessage("plain", IllegalStateException("x")))
    }

    @Test
    fun connectionLost_hasShortUserMessage() {
        val error = T3ConnectionLostException("java.io.EOFException")
        assertEquals("Lost connection to T3", error.message)
        assertEquals("java.io.EOFException", error.detail)
        assertTrue(error is T3RpcException)
    }

    @Test
    fun normalize_wrapsLibraryErrorsAndKeepsT3Errors() {
        val io = T3Errors.normalize(java.io.IOException("failed to connect to /10.0.0.1 (port 8787)"))
        assertTrue(io is T3TransportException)
        assertEquals(T3TransportException.USER_MESSAGE, io.message)
        assertEquals("failed to connect to /10.0.0.1 (port 8787)", T3Errors.detail(io))

        val decoding = T3Errors.normalize(IllegalStateException("Expected JsonObject, got JsonLiteral"))
        assertTrue(decoding is T3DecodingException)
        assertEquals(T3DecodingException.USER_MESSAGE, decoding.message)

        val revoked = T3AccessRevokedException(401, "{\"error\":\"invalid_token\"}")
        assertTrue(T3Errors.normalize(revoked) === revoked)
        assertTrue(T3Errors.userMessage(revoked).contains("Re-pair this environment"))
        val protocol = T3ProtocolException("This T3 environment uses orchestration protocol 1; OpenCode Mobile requires 2")
        assertTrue(T3Errors.normalize(protocol) === protocol)
        assertEquals(protocol.message, T3Errors.userMessage(protocol))
        assertEquals("Lost connection to T3", T3Errors.userMessage(T3ConnectionLostException()))
        assertEquals("T3 rejected the request", T3Errors.userMessage(T3RpcException.rejected("boom")))
    }
}
