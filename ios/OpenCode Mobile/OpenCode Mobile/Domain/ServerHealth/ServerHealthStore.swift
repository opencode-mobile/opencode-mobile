import Foundation

/// On-device record of Server Health checks and events, per Connection Profile.
nonisolated protocol ServerHealthStore: AnyObject, Sendable {
    func appendPing(_ ping: ServerHealthPing) async
    func appendEvent(_ event: ServerHealthEvent) async
    /// Checks at or after `sinceMs`, oldest first.
    func pings(profileID: String, sinceMs: Int64) async -> [ServerHealthPing]
    /// Events at or after `sinceMs`, oldest first. The latest state event before `sinceMs`
    /// comes first when there is one, so the caller knows the state at the start.
    func events(profileID: String, sinceMs: Int64) async -> [ServerHealthEvent]
    /// The most recent check that reported a pid or a version.
    func lastPid(profileID: String) async -> ServerHealthProcessIdentity?
    func prune(olderThanMs: Int64) async
    func purge(profileID: String) async
    func purgeAll() async
}

extension ServerHealthStore {
    /// The latest recorded state event for the profile, if any.
    nonisolated func latestState(profileID: String) async -> ServerHealthConnectionState? {
        await events(profileID: profileID, sinceMs: .max).last?.connectionState
    }
}

/// Keeps the record in memory. The fallback when SwiftData cannot open, and the test double.
nonisolated final class InMemoryServerHealthStore: ServerHealthStore, @unchecked Sendable {
    private let lock = NSLock()
    private var storedPings: [ServerHealthPing] = []
    private var storedEvents: [ServerHealthEvent] = []

    init(pings: [ServerHealthPing] = [], events: [ServerHealthEvent] = []) {
        storedPings = pings
        storedEvents = events
    }

    var allPings: [ServerHealthPing] {
        lock.lock()
        defer { lock.unlock() }
        return storedPings
    }

    var allEvents: [ServerHealthEvent] {
        lock.lock()
        defer { lock.unlock() }
        return storedEvents
    }

    func appendPing(_ ping: ServerHealthPing) async {
        lock.lock()
        storedPings.append(ping)
        lock.unlock()
    }

    func appendEvent(_ event: ServerHealthEvent) async {
        lock.lock()
        storedEvents.append(event)
        lock.unlock()
    }

    func pings(profileID: String, sinceMs: Int64) async -> [ServerHealthPing] {
        lock.lock()
        defer { lock.unlock() }
        return storedPings
            .enumerated()
            .filter { $0.element.profileID == profileID && $0.element.atMs >= sinceMs }
            .sorted { $0.element.atMs == $1.element.atMs ? $0.offset < $1.offset : $0.element.atMs < $1.element.atMs }
            .map(\.element)
    }

    func events(profileID: String, sinceMs: Int64) async -> [ServerHealthEvent] {
        lock.lock()
        defer { lock.unlock() }
        let ordered = storedEvents
            .enumerated()
            .filter { $0.element.profileID == profileID }
            .sorted { $0.element.atMs == $1.element.atMs ? $0.offset < $1.offset : $0.element.atMs < $1.element.atMs }
            .map(\.element)
        let before = ordered.last { $0.kind == .state && $0.atMs < sinceMs }
        let after = ordered.filter { $0.atMs >= sinceMs }
        return (before.map { [$0] } ?? []) + after
    }

    func lastPid(profileID: String) async -> ServerHealthProcessIdentity? {
        lock.lock()
        defer { lock.unlock() }
        let latest = storedPings
            .enumerated()
            .filter { $0.element.profileID == profileID && ($0.element.pid != nil || $0.element.version != nil) }
            .max { $0.element.atMs == $1.element.atMs ? $0.offset < $1.offset : $0.element.atMs < $1.element.atMs }?
            .element
        return latest.map { ServerHealthProcessIdentity(pid: $0.pid, version: $0.version) }
    }

    func prune(olderThanMs: Int64) async {
        lock.lock()
        storedPings.removeAll { $0.atMs < olderThanMs }
        storedEvents.removeAll { $0.atMs < olderThanMs }
        lock.unlock()
    }

    func purge(profileID: String) async {
        lock.lock()
        storedPings.removeAll { $0.profileID == profileID }
        storedEvents.removeAll { $0.profileID == profileID }
        lock.unlock()
    }

    func purgeAll() async {
        lock.lock()
        storedPings.removeAll()
        storedEvents.removeAll()
        lock.unlock()
    }
}
