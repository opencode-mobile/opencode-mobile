import Foundation
import OSLog

@MainActor
final class DefaultSessionReadyStore: SessionReadyStore {
    static let readyTTLMs: Double = 24 * 60 * 60 * 1000

    private final class DirectoryHandle {
        var continuations: [UUID: AsyncStream<Set<String>>.Continuation] = [:]
        var statusTask: Task<Void, Never>?
        var previousTypes: [String: String?] = [:]
    }

    private struct ReadyRecord {
        var directory: String
        var readyAtEpochMs: Double
    }

    private struct WatchRecord {
        var directory: String
        var watchedAtEpochMs: Double
        /// focused-status-event watches are speculative; do not idle-catch-up from them.
        var allowIdleCatchUp: Bool
    }

    private let sessionStatusLiveStore: any SessionStatusLiveStore
    private let eventStream: any ForegroundEventStream
    private let persistence: any SessionReadyPersistence
    private let clock: any SessionReadyClock
    private let logger = Logger(subsystem: "dev.opencode.mobile", category: "SessionReadyStore")

    private var watched: [String: WatchRecord] = [:]
    private var ready: [String: ReadyRecord] = [:]
    private var currentProfileID = ""
    private var focusedSessionID: String?
    private var focusedDirectory: String?
    private var handles: [String: DirectoryHandle] = [:]
    private var eventTask: Task<Void, Never>?

    init(
        sessionStatusLiveStore: any SessionStatusLiveStore,
        eventStream: any ForegroundEventStream,
        persistence: any SessionReadyPersistence = InMemorySessionReadyPersistence(),
        clock: any SessionReadyClock = SystemSessionReadyClock()
    ) {
        self.sessionStatusLiveStore = sessionStatusLiveStore
        self.eventStream = eventStream
        self.persistence = persistence
        self.clock = clock
        loadPersisted()
        for directory in Set(watched.values.map(\.directory)) {
            let handle = handles[directory] ?? DirectoryHandle()
            handles[directory] = handle
            ensureStatusObservation(directory: directory, handle: handle)
        }
        eventTask = Task.detached { [weak self, eventStream] in
            let store = self
            for await event in eventStream.events() {
                // Filter off-main; only control events hop to the Ready store.
                switch event.type {
                case "session.status", "session.idle", "stream.reconnected", "server.connected":
                    break
                default:
                    continue
                }
                await MainActor.run {
                    store?.onEvent(type: event.type, sessionID: event.sessionID)
                }
            }
        }
    }

    func observeReadyIds(directory: String) -> AsyncStream<Set<String>> {
        let pruned = pruneExpired()
        for dir in pruned {
            publishReady(directory: dir, prune: false)
        }
        let handle = handles[directory] ?? DirectoryHandle()
        handles[directory] = handle
        ensureStatusObservation(directory: directory, handle: handle)

        return AsyncStream { [weak self] continuation in
            guard let self else {
                continuation.finish()
                return
            }
            let id = UUID()
            handle.continuations[id] = continuation
            continuation.yield(self.readyIds(for: directory))
            continuation.onTermination = { @Sendable _ in
                Task { @MainActor [weak self] in
                    self?.handles[directory]?.continuations.removeValue(forKey: id)
                }
            }
        }
    }

    func notifyPromptSent(sessionID: String, directory: String) {
        startWatch(sessionID: sessionID, directory: directory, reason: "prompt")
        let handle = handles[directory] ?? DirectoryHandle()
        handles[directory] = handle
        ensureStatusObservation(directory: directory, handle: handle)
    }

