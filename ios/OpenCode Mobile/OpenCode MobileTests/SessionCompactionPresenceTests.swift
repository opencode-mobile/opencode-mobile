import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionCompactionPresenceTests {
    @Test func isActive_falseWhenNotBusy() {
        let messages = [
            Message(
                id: "u1",
                role: .user,
                text: "",
                parts: [.compaction(CompactionPart(id: "c1", auto: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: 1
            )
        ]
        #expect(SessionCompactionPresence.isActive(messages: messages, isSessionBusy: false) == false)
    }

    @Test func isActive_trueWhenBusyWithCompactionPart() {
        let messages = [
            Message(
                id: "u1",
                role: .user,
                text: "",
                parts: [.compaction(CompactionPart(id: "c1", auto: true))],
                model: nil,
                mode: nil,
                sentAtEpochMs: 1
            )
        ]
        #expect(SessionCompactionPresence.isActive(messages: messages, isSessionBusy: true))
    }

    @Test func isActive_trueWhenBusyWithCompactionAssistantMode() {
        let messages = [
            Message(
                id: "u1",
                role: .user,
                text: "prior",
                model: nil,
                mode: "build",
                sentAtEpochMs: 1
            ),
            Message(
                id: "a1",
                role: .assistant,
                text: "summary",
                model: "m",
                mode: "compaction",
                sentAtEpochMs: 2
            )
        ]
        #expect(SessionCompactionPresence.isActive(messages: messages, isSessionBusy: true))
    }

    @Test func isActive_falseWhenBusyWithoutCompactionSignal() {
        let messages = [
            Message(
                id: "u1",
                role: .user,
                text: "hi",
                model: nil,
                mode: "build",
                sentAtEpochMs: 1
            ),
            Message(
                id: "a1",
                role: .assistant,
                text: "hello",
                model: "m",
                mode: "build",
                sentAtEpochMs: 2
            )
        ]
        #expect(SessionCompactionPresence.isActive(messages: messages, isSessionBusy: true) == false)
    }

    @Test func isActive_ignoresHistoricalCompactionWhenIdle() {
        let messages = [
            Message(
                id: "u1",
                role: .user,
                text: "",
                parts: [.compaction(CompactionPart(id: "c1"))],
                model: nil,
                mode: nil,
                sentAtEpochMs: 1
            ),
            Message(
                id: "a1",
                role: .assistant,
                text: "done",
                model: "m",
                mode: "compaction",
                sentAtEpochMs: 2
            ),
            Message(
                id: "u2",
                role: .user,
                text: "next",
                model: nil,
                mode: "build",
                sentAtEpochMs: 3
            )
        ]
        #expect(SessionCompactionPresence.isActive(messages: messages, isSessionBusy: false) == false)
    }

    @Test func isActive_falseWhenBusyWithCompletedCompactionSnapshot() {
        let messages = [
            Message(
                id: "u1",
                role: .user,
                text: "hello",
                model: nil,
                mode: "build",
                sentAtEpochMs: 1
            ),
            Message(
                id: "c1",
                role: .assistant,
                text: "summary",
                parts: [.compaction(CompactionPart(id: "c1", auto: false, completed: true))],
                model: "m",
                mode: "compaction",
                sentAtEpochMs: 2
            )
        ]
        #expect(SessionCompactionPresence.isActive(messages: messages, isSessionBusy: true) == false)
        #expect(SessionCompactionPresence.completedCompactionIDs(in: messages) == ["c1"])
    }
}
