import Foundation

nonisolated struct LiveConnectionConfig: Equatable, Sendable {
    struct Identity: Equatable, Hashable, Sendable {
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
    case pathLost
    case leaseLost
    case parseFailed
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

extension LiveConnectionSnapshot {
    nonisolated var isReachable: Bool {
        connectionState == .connecting
            || connectionState == .connected
            || connectionState == .reconnecting
    }

    /// Every required SSE lease is actually up. Connecting/Reconnecting are not live.
    nonisolated var isFullyLive: Bool {
        !requiredTargets.isEmpty && requiredTargets.allSatisfy { liveTargets.contains($0) }
    }

    /// Non-force REST catch-up: occupancy is live and every required lease is up.
    nonisolated var canCatchUp: Bool {
        connectionState == .connected && isFullyLive
    }
}

nonisolated func canCatchUp(
    occupancy: StreamOccupancy,
    snapshot: LiveConnectionSnapshot
) -> Bool {
    occupancy == .active && snapshot.canCatchUp
}

extension ForegroundEventStream {
    func asLiveConnection() -> any LiveConnection {
        if let live = self as? any LiveConnection {
            return live
        }
        return AdaptedLiveConnection(stream: self)
    }
}

nonisolated final class AdaptedLiveConnection: LiveConnection, @unchecked Sendable {
    private let stream: any ForegroundEventStream

    init(stream: any ForegroundEventStream) {
        self.stream = stream
    }

    var connectionState: EventStreamConnectionState { stream.connectionState }

    func connectionStateUpdates() -> AsyncStream<EventStreamConnectionState> {
        stream.connectionStateUpdates()
    }

    func events() -> AsyncStream<OpencodeEvent> {
        stream.events()
    }

    func start() { stream.start() }

    func stop() { stream.stop() }

    var snapshot: LiveConnectionSnapshot {
        LiveConnectionSnapshot(connectionState: stream.connectionState)
    }

    func snapshotUpdates() -> AsyncStream<LiveConnectionSnapshot> {
        AsyncStream { continuation in
            continuation.yield(snapshot)
            continuation.finish()
        }
    }
}

nonisolated final class LiveConnectionRevisionSignal: @unchecked Sendable {
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<Void>.Continuation] = [:]

    func stream() -> AsyncStream<Void> {
        let id = UUID()
        let (stream, continuation) = AsyncStream.makeStream(of: Void.self)
        lock.lock()
        continuations[id] = continuation
        lock.unlock()
        continuation.onTermination = { [weak self] _ in
            guard let self else { return }
            self.lock.lock()
            self.continuations.removeValue(forKey: id)
            self.lock.unlock()
        }
        return stream
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
