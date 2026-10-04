import Foundation
import Testing
@testable import OpenCode_Mobile

struct T3DispatchFailureTests {
    @Test func messageDispatchCommand_carriesRequiredCreationFields() {
        let command = T3RpcCodec.messageDispatchCommand(
            commandID: "cmd-1",
            threadID: "thread-1",
            messageID: "msg-1",
            text: "Hi",
            selection: T3ModelSelection(instanceID: "inst-1", model: "gpt-5"),
            isRunning: true
        )
        #expect(command["createdBy"] as? String == "user")
        #expect(command["creationSource"] as? String == "mobile")
    }

    @Test func exitFailure_dieDefect_keepsSchemaIssueAsCause() throws {
        let frame = try T3RpcCodec.parseFrame(Data(#"""
        {"_tag":"Exit","requestId":"req-1","exit":{"_tag":"Failure","cause":[{"_tag":"Die","defect":"Missing key\n  at [\"createdBy\"]"}]}}
        """#.utf8))
        guard case let .exitFailure(requestID, message) = frame else {
            Issue.record("expected exitFailure, got \(frame)")
            return
        }
        #expect(requestID == "req-1")
        #expect(message.contains("Missing key"))
        #expect(message.contains("createdBy"))
    }

    @Test func exitFailure_failError_keepsTagAndMessage() {
        let message = T3RpcCodec.failureMessage([
            ["_tag": "Fail", "error": ["_tag": "OrchestrationDispatchError", "message": "thread is archived"]],
        ])
        #expect(message == "OrchestrationDispatchError: thread is archived")
    }

    @Test func rejectedError_diagnosticTextContainsServerCause_snackbarDoesNot() {
        let cause = T3RpcCodec.failureMessage([["_tag": "Die", "defect": "Missing key\n  at [\"creationSource\"]"]])
        let error = T3SocketError.rejected(cause: cause)

        let diagnostic = T3ErrorText.diagnostic(error)
        #expect(diagnostic.hasPrefix(T3SocketError.rejectedMessage))
        #expect(diagnostic.contains("Missing key at [\"creationSource\"]"))

        let logged = DiagnosticLogCause.appendTo("sendPrompt failed", error: error)
        #expect(logged.contains("creationSource"))

        #expect(T3ErrorText.userMessage(error) == T3SocketError.rejectedMessage)
        #expect(!T3ErrorText.userMessage(error).contains("creationSource"))
    }
}
