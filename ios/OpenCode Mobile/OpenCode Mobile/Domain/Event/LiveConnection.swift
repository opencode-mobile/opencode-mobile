import Foundation

nonisolated struct LiveConnectionConfig: Equatable, Sendable {
    struct Identity: Equatable, Sendable {
        let profileID: String
        let connectionIdentity: String
        let serverProtocol: ServerProtocol
    }

    let profileID: String
    let connectionIdentity: String
    let serverProtocol: ServerProtocol
    let directories: [String]

    var identity: Identity {
        Identity(profileID: profileID, connectionIdentity: connectionIdentity, serverProtocol: serverProtocol)
    }
}

nonisolated enum LiveGapReason: Equatable, Sendable {
    case occupancyStart
    case reconnect
    case configChanged
    case stopped
}

nonisolated struct LiveGapRevision: Equatable, Sendable {
    let generation: UInt64
    let reason: LiveGapReason
}

nonisolated struct LiveConnectionSnapshot: Equatable, Sendable {
    var config: LiveConnectionConfig? = nil
    var generation: UInt64 = 0
    var connectionState: EventStreamConnectionState = .idle
    var gap: LiveGapRevision? = nil
    var requiredTargets: [String] = []
    var liveTargets: [String] = []
}

/// Transport authority for foreground SSE. `ForegroundEventStream` stays the
/// caller-facing occupancy seam; this snapshot/gap surface is what later
/// phases query instead of inventing another clock.
nonisolated protocol LiveConnection: ForegroundEventStream {
    var snapshot: LiveConnectionSnapshot { get }
    func snapshotUpdates() -> AsyncStream<LiveConnectionSnapshot>
}

nonisolated final class LiveConnectionRevisionSignal: @unchecked Sendable {
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<Void>.Continuation] = [:]

    func stream() -> AsyncStream<Void> {
        let id = UUID()
        return AsyncStream { continuation in
            lock.lock()
            continuations[id] = continuation
            lock.unlock()
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.continuations.removeValue(forKey: id)
                self.lock.unlock()
            }
        }
    }

    func yield() {
        lock.lock()
        let conts = Array(continuations.values)
        lock.unlock()
        for continuation in conts {
            continuation.yield(())
        }
    }
}
