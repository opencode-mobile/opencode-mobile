package dev.opencode.mobile.domain.session

import dev.opencode.mobile.domain.text.resolve
import dev.opencode.mobile.ui.text.EnglishStrings
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
    fun knownTags_areTypedAndKeyed() {
        val pairing = ServerErrorMessage.reason("""{"_tag":"UnauthorizedError","message":"nope"}""")
        assertEquals(ServerErrorReason.PairingExpired, pairing)
        assertEquals("Pairing expired, pair again", pairing?.text?.resolve(EnglishStrings))
        assertNull(pairing?.serverText)
        val notFound = ServerErrorMessage.reason("""{"_tag":"LocationNotFoundError","location":"/repo"}""")
        assertEquals(ServerErrorReason.NotFound("/repo"), notFound)
        assertEquals("Folder or file not found · /repo", notFound?.text?.resolve(EnglishStrings))
        assertEquals(
            "Folder or file not found",
            ServerErrorMessage.reason("""{"_tag":"FileNotFoundError"}""")?.text?.resolve(EnglishStrings),
        )
        assertEquals(ServerErrorReason.SessionBusy, ServerErrorMessage.reason("""{"_tag":"SessionBusyError","sessionID":"ses-1"}"""))
        val unknown = ServerErrorMessage.reason("""{"_tag":"UnknownError","message":"Something went wrong","ref":"ref-9"}""")
        assertEquals(ServerErrorReason.Server("Something went wrong", "ref-9"), unknown)
        assertEquals("Something went wrong (ref-9)", unknown?.text?.resolve(EnglishStrings))
        assertEquals("Something went wrong (ref-9)", unknown?.serverText)
        assertNull(ServerErrorMessage.reason("""{"_tag":"InvalidRequestError","message":"Missing key"}"""))
    }

    @Test
    fun fromError_readsTheHttpBody() {
        val error =
            SessionError.Http(
                statusCode = 400,
                message = "Bad response: HttpResponse[http://host/api/…, 400 Bad Request]",
                body = """{"_tag":"FormInvalidAnswerError","message":"Too many selections for form field: environment"}""",
            )
        assertEquals(ServerErrorReason.Server("Too many selections for form field: environment"), ServerErrorMessage.from(error))
        assertNull(ServerErrorMessage.from(IllegalStateException("boom")))
    }
}
