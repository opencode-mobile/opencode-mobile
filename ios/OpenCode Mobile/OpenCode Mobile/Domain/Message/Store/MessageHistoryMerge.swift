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
        let tailIDs = Set(tail.map(\.id))
        let overlapIndexes = local.indices.filter { tailIDs.contains(local[$0].id) }
        // Prefix is everything before the earliest local id the tail contains.
        // A suffix survives only when `tail.last` is already local, and only past
        // the latest overlapping local index. Rows inside that span were omitted
        // by the tail (a revert), including when the tail arrives out of order.
        guard let earliestOverlap = overlapIndexes.min(), let latestOverlap = overlapIndexes.max() else {
            return tail
        }
        let olderPrefix = Array(local.prefix(earliestOverlap))
        let newerSuffix: [Message]
        if let newestID = tail.last?.id,
           local.contains(where: { $0.id == newestID }),
           latestOverlap + 1 < local.count {
            newerSuffix = Array(local.suffix(from: latestOverlap + 1)).filter { !tailIDs.contains($0.id) }
        } else {
            newerSuffix = []
        }
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
        guard let firstOverlap = oldestOverlapIndex(local: local, tail: remote) else { return true }
        let lastOverlap = newestOverlapIndex(local: local, tail: remote) ?? firstOverlap
        // A local message inside the tail's window that the tail lacks was removed
        // on the server (a committed revert): it must not make the tail look stale.
        let remoteIDs = Set(remote.map(\.id))
        let lower = min(firstOverlap, lastOverlap)
        let upper = max(firstOverlap, lastOverlap)
        let localWindow = local[lower...upper].filter { remoteIDs.contains($0.id) }
        return MessageSnapshotAuthority.isAheadOrEqual(remote: remote, local: localWindow)
    }

    private static func oldestOverlapIndex(local: [Message], tail: [Message]) -> Int? {
        overlapIndex(local: local, tail: tail, reversed: false)
    }

    private static func newestOverlapIndex(local: [Message], tail: [Message]) -> Int? {
        overlapIndex(local: local, tail: tail, reversed: true)
    }

    private static func overlapIndex(local: [Message], tail: [Message], reversed: Bool) -> Int? {
        let localIndex = Dictionary(
            local.enumerated().map { ($1.id, $0) },
            uniquingKeysWith: { _, last in last }
        )
        let sequence = reversed ? Array(tail.reversed()) : tail
        for message in sequence {
            if let index = localIndex[message.id] {
                return index
            }
        }
        return nil
    }
}
