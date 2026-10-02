package dev.opencode.mobile.domain.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerErrorMessageTest {
    @Test
    fun formAnswerError_keepsTheServerMessage() {
        val body =
            """{"_tag":"FormInvalidAnswerError","formID":"frm_1",""" +
                """"message":"Too many selections for form field: environment"}"""
        assertEquals("Too many selections for form field: environment", ServerErrorMessage.fromBody(body))
    }

    @Test
    fun requestSchemaError_isNotShown() {
        val body = """{"_tag":"InvalidRequestError","message":"Missing key\n at [\"decision\"]","kind":"Payload"}"""
        assertNull(ServerErrorMessage.fromBody(body))
    }

    @Test
    fun v1NamedErrorData_message() {
        assertEquals(
            "Session is busy",
            ServerErrorMessage.fromBody("""{"name":"BusyError","data":{"message":"Session is busy."}}"""),
        )
    }

    @Test
    fun nonJsonOrEmptyBodies_areIgnored() {
        assertNull(ServerErrorMessage.fromBody("HTTP 400: Bad Request"))
        assertNull(ServerErrorMessage.fromBody(""))
        assertNull(ServerErrorMessage.fromBody(null))
        assertNull(ServerErrorMessage.fromBody("""{"message":""}"""))
    }

    @Test
    fun fromError_readsTheHttpBody() {
        val error =
            SessionError.Http(
                statusCode = 400,
                message = "Bad response: HttpResponse[http://host/api/…, 400 Bad Request]",
                body = """{"_tag":"FormInvalidAnswerError","message":"Too many selections for form field: environment"}""",
            )
        assertEquals("Too many selections for form field: environment", ServerErrorMessage.from(error))
        assertNull(ServerErrorMessage.from(IllegalStateException("boom")))
    }
}
