import Foundation
import OSLog

@MainActor
final class DefaultSessionReadyStore: SessionReadyStore {
    private final class DirectoryHandle {
        var continuations: [UUID: AsyncStream<Set<String>>.Continuation] = [:]
        var statusTask: Task<Void, Never>?
        var previousTypes: [String: String?] = [:]
    }

    private let sessionStatusLiveStore: any SessionStatusLiveStore
    private let eventStream: any ForegroundEventStream
    private let logger = Logger(subsystem: "dev.opencode.mobile", category: "SessionReadyStore")

    private var watched: [String: String] = [:]
    private var ready: [String: String] = [:]
    private var focusedSessionID: String?
    private var focusedDirectory: String?
    private var handles: [String: DirectoryHandle] = [:]
    private var eventTask: Task<Void, Never>?

    init(
        sessionStatusLiveStore: any SessionStatusLiveStore,
        eventStream: any ForegroundEventStream
    ) {
        self.sessionStatusLiveStore = sessionStatusLiveStore
        self.eventStream = eventStream
        eventTask = Task { [weak self] in
            guard let self else { return }
            for await event in self.eventStream.events() {
                self.onEvent(type: event.type, sessionID: event.sessionID)
            }
        }
    }

    func observeReadyIds(directory: String) -> AsyncStream<Set<String>> {
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
        watched[sessionID] = directory
        logger.info("watch started reason=prompt session=\(sessionID, privacy: .public) directory=\(directory, privacy: .public)")
        let handle = handles[directory] ?? DirectoryHandle()
        handles[directory] = handle
        ensureStatusObservation(directory: directory, handle: handle)
    }

    func setFocusedSession(sessionID: String?, directory: String?) {
        if sessionID == nil {
            if let priorID = focusedSessionID, let priorDirectory = focusedDirectory {
                let type = handles[priorDirectory]?.previousTypes[priorID] ?? nil
                if type.isBusyOrRetry, watched[priorID] != priorDirectory {
                    watched[priorID] = priorDirectory
                    logger.info(
                        "watch started reason=leave-while-busy session=\(priorID, privacy: .public) directory=\(priorDirectory, privacy: .public)"
                    )
                }
            }
            focusedSessionID = nil
            focusedDirectory = nil
            return
        }

        guard let sessionID else { return }
        focusedSessionID = sessionID
        focusedDirectory = directory
        if let readyDirectory = ready.removeValue(forKey: sessionID) {
            logger.info("ready cleared on focus session=\(sessionID, privacy: .public)")
            publishReady(directory: readyDirectory)
        }
        if let directory {
            let handle = handles[directory] ?? DirectoryHandle()
            handles[directory] = handle
            ensureStatusObservation(directory: directory, handle: handle)
        }
    }

    func clearCache() {
        watched.removeAll()
        ready.removeAll()
        focusedSessionID = nil
        focusedDirectory = nil
        for (directory, handle) in handles {
            handle.previousTypes.removeAll()
            publishReady(directory: directory, handle: handle)
        }
        logger.info("clearCache")
    }

    private func onEvent(type: String, sessionID: String?) {
        guard let sessionID else { return }
        switch type {
        case "session.status":
            guard sessionID == focusedSessionID, let directory = focusedDirectory else { return }
            if watched[sessionID] != directory {
                watched[sessionID] = directory
                logger.info(
                    "watch started reason=focused-status-event session=\(sessionID, privacy: .public) directory=\(directory, privacy: .public)"
                )
            }
            let handle = handles[directory] ?? DirectoryHandle()
            handles[directory] = handle
            ensureStatusObservation(directory: directory, handle: handle)
        case "session.idle":
            guard let directory = watched[sessionID] else { return }
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
        guard let handle = handles[directory] else { return }
        let sessionIDs = Set(handle.previousTypes.keys).union(statuses.keys)
        var readyChanged = false

        for sessionID in sessionIDs {
            let type = statuses[sessionID]?.raw["type"]
            let prev = handle.previousTypes[sessionID] ?? nil
            let isBusy = type.isBusyOrRetry
            let wasBusy = prev.isBusyOrRetry

            if sessionID == focusedSessionID,
               directory == focusedDirectory,
               isBusy
            {
                if watched[sessionID] != directory {
                    watched[sessionID] = directory
                    logger.info(
                        "watch started reason=focused-busy session=\(sessionID, privacy: .public) directory=\(directory, privacy: .public)"
                    )
                }
            }

            if watched[sessionID] == directory, wasBusy, !isBusy {
                if handleIdle(sessionID: sessionID, directory: directory, markPreviousIdle: false) {
                    readyChanged = true
                }
            }

            if statuses[sessionID] != nil {
                handle.previousTypes[sessionID] = type
            } else {
                handle.previousTypes.removeValue(forKey: sessionID)
            }
        }

        if readyChanged {
            publishReady(directory: directory, handle: handle)
        }
    }

    @discardableResult
    private func handleIdle(sessionID: String, directory: String, markPreviousIdle: Bool) -> Bool {
        guard watched[sessionID] == directory else { return false }
        watched.removeValue(forKey: sessionID)
        if markPreviousIdle {
            let handle = handles[directory] ?? DirectoryHandle()
            handles[directory] = handle
            handle.previousTypes[sessionID] = "idle"
        }
        if sessionID == focusedSessionID {
            logger.info(
                "idle handled outcome=suppressed-open session=\(sessionID, privacy: .public) directory=\(directory, privacy: .public)"
            )
            return false
        }
        ready[sessionID] = directory
        logger.info(
            "idle handled outcome=ready session=\(sessionID, privacy: .public) directory=\(directory, privacy: .public)"
        )
        publishReady(directory: directory)
        return true
    }

    private func readyIds(for directory: String) -> Set<String> {
        Set(ready.compactMap { id, dir in dir == directory ? id : nil })
    }

    private func publishReady(directory: String?, handle: DirectoryHandle? = nil) {
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
