package dev.opencode.mobile.ui.sessionchat.region

import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.text.resolve
import dev.opencode.mobile.ui.text.EnglishStrings
import org.junit.Assert.assertEquals
import org.junit.Test

class InteractionReplyErrorTest {
    @Test
    fun transportFailure_isAShortRetryHint() {
        val error = SessionError.Transport(IllegalStateException("Bad response: HttpResponse[http://10.0.2.2:4700/…]"))
        assertEquals("Couldn't send your reply. Try again.", InteractionReplyError.PermissionReply.message(error).resolve(EnglishStrings))
        assertEquals("Couldn't dismiss the question. Try again.", InteractionReplyError.QuestionDismiss.message(error).resolve(EnglishStrings))
    }

    @Test
    fun meaningfulServerMessage_isAppended() {
        val error =
            SessionError.Http(
                statusCode = 400,
                message = "Bad response",
                body = """{"_tag":"FormInvalidAnswerError","message":"Too many selections for form field: environment"}""",
            )
        assertEquals(
            "Couldn't send your answer. Too many selections for form field: environment.",
            InteractionReplyError.QuestionReply.message(error).resolve(EnglishStrings),
        )
    }

    @Test
    fun schemaFailure_hidesTheRawBody() {
        val error =
            SessionError.Http(
                statusCode = 400,
                message = "Bad response",
                body = """{"_tag":"InvalidRequestError","message":"Missing key\n at [\"decision\"]","kind":"Payload"}""",
            )
        assertEquals("Couldn't send your reply. Try again.", InteractionReplyError.PermissionReply.message(error).resolve(EnglishStrings))
    }
}
