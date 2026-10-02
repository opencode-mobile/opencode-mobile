import Foundation
import Testing
@testable import OpenCode_Mobile

struct ServerErrorMessageTests {
    @Test func formAnswerError_keepsTheServerMessage() {
        let body = #"{"_tag":"FormInvalidAnswerError","formID":"frm_1","message":"Too many selections for form field: environment"}"#
        #expect(ServerErrorMessage.fromBody(body) == "Too many selections for form field: environment")
    }

    @Test func requestSchemaError_isNotShown() {
        let body = #"{"_tag":"InvalidRequestError","message":"Missing key\n at [\"decision\"]","kind":"Payload"}"#
        #expect(ServerErrorMessage.fromBody(body) == nil)
    }

    @Test func v1NamedErrorData_message() {
        #expect(ServerErrorMessage.fromBody(#"{"name":"BusyError","data":{"message":"Session is busy."}}"#) == "Session is busy")
    }

    @Test func nonJsonOrEmptyBodies_areIgnored() {
        #expect(ServerErrorMessage.fromBody("HTTP 400: Bad Request") == nil)
        #expect(ServerErrorMessage.fromBody("") == nil)
        #expect(ServerErrorMessage.fromBody(nil) == nil)
        #expect(ServerErrorMessage.fromBody(#"{"message":""}"#) == nil)
    }

    @Test func interactionReplyError_copy() {
        let form = SessionError.http(
            statusCode: 400,
            message: #"{"_tag":"FormInvalidAnswerError","message":"Too many selections for form field: environment"}"#
        )
        #expect(
            InteractionReplyError.questionReply.message(for: form)
                == "Couldn't send your answer. Too many selections for form field: environment."
        )
        let schema = SessionError.http(
            statusCode: 400,
            message: #"{"_tag":"InvalidRequestError","message":"Missing key\n at [\"decision\"]","kind":"Payload"}"#
        )
        #expect(InteractionReplyError.permissionReply.message(for: schema) == "Couldn't send your reply. Try again.")
        #expect(
            InteractionReplyError.questionDismiss.message(for: SessionError.transport("offline"))
                == "Couldn't dismiss the question. Try again."
        )
    }
}
