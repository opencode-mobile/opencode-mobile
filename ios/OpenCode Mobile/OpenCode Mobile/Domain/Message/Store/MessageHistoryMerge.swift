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
        let olderPrefix = Array(local.prefix(firstOverlap))
        // Only rows newer than the tail's newest message survive (live arrivals the
        // snapshot predates). When that message is not local, every local row past
        // the overlap sits inside the tail's window, so the tail disproves it (a
        // committed revert removed it on the server).
        let tailIDs = Set(tail.map(\.id))
        var newerSuffix: [Message] = []
        if let newestID = tail.last?.id, let newestLocal = local.lastIndex(where: { $0.id == newestID }) {
            newerSuffix = local.suffix(from: newestLocal + 1).filter { !tailIDs.contains($0.id) }
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
        let localWindow = local[firstOverlap...lastOverlap].filter { remoteIDs.contains($0.id) }
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
