import Foundation

nonisolated enum MessageHistoryMerge {
    static func beginsMidTurn(_ messages: [Message]) -> Bool {
        guard let oldest = messages.first else { return false }
        return !isTurnBoundary(oldest)
    }

    static func isTurnBoundary(_ message: Message) -> Bool {
        message.role == .user || message.role == .command
    }

    static func applyTail(local: [Message], tail: [Message]) -> [Message] {
        if tail.isEmpty { return [] }
        guard let overlapIndex = oldestOverlapIndex(local: local, tail: tail) else {
            return tail
        }
        let olderPrefix = Array(local.prefix(overlapIndex))
        return olderPrefix + tail
    }

    static func applyOlder(local: [Message], older: [Message]) -> [Message] {
        let localIDs = Set(local.map(\.id))
        let uniqueOlder = older.filter { !localIDs.contains($0.id) }
        return uniqueOlder + local
    }

    static func hasOverlap(local: [Message], tail: [Message]) -> Bool {
        oldestOverlapIndex(local: local, tail: tail) != nil
    }

    static func shouldAcceptTail(remote: [Message], local: [Message]) -> Bool {
        if remote.isEmpty { return true }
        guard hasOverlap(local: local, tail: remote) else { return true }
        let overlapIndex = oldestOverlapIndex(local: local, tail: remote) ?? 0
        let localWindow = Array(local[overlapIndex...])
        return MessageSnapshotAuthority.isAheadOrEqual(remote: remote, local: localWindow)
    }

    private static func oldestOverlapIndex(local: [Message], tail: [Message]) -> Int? {
        let localIndex = Dictionary(uniqueKeysWithValues: local.enumerated().map { ($1.id, $0) })
        for message in tail {
            if let index = localIndex[message.id] {
                return index
            }
        }
        return nil
    }
}
