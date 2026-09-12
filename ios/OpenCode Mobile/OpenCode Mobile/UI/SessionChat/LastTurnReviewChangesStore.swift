import Foundation

/// Session-scoped in-memory Last Turn summary. Changes publishes counts after a
/// successful load; Session Chat reads them so the pill can appear on return.
/// Patch text is never stored.
@MainActor
final class LastTurnReviewChangesStore {
    struct Snapshot: Equatable {
        var sessionID: String
        var messageID: String
        var summary: ReviewChangesSummary?
    }

    private var snapshots: [String: Snapshot] = [:]
    private var listeners: [UUID: (String) -> Void] = [:]

    func snapshot(sessionID: String) -> Snapshot? {
        snapshots[sessionID]
    }

    func publish(sessionID: String, messageID: String, summary: ReviewChangesSummary?) {
        snapshots[sessionID] = Snapshot(sessionID: sessionID, messageID: messageID, summary: summary)
        for listener in listeners.values {
            listener(sessionID)
        }
    }

    func observe(_ listener: @escaping (String) -> Void) -> UUID {
        let id = UUID()
        listeners[id] = listener
        return id
    }

    func stopObserving(_ id: UUID) {
        listeners[id] = nil
    }
}
