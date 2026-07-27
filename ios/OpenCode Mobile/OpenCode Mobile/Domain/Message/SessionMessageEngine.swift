import Foundation

/// Named single-writer reducer facade. MessageSync owns transport/recovery;
/// this type owns apply/revision and live-frame splicing.
final class SessionMessageEngine: @unchecked Sendable {
    private let store: InMemoryMessageStore

    init(store: InMemoryMessageStore) {
        self.store = store
    }

    func apply(sessionID: String, patch: MessagePatch) -> MessageStoreApplyResult {
        store.apply(sessionID: sessionID, patch: patch)
    }

    func replaceAll(sessionID: String, messages: [Message]) {
        store.replaceAll(sessionID: sessionID, messages: messages)
    }

    func replaceAllIfRevision(
        sessionID: String,
        messages: [Message],
        expectedRevision: UInt64
    ) -> Bool {
        store.replaceAllIfRevision(
            sessionID: sessionID,
            messages: messages,
            expectedRevision: expectedRevision
        )
    }

    func messages(sessionID: String) -> [Message] {
        store.messages(sessionID: sessionID)
    }

    func message(sessionID: String, messageID: String) -> Message? {
        store.message(sessionID: sessionID, messageID: messageID)
    }

    func revision(sessionID: String) -> UInt64 {
        store.revision(sessionID: sessionID)
    }

    func isRestSeededMessage(sessionID: String, messageID: String) -> Bool {
        store.isRestSeededMessage(sessionID: sessionID, messageID: messageID)
    }

    func hasStreamingText(sessionID: String) -> Bool {
        store.hasStreamingText(sessionID: sessionID)
    }

    func finishStreamingText(sessionID: String) {
        store.finishStreamingText(sessionID: sessionID)
    }

    func clear() {
        store.clear()
    }

    /// Live SSE publishability without scanning the full message list.
    func shouldPublishLive(_ changeSet: SessionChangeSet) -> Bool {
        !changeSet.changedPartIDsByMessage.isEmpty
            || !changeSet.removedMessageIDs.isEmpty
            || !changeSet.streamingStateChangedIDs.isEmpty
    }

    /// Build the next published frame by splicing changed message IDs into
    /// `previous`, or fully rebuilding on structural inserts/removals.
    func frameAfterChange(
        sessionID: String,
        changeSet: SessionChangeSet,
        previous: [Message]?
    ) -> [Message] {
        if previous == nil
            || changeSet.structuralChange
            || !changeSet.insertedMessageIDs.isEmpty
            || !changeSet.removedMessageIDs.isEmpty
        {
            return store.messages(sessionID: sessionID)
        }
        var indexByID: [String: Int] = [:]
        indexByID.reserveCapacity(previous!.count)
        for (index, message) in previous!.enumerated() {
            indexByID[message.id] = index
        }
        var next = previous!
        for id in changeSet.changedMessageIDs {
            guard let updated = store.message(sessionID: sessionID, messageID: id) else { continue }
            if let index = indexByID[id] {
                next[index] = updated
            }
        }
        return next
    }
}
