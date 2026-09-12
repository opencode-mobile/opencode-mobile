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
        if tail.isEmpty { return local }
        guard let firstOverlap = oldestOverlapIndex(local: local, tail: tail) else {
            return tail
        }
        let lastOverlap = newestOverlapIndex(local: local, tail: tail) ?? firstOverlap
        let olderPrefix = Array(local.prefix(firstOverlap))
        let newerSuffix = Array(local.suffix(from: min(local.count, lastOverlap + 1)))
        return olderPrefix + tail + newerSuffix
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
        overlapIndex(local: local, tail: tail, reversed: false)
    }

    private static func newestOverlapIndex(local: [Message], tail: [Message]) -> Int? {
        overlapIndex(local: local, tail: tail, reversed: true)
    }

    private static func overlapIndex(local: [Message], tail: [Message], reversed: Bool) -> Int? {
        let localIndex = Dictionary(uniqueKeysWithValues: local.enumerated().map { ($1.id, $0) })
        let sequence = reversed ? Array(tail.reversed()) : tail
        for message in sequence {
            if let index = localIndex[message.id] {
                return index
            }
        }
        return nil
    }
}
