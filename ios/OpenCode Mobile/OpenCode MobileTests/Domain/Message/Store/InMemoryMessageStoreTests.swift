import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct InMemoryMessageStoreTests {
    @Test func messages_toleratesDuplicateBaseMessageIDsWithLastWriteWins() {
        let store = InMemoryMessageStore()
        let first = Message(
            id: "msg_0e6652a78001MAtzSSAbvUq9yZ",
            role: .assistant,
            text: "first",
            model: nil,
            mode: nil,
            sentAtEpochMs: 1
        )
        let second = Message(
            id: "msg_0e6652a78001MAtzSSAbvUq9yZ",
            role: .assistant,
            text: "second",
            model: nil,
            mode: nil,
            sentAtEpochMs: 2
        )

        store.replaceAll(sessionID: "ses-1", messages: [first, second])

        let messages = store.messages(sessionID: "ses-1")
        #expect(messages.map(\.text) == ["second", "second"])
        #expect(store.message(sessionID: "ses-1", messageID: first.id)?.text == "second")
    }

    @Test func prependOlder_toleratesDuplicateBaseMessageIDs() {
        let store = InMemoryMessageStore()
        let duplicateID = "msg_dup"
        store.replaceAll(
            sessionID: "ses-1",
            messages: [
                Message(id: duplicateID, role: .user, text: "a", model: nil, mode: nil, sentAtEpochMs: 1),
                Message(id: duplicateID, role: .user, text: "b", model: nil, mode: nil, sentAtEpochMs: 2),
            ]
        )

        let accepted = store.prependOlderIfRevision(
            sessionID: "ses-1",
            older: [
                Message(id: "msg_older", role: .user, text: "older", model: nil, mode: nil, sentAtEpochMs: 0),
            ],
            expectedRevision: store.revision(sessionID: "ses-1")
        )

        #expect(accepted)
        let messages = store.messages(sessionID: "ses-1")
        #expect(messages.map(\.id) == ["msg_older", duplicateID, duplicateID])
        #expect(messages.map(\.text) == ["older", "b", "b"])
    }
}
