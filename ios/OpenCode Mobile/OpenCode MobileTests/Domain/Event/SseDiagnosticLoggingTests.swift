import Testing
@testable import OpenCode_Mobile

struct SseDiagnosticLoggingTests {
    @Test func droppedMessage_recordsType() {
        #expect(
            SseDiagnosticLogging.droppedMessage(type: "session.usage.updated")
                == "SSE event dropped type=session.usage.updated"
        )
    }

    @Test func parseFailedMessage_recordsTypeAndLengthWithoutBody() {
        #expect(
            SseDiagnosticLogging.parseFailedMessage(#"{"type":"session.text.ended","payload":{}}"#)
                == "SSE event parse failed type=session.text.ended chars=42"
        )
        #expect(
            SseDiagnosticLogging.parseFailedMessage("not")
                == "SSE event parse failed type=- chars=3"
        )
    }

    @Test func bufferMessage_skipsMessageParts() {
        #expect(
            SseDiagnosticLogging.bufferMessage(
                for: OpencodeEvent(type: "message.part.delta", sessionID: "ses-1")
            ) == nil
        )
        #expect(
            SseDiagnosticLogging.bufferMessage(
                for: OpencodeEvent(type: "message.part.updated", sessionID: "ses-1")
            ) == nil
        )
        #expect(
            SseDiagnosticLogging.bufferMessage(
                for: OpencodeEvent(type: "message.updated", sessionID: "ses-1")
            ) == nil
        )
    }

    @Test func bufferMessage_recordsStatusMetadata() {
        let event = OpencodeEvent(
            type: "session.status",
            sessionID: "ses-1",
            rawProperties: [
                "status": .dictionary(["type": .string("busy")]),
            ]
        )
        #expect(
            SseDiagnosticLogging.bufferMessage(for: event)
                == "SSE event type=session.status session=ses-1 status=busy"
        )
    }

    @Test func bufferMessage_recordsIdleAndError() {
        #expect(
            SseDiagnosticLogging.bufferMessage(
                for: OpencodeEvent(type: "session.idle", sessionID: "ses-1")
            ) == "SSE event type=session.idle session=ses-1"
        )
        let error = OpencodeEvent(
            type: "session.error",
            sessionID: "ses-1",
            rawProperties: ["message": .string("model overloaded")]
        )
        #expect(
            SseDiagnosticLogging.bufferMessage(for: error)
                == "SSE event type=session.error session=ses-1 error=model overloaded"
        )
    }
}
