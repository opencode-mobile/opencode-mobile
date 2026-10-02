import Foundation
import Testing
@testable import OpenCode_Mobile

struct ToolInterruptionTests {
    @Test func v1AbortAndRejectText_isInterruption() {
        #expect(ToolInterruption.isUserInterruption("Tool execution aborted"))
        #expect(ToolInterruption.isUserInterruption("The user rejected permission to use this specific tool call."))
        #expect(ToolInterruption.isUserInterruption("The user dismissed this question"))
        #expect(!ToolInterruption.isUserInterruption("ENOENT: no such file"))
        #expect(!ToolInterruption.isUserInterruption(nil))
    }

    @Test func v1ToolState_marksAbortedErrorsInterrupted() {
        let adapter = MessageDomainAdapter()
        let aborted = adapter.toolPart(
            toolName: "bash",
            state: MessageToolStateDTO(status: "error", input: nil, raw: nil, title: nil, output: nil,
                                       error: "Tool execution aborted", time: nil, metadata: nil)
        )
        #expect(aborted.isInterrupted)
        let failed = adapter.toolPart(
            toolName: "bash",
            state: MessageToolStateDTO(status: "error", input: nil, raw: nil, title: nil, output: nil,
                                       error: "exit 1", time: nil, metadata: nil)
        )
        #expect(!failed.isInterrupted)
    }

    @Test func sessionAbortErrors() {
        #expect(ToolInterruption.isUserAbortError(type: "aborted", name: nil))
        #expect(ToolInterruption.isUserAbortError(type: nil, name: "MessageAbortedError"))
        #expect(!ToolInterruption.isUserAbortError(type: "provider", name: "APIError"))
    }

    @Test func interruptedCopy() {
        #expect(SessionChatTimelineBlocks.interruptedToolText("The user declined this tool call") == "Declined")
        #expect(SessionChatTimelineBlocks.interruptedToolText("Tool execution interrupted") == "Stopped")
    }

    @Test func v1Info_stopReason() throws {
        let adapter = MessageDomainAdapter()
        func info(_ json: String) throws -> MessageInfoDTO {
            try JSONDecoder().decode(MessageInfoDTO.self, from: Data(json.utf8))
        }
        let truncated = adapter.message(info: try info(#"{"id":"a1","role":"assistant","finish":"length"}"#), domainParts: [])
        #expect(truncated.stopReason == .length)
        let aborted = adapter.message(
            info: try info(#"{"id":"a2","role":"assistant","error":{"name":"MessageAbortedError","data":{"message":"Aborted"}}}"#),
            domainParts: []
        )
        #expect(aborted.stopReason == .interrupted)
        #expect(aborted.text == "")
        let done = adapter.message(info: try info(#"{"id":"a3","role":"assistant","finish":"stop"}"#), domainParts: [])
        #expect(done.stopReason == nil)
    }
}