    func setFocusedSession(sessionID: String?, directory: String?) {
        if sessionID == nil {
            if let priorID = focusedSessionID, let priorDirectory = focusedDirectory {
                let type = handles[priorDirectory]?.previousTypes[priorID] ?? nil
                if type.isBusyOrRetry {
                    startWatch(sessionID: priorID, directory: priorDirectory, reason: "leave-while-busy")
                }
            }
            let priorID = focusedSessionID
            focusedSessionID = nil
            focusedDirectory = nil
            if let priorID {
                logger.info("focus cleared session=\(priorID, privacy: .public)")
            }
            return
        }

        guard let sessionID else { return }
        focusedSessionID = sessionID
        focusedDirectory = directory
        // Opening Session Chat acknowledges Ready. Drop Ready and any leftover watch so
        // idle catch-up cannot immediately re-stamp Ready after a focused session.status
        // arms a speculative watch.
        let readyRecord = ready.removeValue(forKey: sessionID)
        let watchRecord = watched.removeValue(forKey: sessionID)
        if readyRecord != nil || watchRecord != nil {
            if readyRecord != nil {
                logger.info("ready cleared on focus session=\(sessionID, privacy: .public)")
            }
            if watchRecord != nil {
                logger.info("watch cleared on focus session=\(sessionID, privacy: .public)")
            }
            persistState()
            publishReady(directory: readyRecord?.directory ?? watchRecord?.directory)
        }
        if let directory {
            let handle = handles[directory] ?? DirectoryHandle()
            handles[directory] = handle
            let previous = handle.previousTypes[sessionID] ?? nil
            if !previous.isBusyOrRetry {
                handle.previousTypes[sessionID] = "idle"
            }
            ensureStatusObservation(directory: directory, handle: handle)
        }
    }

    func bindProfile(profileID: String?) {
        let next = profileID ?? ""
        if next == currentProfileID { return }
        let migrateUnscoped = currentProfileID.isEmpty && !next.isEmpty && (!ready.isEmpty || !watched.isEmpty)
        if migrateUnscoped {
            currentProfileID = next
            persistState()
            focusedSessionID = nil
            focusedDirectory = nil
            for handle in handles.values {
                handle.previousTypes.removeAll()
            }
            logger.info("bindProfile migrated unscoped profile=\(next, privacy: .public)")
            return
        }
        persistState()
        watched.removeAll()
        ready.removeAll()
        focusedSessionID = nil
        focusedDirectory = nil
        currentProfileID = next
        loadPersisted()
        for (directory, handle) in handles {
            handle.previousTypes.removeAll()
            publishReady(directory: directory, handle: handle, prune: false)
        }
        for directory in Set(watched.values.map(\.directory)) {
            let handle = handles[directory] ?? DirectoryHandle()
            handles[directory] = handle
            ensureStatusObservation(directory: directory, handle: handle)
        }
        logger.info(
            "bindProfile profile=\(next, privacy: .public) ready=\(self.ready.count, privacy: .public) watched=\(self.watched.count, privacy: .public)"
        )
    }

    func clearCache() {
        watched.removeAll()
        ready.removeAll()
        focusedSessionID = nil
        focusedDirectory = nil
        persistence.save(SessionReadyPersistedState())
        for (directory, handle) in handles {
            handle.previousTypes.removeAll()
            publishReady(directory: directory, handle: handle, prune: false)
        }
        logger.info("clearCache")
    }

    private func onEvent(type: String, sessionID: String?) {
        guard let sessionID else { return }
        switch type {
        case "session.status":
            guard sessionID == focusedSessionID, let directory = focusedDirectory else { return }
            startWatch(sessionID: sessionID, directory: directory, reason: "focused-status-event")
            let handle = handles[directory] ?? DirectoryHandle()
            handles[directory] = handle
            ensureStatusObservation(directory: directory, handle: handle)
        case "session.idle":
            guard let directory = watched[sessionID]?.directory else { return }
            _ = handleIdle(sessionID: sessionID, directory: directory, markPreviousIdle: true)
        default:
            break
        }
    }

    private func ensureStatusObservation(directory: String, handle: DirectoryHandle) {
        guard handle.statusTask == nil else { return }
        handle.statusTask = Task { [weak self] in
            guard let self else { return }
            for await statuses in self.sessionStatusLiveStore.observeStatuses(directory: directory) {
                self.onStatuses(directory: directory, statuses: statuses)
            }
        }
    }

