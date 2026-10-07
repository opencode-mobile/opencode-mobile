import Foundation
import Testing
@testable import OpenCode_Mobile

struct MessageHistoryMergeTests {
    @Test func applyTailEmptyTailKeepsLocal() {
        let local = [message("m1", role: .user), message("m2", role: .assistant)]

        #expect(MessageHistoryMerge.applyTail(local: local, tail: []).map(\.id) == ["m1", "m2"])
    }

    @Test func applyTailPreservesOlderThanOverlapAndUpsertsNewer() {
        let local = [
            message("m1", role: .user, text: "old"),
            message("m2", role: .assistant, text: "cached"),
            message("m3", role: .user, text: "stale"),
            message("m4", role: .assistant, text: "sse"),
        ]
        let tail = [
            message("m2", role: .assistant, text: "fresh"),
            message("m3", role: .user, text: "latest"),
        ]

        let merged = MessageHistoryMerge.applyTail(local: local, tail: tail)
        #expect(merged.map(\.id) == ["m1", "m2", "m3", "m4"])
        #expect(merged[1].text == "fresh")
        #expect(merged[2].text == "latest")
        #expect(merged[3].text == "sse")
    }

    @Test func applyTailDropsRowsInsideOverlapWindowAndKeepsSSESuffix() {
        let local = [
            message("m1", role: .user, text: "old"),
            message("m2", role: .assistant, text: "overlap"),
            message("gone", role: .assistant, text: "disproved"),
            message("m3", role: .user, text: "overlap-end"),
            message("sse", role: .assistant, text: "live"),
        ]
        let tail = [
            message("m2", role: .assistant, text: "overlap"),
            message("m3", role: .user, text: "overlap-end"),
        ]

        #expect(MessageHistoryMerge.applyTail(local: local, tail: tail).map(\.id) == ["m1", "m2", "m3", "sse"])
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

    @Test func overlappingTailIgnoresNewerLocalSSERowAndKeepsSuffix() {
        let local = [
            message("old", role: .user, text: "old"),
            message("mid", role: .assistant, text: "mid"),
            message("tail-end", role: .user, text: "tail-end"),
            message("sse-new", role: .assistant, text: "live sse extra text"),
        ]
        let tail = [
            message("mid", role: .assistant, text: "mid"),
            message("tail-end", role: .user, text: "tail-end"),
        ]

        #expect(MessageHistoryMerge.shouldAcceptTail(remote: tail, local: local))
        let merged = MessageHistoryMerge.applyTail(local: local, tail: tail)
        #expect(merged.map(\.id) == ["old", "mid", "tail-end", "sse-new"])
        #expect(merged.last?.text == "live sse extra text")
    }

    @Test func tailThatDroppedRevertedMessagesIsAcceptedAndDropsThem() {
        // Cached transcript still holds a reverted turn the server has since removed.
        let local = [
            message("u1", role: .user, text: "First prompt"),
            message("a1", role: .assistant, text: "Answer to the first prompt."),
            message("u2", role: .user, text: "Queued prompt"),
            message("a2", role: .assistant, text: "A much longer answer to the queued prompt that was reverted."),
            message("u3", role: .user, text: "Queued prompt v2"),
            message("a3", role: .assistant, text: "Answer to the replacement prompt."),
        ]
        let tail = [
            message("u1", role: .user, text: "First prompt"),
            message("a1", role: .assistant, text: "Answer to the first prompt."),
            message("u3", role: .user, text: "Queued prompt v2"),
            message("a3", role: .assistant, text: "Answer to the replacement prompt."),
        ]

        #expect(MessageHistoryMerge.shouldAcceptTail(remote: tail, local: local))
        #expect(MessageHistoryMerge.applyTail(local: local, tail: tail).map(\.id) == ["u1", "a1", "u3", "a3"])
    }

    @Test func reorderedOverlapAcceptsEqualMessagesWithoutTrapping() {
        let local = [
            message("c", role: .user),
            message("a", role: .assistant),
        ]
        let tail = [
            message("a", role: .assistant),
            message("c", role: .user),
        ]

        #expect(MessageHistoryMerge.shouldAcceptTail(remote: tail, local: local))
    }

    @Test func reorderedTailDropsOmittedRowsBetweenOverlapIndexes() {
        let local = [
            message("A", role: .user),
            message("reverted", role: .assistant),
            message("C", role: .user),
            message("sse", role: .assistant),
        ]
        let tail = [
            message("C", role: .user),
            message("A", role: .user),
        ]

        #expect(MessageHistoryMerge.applyTail(local: local, tail: tail).map(\.id) == ["C", "A", "sse"])
    }

    @Test func tailWhoseNewTurnReplacedACachedRevertedTurnDropsIt() {
        let local = [
            message("u1", role: .user, text: "First prompt"),
            message("a1", role: .assistant, text: "Answer one."),
            message("u2", role: .user, text: "Reverted prompt"),
            message("a2", role: .assistant, text: "A long reverted answer that is no longer on the server."),
        ]
        let tail = [
            message("u1", role: .user, text: "First prompt"),
            message("a1", role: .assistant, text: "Answer one."),
            message("u3", role: .user, text: "New"),
        ]

        #expect(MessageHistoryMerge.shouldAcceptTail(remote: tail, local: local))
        #expect(MessageHistoryMerge.applyTail(local: local, tail: tail).map(\.id) == ["u1", "a1", "u3"])
    }

    private func message(
        _ id: String,
        role: MessageRole,
        text: String = "text"
    ) -> Message {
        Message(id: id, role: role, text: text, model: nil, mode: nil, sentAtEpochMs: nil)
    }
}
