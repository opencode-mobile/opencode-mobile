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

    @Test func knownTags_useFixedCopy() {
        #expect(
            ServerErrorMessage.userFacing(#"{"_tag":"UnauthorizedError","message":"nope"}"#)
                == "Pairing expired, pair again"
        )
        #expect(
            ServerErrorMessage.userFacing(#"{"_tag":"LocationNotFoundError","location":"/repo"}"#)
                == "Folder or file not found · /repo"
        )
        #expect(
            ServerErrorMessage.userFacing(#"{"_tag":"SessionBusyError","sessionID":"ses-1"}"#)
                == "Session is busy"
        )
        #expect(
            ServerErrorMessage.userFacing(
                #"{"_tag":"UnknownError","message":"Something went wrong","ref":"ref-9"}"#
            ) == "Something went wrong (ref-9)"
        )
        #expect(ServerErrorMessage.userFacing(#"{"_tag":"InvalidRequestError","message":"Missing key"}"#) == nil)
    }

    @Test func knownTags_areTyped() {
        #expect(ServerErrorMessage.reason(#"{"_tag":"UnauthorizedError"}"#) == .pairingExpired)
        #expect(ServerErrorMessage.reason(#"{"_tag":"FileNotFoundError"}"#) == .notFound(path: nil))
        #expect(ServerErrorMessage.reason(#"{"_tag":"FileNotFoundError"}"#)?.text == "Folder or file not found")
        #expect(ServerErrorMessage.reason(#"{"_tag":"BusyError"}"#) == .sessionBusy)
        #expect(
            ServerErrorMessage.reason(#"{"_tag":"UnknownError","ref":"ref-9"}"#) == .server(message: "ref-9", ref: nil)
        )
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
