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

    // MARK: Deny with a note (#946)

    @Test func v2PermissionRejected_isAUserInterruption() {
        #expect(ToolInterruption.isUserInterruptionType("permission.rejected"))
        #expect(ToolInterruption.isUserInterruptionType("aborted"))
        #expect(!ToolInterruption.isUserInterruptionType("provider"))
        #expect(!ToolInterruption.isUserInterruptionType(nil))
    }

    @Test func v2Decline_readsTheNoteFromTheMessage() {
        #expect(ToolInterruption.v2Decline(type: "permission.rejected", message: "  Use pnpm\n") == ToolDecline(note: "Use pnpm"))
        #expect(ToolInterruption.v2Decline(type: "permission.rejected", message: "  ") == ToolDecline(note: nil))
        #expect(ToolInterruption.v2Decline(type: "permission.rejected", message: nil) == ToolDecline(note: nil))
        #expect(ToolInterruption.v2Decline(type: "aborted", message: "Tool execution interrupted") == nil)
        #expect(ToolInterruption.v2Decline(type: nil, message: "exit 1") == nil)
    }

    @Test func v1Decline_readsTheNoteAfterTheFeedbackMarker() {
        let prefix = "The user rejected permission to use this specific tool call"
        #expect(ToolInterruption.v1Decline(error: "\(prefix) with the following feedback: Use pnpm instead ")
            == ToolDecline(note: "Use pnpm instead"))
        #expect(ToolInterruption.v1Decline(error: "\(prefix) with the following feedback:   ") == ToolDecline(note: nil))
        #expect(ToolInterruption.v1Decline(error: "\(prefix). You may try again with different parameters.")
            == ToolDecline(note: nil))
        #expect(ToolInterruption.v1Decline(error: "Tool execution aborted") == nil)
        #expect(ToolInterruption.v1Decline(error: "ENOENT: no such file") == nil)
        #expect(ToolInterruption.v1Decline(error: nil) == nil)
    }

    @Test func v1ToolState_declinedWithNote_isInterruptedAndKeepsTheNote() {
        let adapter = MessageDomainAdapter()
        let declined = adapter.toolPart(
            toolName: "bash",
            state: MessageToolStateDTO(
                status: "error", input: nil, raw: nil, title: nil, output: nil,
                error: "The user rejected permission to use this specific tool call with the following feedback: Use pnpm",
                time: nil, metadata: nil
            )
        )
        #expect(declined.isInterrupted)
        #expect(declined.decline == ToolDecline(note: "Use pnpm"))

        let plain = adapter.toolPart(
            toolName: "bash",
            state: MessageToolStateDTO(
                status: "error", input: nil, raw: nil, title: nil, output: nil,
                error: "The user rejected permission to use this specific tool call.",
                time: nil, metadata: nil
            )
        )
        #expect(plain.isInterrupted)
        #expect(plain.decline == ToolDecline(note: nil))

        let stopped = adapter.toolPart(
            toolName: "bash",
            state: MessageToolStateDTO(status: "error", input: nil, raw: nil, title: nil, output: nil,
                                       error: "Tool execution aborted", time: nil, metadata: nil)
        )
        #expect(stopped.decline == nil)
    }

    @Test func declinedCard_readsDeclined_withTheNoteAsASecondLine() {
        #expect(SessionChatTimelineBlocks.interruptedToolText("Use pnpm", declined: true).resolved == "Declined")
        #expect(SessionChatTimelineBlocks.declineNoteText(ToolDecline(note: "Use pnpm"))?.resolved == "Your note: Use pnpm")
        #expect(SessionChatTimelineBlocks.declineNoteText(ToolDecline(note: nil)) == nil)
        #expect(SessionChatTimelineBlocks.declineNoteText(nil) == nil)
    }

    @Test func sessionAbortErrors() {
        #expect(ToolInterruption.isUserAbortError(type: "aborted", name: nil))
        #expect(ToolInterruption.isUserAbortError(type: nil, name: "MessageAbortedError"))
        #expect(!ToolInterruption.isUserAbortError(type: "provider", name: "APIError"))
    }

    @Test func interruptedCopy() {
        #expect(SessionChatTimelineBlocks.interruptedToolText("The user declined this tool call").resolved == "Declined")
        #expect(SessionChatTimelineBlocks.interruptedToolText("Tool execution interrupted").resolved == "Stopped")
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