    private func onStatuses(directory: String, statuses: [String: SessionStatus]) {
        let pruned = pruneExpired()
        for dir in pruned where dir != directory {
            publishReady(directory: dir, prune: false)
        }
        guard let handle = handles[directory] else { return }
        let watchedIDs = Set(watched.compactMap { id, record in record.directory == directory ? id : nil })
        let sessionIDs = Set(handle.previousTypes.keys).union(statuses.keys).union(watchedIDs)
        var readyChanged = false

        for sessionID in sessionIDs {
            let type = statuses[sessionID]?.raw["type"]
            let prev = handle.previousTypes[sessionID] ?? nil
            let isBusy = type.isBusyOrRetry
            let wasBusy = prev.isBusyOrRetry
            let firstSighting = watched[sessionID]?.directory == directory &&
                !handle.previousTypes.keys.contains(sessionID)

            if sessionID == focusedSessionID,
               directory == focusedDirectory,
               isBusy
            {
                startWatch(sessionID: sessionID, directory: directory, reason: "focused-busy")
            }

            if watched[sessionID]?.directory == directory {
                let watch = watched[sessionID]
                let becameIdle = wasBusy && !isBusy
                let restoredAlreadyIdle = firstSighting && !isBusy && (watch?.allowIdleCatchUp == true)
                if becameIdle || restoredAlreadyIdle {
                    if handleIdle(sessionID: sessionID, directory: directory, markPreviousIdle: false) {
                        readyChanged = true
                    }
                }
            }

            if statuses[sessionID] != nil {
                handle.previousTypes[sessionID] = type
            } else if handle.previousTypes[sessionID] != nil {
                handle.previousTypes.removeValue(forKey: sessionID)
            }
        }

        if readyChanged {
            publishReady(directory: directory, handle: handle)
        }
    }

    @discardableResult
    private func startWatch(sessionID: String, directory: String, reason: String) -> Bool {
        if watched[sessionID]?.directory == directory { return false }
        // focused-status-event is speculative (type unknown before debounce). It must not
        // use restoredAlreadyIdle catch-up, or opening an idle Ready session can re-arm
        // Ready after leave when the status map still omits idle rows.
        let allowIdleCatchUp = reason != "focused-status-event"
        watched[sessionID] = WatchRecord(
            directory: directory,
            watchedAtEpochMs: clock.nowEpochMs(),
            allowIdleCatchUp: allowIdleCatchUp
        )
        logger.info(
            "watch started reason=\(reason, privacy: .public) session=\(sessionID, privacy: .public) directory=\(directory, privacy: .public)"
        )
        persistState()
        return true
    }

    @discardableResult
    private func handleIdle(sessionID: String, directory: String, markPreviousIdle: Bool) -> Bool {
        guard watched[sessionID]?.directory == directory else { return false }
        watched.removeValue(forKey: sessionID)
        if markPreviousIdle {
            let handle = handles[directory] ?? DirectoryHandle()
            handles[directory] = handle
            handle.previousTypes[sessionID] = "idle"
        }
        if sessionID == focusedSessionID {
            persistState()
            logger.info(
                "idle handled outcome=suppressed-open session=\(sessionID, privacy: .public) directory=\(directory, privacy: .public)"
            )
            return false
        }
        ready[sessionID] = ReadyRecord(directory: directory, readyAtEpochMs: clock.nowEpochMs())
        logger.info(
            "idle handled outcome=ready session=\(sessionID, privacy: .public) directory=\(directory, privacy: .public)"
        )
        persistState()
        publishReady(directory: directory)
        return true
    }

