import Foundation

/// Filters which foreground events enter MessageSync per-session staging.
/// Global consumers (permissions, questions, home/session list) keep their own
/// filters on the shared stream.
nonisolated enum MessageEventRouter {
    static let messageIntakeTypes: Set<String> = [
        "message.updated",
        "message.part.updated",
        "message.part.delta",
        "message.removed",
        "message.part.removed",
        "session.idle",
        "stream.reconnected",
        "server.connected",
    ]

    static func shouldStageForMessageSync(_ event: OpencodeEvent) -> Bool {
        messageIntakeTypes.contains(event.type)
    }

    /// Cheap UTF-8 size proxy for staging byte budgets. Never inspects full
    /// message bodies beyond the delta/text fields already on the event.
    static func estimatedByteCost(_ event: OpencodeEvent) -> Int {
        var cost = event.type.utf8.count + (event.sessionID?.utf8.count ?? 0)
        if let props = event.rawProperties {
            if case let .string(delta)? = props["delta"] {
                cost += delta.utf8.count
            }
            if case let .dictionary(part)? = props["part"],
               case let .string(text)? = part["text"] {
                cost += text.utf8.count
            }
        }
        return max(64, cost)
    }
}

/// Monotonic foreground-connection generation. Bumped on each connect/reconnect
/// cycle so observations can reject stale-epoch work.
nonisolated final class ConnectionEpoch: @unchecked Sendable {
    static let shared = ConnectionEpoch()

    private let lock = NSLock()
    private var value: UInt64 = 0

    func current() -> UInt64 {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    @discardableResult
    func bump() -> UInt64 {
        lock.lock()
        defer { lock.unlock() }
        value &+= 1
        return value
    }
}

/// Per-observation generation. Adopted forward on stream.reconnected /
/// server.connected so an SSE start/reconnect bump does not latch Recovering.
nonisolated final class ObservationEpoch: @unchecked Sendable {
    private let lock = NSLock()
    private var value: UInt64

    init(_ value: UInt64) {
        self.value = value
    }

    var current: UInt64 {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func adopt(_ newValue: UInt64) {
        lock.lock()
        defer { lock.unlock() }
        value = newValue
    }
}

nonisolated let messageEventStagingByteCapacity = 2_000_000
