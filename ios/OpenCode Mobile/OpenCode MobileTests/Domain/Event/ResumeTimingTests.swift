import Foundation
import Testing
@testable import OpenCode_Mobile

struct ResumeTimingTests {
    @Test func logsResumeLineFromForegroundOrigin() {
        ResumeTiming.shared.begin(nowMs: 1_000)
        ResumeTiming.shared.markCachePainted(nowMs: 1_040)
        ResumeTiming.shared.markConnected(nowMs: 1_200)
        ResumeTiming.shared.markBootstrapDone(nowMs: 1_350)
        let messages = DiagnosticLogBuffer.shared.snapshot().entries.map(\.message)
        #expect(messages.contains { $0 == "resume: cache painted +40ms, connected +200ms, bootstrap done +350ms" })
    }
}

struct MessageFrameIdentityTests {
    @Test func tailIdentityMatchesOnIdTimestampAndPartCount() {
        let left = [
            Message(id: "m1", role: .user, text: "A", parts: [.text(TextPart(text: "A", isStreaming: false))], model: nil, mode: nil, sentAtEpochMs: 1, completedAtEpochMs: 2),
        ]
        let right = [
            Message(id: "m1", role: .user, text: "Different", parts: [.text(TextPart(text: "Different", isStreaming: false))], model: nil, mode: nil, sentAtEpochMs: 1, completedAtEpochMs: 2),
        ]
        #expect(MessageTailIdentity.of(left) == MessageTailIdentity.of(right))
        let extraPart = [
            Message(
                id: "m1",
                role: .user,
                text: "A",
                parts: [
                    .text(TextPart(text: "A", isStreaming: false)),
                    .text(TextPart(text: "B", isStreaming: false)),
                ],
                model: nil,
                mode: nil,
                sentAtEpochMs: 1,
                completedAtEpochMs: 2
            ),
        ]
        #expect(MessageTailIdentity.of(left) != MessageTailIdentity.of(extraPart))
    }

    @Test func frameIdentityChangesWithEngineRevision() {
        let messages = [
            Message(id: "m1", role: .user, text: "A", model: nil, mode: nil, sentAtEpochMs: 1),
        ]
        let first = MessageFrameIdentity.of(messages: messages, engineRevision: 1)
        let second = MessageFrameIdentity.of(messages: messages, engineRevision: 2)
        #expect(first != second)
        #expect(first == MessageFrameIdentity.of(messages: messages, engineRevision: 1))
        #expect(first.sameVisibleFrame(as: second))
    }
}