    private func loadPersisted() {
        let now = clock.nowEpochMs()
        let state = persistence.load()
        var pruned = false
        for entry in state.ready {
            if entry.profileID != currentProfileID { continue }
            if now - entry.atEpochMs >= Self.readyTTLMs {
                pruned = true
                continue
            }
            ready[entry.sessionID] = ReadyRecord(
                directory: entry.directory,
                readyAtEpochMs: entry.atEpochMs
            )
        }
        for entry in state.watched {
            if entry.profileID != currentProfileID { continue }
            if now - entry.atEpochMs >= Self.readyTTLMs {
                pruned = true
                continue
            }
            if ready[entry.sessionID] != nil { continue }
            watched[entry.sessionID] = WatchRecord(
                directory: entry.directory,
                watchedAtEpochMs: entry.atEpochMs,
                allowIdleCatchUp: true
            )
        }
        if pruned || !ready.isEmpty || !watched.isEmpty {
            persistState()
        }
        if !ready.isEmpty {
            logger.info("loaded persisted ready count=\(self.ready.count, privacy: .public)")
        }
        if !watched.isEmpty {
            logger.info("loaded persisted watched count=\(self.watched.count, privacy: .public)")
        }
    }

    @discardableResult
    private func pruneExpired() -> Set<String> {
        let now = clock.nowEpochMs()
        let expiredReady = ready.filter { now - $0.value.readyAtEpochMs >= Self.readyTTLMs }.map(\.key)
        let expiredWatched = watched.filter { now - $0.value.watchedAtEpochMs >= Self.readyTTLMs }.map(\.key)
        guard !expiredReady.isEmpty || !expiredWatched.isEmpty else { return [] }
        var directories = Set<String>()
        for sessionID in expiredReady {
            if let record = ready.removeValue(forKey: sessionID) {
                directories.insert(record.directory)
                logger.info("ready expired session=\(sessionID, privacy: .public)")
            }
        }
        for sessionID in expiredWatched {
            if let record = watched.removeValue(forKey: sessionID) {
                directories.insert(record.directory)
                logger.info("watch expired session=\(sessionID, privacy: .public)")
            }
        }
        persistState()
        return directories
    }

    private func persistState() {
        let now = clock.nowEpochMs()
        let stored = persistence.load()
        let otherReady = stored.ready.filter {
            $0.profileID != currentProfileID && now - $0.atEpochMs < Self.readyTTLMs
        }
        let otherWatched = stored.watched.filter {
            $0.profileID != currentProfileID && now - $0.atEpochMs < Self.readyTTLMs
        }
        let state = SessionReadyPersistedState(
            ready: otherReady + ready.map { sessionID, record in
                SessionReadyPersistedEntry(
                    sessionID: sessionID,
                    directory: record.directory,
                    atEpochMs: record.readyAtEpochMs,
                    profileID: currentProfileID
                )
            },
            watched: otherWatched + watched.map { sessionID, record in
                SessionReadyPersistedEntry(
                    sessionID: sessionID,
                    directory: record.directory,
                    atEpochMs: record.watchedAtEpochMs,
                    profileID: currentProfileID
                )
            }
        )
        persistence.save(state)
    }

    private func readyIds(for directory: String) -> Set<String> {
        Set(ready.compactMap { id, record in record.directory == directory ? id : nil })
    }

    private func publishReady(directory: String?, handle: DirectoryHandle? = nil, prune: Bool = true) {
        if prune {
            let pruned = pruneExpired()
            for dir in pruned where dir != directory {
                publishReady(directory: dir, prune: false)
            }
        }
        if let directory {
            let target = handle ?? handles[directory]
            let ids = readyIds(for: directory)
            target?.continuations.values.forEach { $0.yield(ids) }
            return
        }
        for (dir, dirHandle) in handles {
            let ids = readyIds(for: dir)
            dirHandle.continuations.values.forEach { $0.yield(ids) }
        }
    }
}

private extension Optional where Wrapped == String {
    var isBusyOrRetry: Bool {
        self == "busy" || self == "retry"
    }
}
