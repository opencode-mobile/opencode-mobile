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
}
