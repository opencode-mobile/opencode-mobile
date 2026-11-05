import Foundation

/// Parents learned for sessions of one connection profile, so a busy sub-session can mark the
/// Home row of its root without listing every session.
nonisolated struct SessionAncestryMap: Sendable, Equatable {
    /// Parent of each known session; nil for a root.
    var parents: [String: String?] = [:]
    /// Sessions seen in a loaded window or fetched while walking up, by id.
    var sessions: [String: Session] = [:]

    static let empty = SessionAncestryMap()

    mutating func record(_ session: Session) {
        // `updateValue`: assigning a nil parent through the subscript would drop the root.
        parents.updateValue(session.parentID.flatMap { $0.isEmpty ? nil : $0 }, forKey: session.id)
        sessions[session.id] = session
    }

    /// Root of `sessionID` by known parents, at most `SessionAncestry.maxDepth` steps up. A
    /// `loadedRoots` id ends the walk at once. Nil while a parent on the way is unknown, and
    /// for an archived session.
    func root(of sessionID: String, loadedRoots: Set<String> = []) -> String? {
        if sessions[sessionID]?.isArchived == true { return nil }
        var current = sessionID
        var seen: Set<String> = [sessionID]
        for _ in 0 ... SessionAncestry.maxDepth {
            if loadedRoots.contains(current) { return current }
            guard let known = parents[current] else { return nil }
            guard let parent = known, seen.insert(parent).inserted else { return current }
            current = parent
        }
        return nil
    }

    /// First session on the way up from `sessionID` whose parent is not known yet.
    func firstUnknown(from sessionID: String, loadedRoots: Set<String> = []) -> String? {
        var current = sessionID
        var seen: Set<String> = [sessionID]
        for _ in 0 ... SessionAncestry.maxDepth {
            if loadedRoots.contains(current) { return nil }
            guard let known = parents[current] else { return current }
            guard let parent = known, seen.insert(parent).inserted else { return nil }
            current = parent
        }
        return nil
    }
}

/// What ``SessionAncestry/resolveRoots(ids:directory:)`` found.
nonisolated struct SessionAncestryResolution: Sendable, Equatable {
    /// Root of each asked id that resolved.
    var roots: [String: String] = [:]
    /// Those roots as sessions, when known.
    var rootSessions: [String: Session] = [:]
    /// A fetch taught something new; rows built before it are out of date.
    var learned = false
}

/// In-memory ancestry for the live session store. Seeded from every loaded window; unknown
/// parents are fetched one session at a time (`GET` session by id), memoized, failures included.
nonisolated final class SessionAncestry: @unchecked Sendable {
    /// Steps walked up from a session before giving up.
    static let maxDepth = 8
    /// A session that failed to load is not asked for again within this long.
    static let failureRetryMs: Int64 = 60_000

    typealias Fetch = @Sendable (_ sessionID: String, _ directory: String?) async throws -> Session

    private let lock = NSLock()
    private let fetch: Fetch?
    private let nowMs: @Sendable () -> Int64
    private var map = SessionAncestryMap.empty
    private var failedAtMs: [String: Int64] = [:]
    private var inFlight: [String: Task<Bool, Never>] = [:]
    private var generation = 0

    /// Without `fetch`, only seeded sessions resolve.
    init(
        fetch: Fetch?,
        nowMs: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.fetch = fetch
        self.nowMs = nowMs
    }

    func record(_ sessions: [Session]) {
        guard !sessions.isEmpty else { return }
        lock.withLock {
            for session in sessions {
                map.record(session)
                failedAtMs.removeValue(forKey: session.id)
            }
        }
    }

    func snapshot() -> SessionAncestryMap {
        lock.withLock { map }
    }

    func rootOf(_ sessionID: String) -> String? {
        snapshot().root(of: sessionID)
    }

    /// Forgets everything (profile switch, Clear cache). Fetches still running are dropped.
    func clear() {
        lock.withLock {
            generation += 1
            map = .empty
            failedAtMs.removeAll()
            inFlight.removeAll()
        }
    }

    /// Walks each id up to its root, fetching parents nobody has seen. A `loadedRoots` id (a row
    /// of the loaded window) ends the walk without a fetch. `directory` scopes v1 requests; v2
    /// ignores it.
    func resolveRoots(
        ids: [String],
        directory: String?,
        loadedRoots: Set<String> = []
    ) async -> SessionAncestryResolution {
        var resolution = SessionAncestryResolution()
        var asked: Set<String> = []
        for id in ids where asked.insert(id).inserted {
            for _ in 0 ... Self.maxDepth {
                guard let unknown = snapshot().firstUnknown(from: id, loadedRoots: loadedRoots) else { break }
                guard await load(unknown, directory: directory) else { break }
                resolution.learned = true
            }
            let known = snapshot()
            if let root = known.root(of: id, loadedRoots: loadedRoots) {
                resolution.roots[id] = root
                resolution.rootSessions[root] = known.sessions[root]
            }
        }
        return resolution
    }

    /// True when `sessionID` is known now. A recent failure or a missing fetch returns false at once.
    private func load(_ sessionID: String, directory: String?) async -> Bool {
        let now = nowMs()
        let task: Task<Bool, Never>? = lock.withLock {
            guard let fetch else { return nil }
            if let failed = failedAtMs[sessionID], now - failed < Self.failureRetryMs { return nil }
            if let running = inFlight[sessionID] { return running }
            let startedIn = generation
            let task = Task<Bool, Never> { [weak self] in
                let session = try? await fetch(sessionID, directory)
                return self?.finishLoad(sessionID, session: session, startedIn: startedIn) ?? false
            }
            inFlight[sessionID] = task
            return task
        }
        return await task?.value ?? false
    }

    private func finishLoad(_ sessionID: String, session: Session?, startedIn: Int) -> Bool {
        lock.withLock {
            guard generation == startedIn else { return false }
            inFlight.removeValue(forKey: sessionID)
            guard let session, session.id == sessionID else {
                failedAtMs[sessionID] = nowMs()
                return false
            }
            map.record(session)
            return true
        }
    }
}
