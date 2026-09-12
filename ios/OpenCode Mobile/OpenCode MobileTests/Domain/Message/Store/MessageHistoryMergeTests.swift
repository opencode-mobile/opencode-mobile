import Foundation
import Testing
@testable import OpenCode_Mobile

struct MessageHistoryMergeTests {
    @Test func applyTailPreservesOlderThanOverlapAndUpsertsNewer() {
        let local = [
            message("m1", role: .user, text: "old"),
            message("m2", role: .assistant, text: "cached"),
            message("m3", role: .user, text: "stale"),
            message("m4", role: .assistant, text: "disproved"),
        ]
        let tail = [
            message("m2", role: .assistant, text: "fresh"),
            message("m3", role: .user, text: "latest"),
        ]

        #expect(MessageHistoryMerge.applyTail(local: local, tail: tail).map(\.id) == ["m1", "m2", "m3"])
        #expect(MessageHistoryMerge.applyTail(local: local, tail: tail)[1].text == "fresh")
    }

    @Test func applyTailDropsNewerLocalRowsDisprovedByTail() {
        let local = [
            message("m1", role: .user, text: "old"),
            message("m2", role: .assistant, text: "overlap"),
            message("gone", role: .assistant, text: "disproved"),
        ]
        let tail = [message("m2", role: .assistant, text: "overlap")]

        #expect(MessageHistoryMerge.applyTail(local: local, tail: tail).map(\.id) == ["m1", "m2"])
    }

    @Test func applyTailResetsWhenNonemptyTailHasNoOverlap() {
        let local = [message("old-1", role: .user), message("old-2", role: .assistant)]
        let tail = [message("new-1", role: .user), message("new-2", role: .assistant)]

        #expect(MessageHistoryMerge.applyTail(local: local, tail: tail).map(\.id) == ["new-1", "new-2"])
    }

    @Test func applyOlderPrependsAndNeverOverwritesNewer() {
        let local = [message("m3", role: .user, text: "live"), message("m4", role: .assistant, text: "live")]
        let older = [
            message("m1", role: .user, text: "old"),
            message("m3", role: .user, text: "stale"),
            message("m2", role: .assistant, text: "old"),
        ]

        let merged = MessageHistoryMerge.applyOlder(local: local, older: older)
        #expect(merged.map(\.id) == ["m1", "m2", "m3", "m4"])
        #expect(merged.first { $0.id == "m3" }?.text == "live")
    }

    @Test func beginsMidTurnWhenOldestIsAssistant() {
        #expect(MessageHistoryMerge.beginsMidTurn([message("a", role: .assistant)]))
        #expect(!MessageHistoryMerge.beginsMidTurn([message("u", role: .user), message("a", role: .assistant)]))
        #expect(!MessageHistoryMerge.beginsMidTurn([message("c", role: .command)]))
    }

    @Test func matchingTailIsAcceptedAgainstLargerLocalWindow() {
        let local = (1...5).map { message("m\($0)", role: $0.isMultiple(of: 2) ? .assistant : .user, text: "t\($0)") }
        let tail = Array(local.suffix(2))
        #expect(MessageHistoryMerge.shouldAcceptTail(remote: tail, local: local))
    }

    private func message(
        _ id: String,
        role: MessageRole,
        text: String = "text"
    ) -> Message {
        Message(id: id, role: role, text: text, model: nil, mode: nil, sentAtEpochMs: nil)
    }
}
