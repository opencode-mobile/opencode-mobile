import Foundation

@MainActor
final class DefaultSessionTodoLiveStore: SessionTodoLiveStore {
    private struct SessionKey: Hashable, Sendable {
        let sessionID: String
        let directory: String
    }

    private final class SessionHandle {
        var todos: [SessionTodo] = []
        var hasCache = false
        var isLive = false
        var continuations: [UUID: AsyncStream<[SessionTodo]>.Continuation] = [:]
        var eventTask: Task<Void, Never>?
        var registration: SyncRegistration?
    }

    private let sessionRepository: any SessionRepository
    private let eventStream: any ForegroundEventStream
    private let worldSignals: any WorldSignals
    private let adapter: SessionDomainAdapter
    private let syncCoordinator: any SyncCoordinator
    private var handles: [SessionKey: SessionHandle] = [:]
    private let observedSessions = DirectorySessionSet()
    private var directoryRegistrations: [String: (registration: SyncRegistration, count: Int)] = [:]

    init(
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        adapter: SessionDomainAdapter = SessionDomainAdapter(),
        worldSignals: any WorldSignals = SilentWorldSignals.shared,
        syncCoordinator: (any SyncCoordinator)? = nil
    ) {
        self.sessionRepository = sessionRepository
        self.eventStream = eventStream
        self.worldSignals = worldSignals
        self.adapter = adapter
        self.syncCoordinator = syncCoordinator ?? DefaultSyncCoordinator(
            connection: eventStream.asLiveConnection(),
            worldSignals: worldSignals,
            eventStream: eventStream
        )
    }

    func observeTodos(sessionID: String, directory: String?) -> AsyncStream<[SessionTodo]> {
        resubscribingLiveStream { [weak self] in
            guard let self else { return AsyncStream { $0.finish() } }
            return self.todoStream(sessionID: sessionID, directory: directory)
        }
    }

    private func todoStream(sessionID: String, directory: String?) -> AsyncStream<[SessionTodo]> {
        let key = SessionKey(sessionID: sessionID, directory: directory ?? "")
        let handle = handles[key] ?? SessionHandle()
        handles[key] = handle
        let projection = ProjectionKey.todos(directory: key.directory)

        return AsyncStream { [weak self] continuation in
            guard let self else {
                continuation.finish()
                return
            }
            let id = UUID()
            handle.continuations[id] = continuation
            continuation.yield(handle.todos)
            if handle.eventTask == nil {
                observedSessions.insert(sessionID, directory: key.directory)
                let sessionRepository = self.sessionRepository
                let eventStream = self.eventStream
                let adapter = self.adapter
                let coordinator = self.syncCoordinator
                let observedSessions = self.observedSessions
                if directoryRegistrations[key.directory] == nil {
                    let registration = coordinator.register(key: projection) { [weak self] in
                        try await Self.loadDirectory(
                            directory: key.directory,
                            sessionRepository: sessionRepository,
                            observedSessions: observedSessions,
                            store: self
                        )
                    }
                    coordinator.visible(projection, true)
                    directoryRegistrations[key.directory] = (registration, 1)
                } else {
                    directoryRegistrations[key.directory]?.count += 1
                }
                handle.registration = directoryRegistrations[key.directory]?.registration
                handle.eventTask = Task.detached { [weak self] in
                    await coordinator.ensureFresh(projection)
                    await withTaskGroup(of: Void.self) { group in
                        group.addTask {
                            for await event in eventStream.events() {
                                if event.type == "todo.updated", event.sessionID == key.sessionID {
                                    if let todos = adapter.todos(fromEventProperties: event.rawProperties) {
                                        await MainActor.run {
                                            self?.replace(todos: todos, key: key, markCache: true)
                                        }
                                    }
                                }
                            }
                        }
                        await group.next()
                        group.cancelAll()
                    }
                }
            }
            continuation.onTermination = { @Sendable _ in
                Task { @MainActor [weak self] in
                    self?.removeContinuation(key: key, id: id)
                }
            }
        }
    }

    func setLive(_ isLive: Bool, sessionID: String, directory: String?) {
        let key = SessionKey(sessionID: sessionID, directory: directory ?? "")
        let handle = handles[key] ?? SessionHandle()
        handles[key] = handle
        let wasLive = handle.isLive
        handle.isLive = isLive
        guard wasLive != isLive else { return }
        guard isLive else { return }
        let projection = ProjectionKey.todos(directory: key.directory)
        let coordinator = syncCoordinator
        coordinator.invalidate(projection)
        Task {
            await coordinator.ensureFresh(projection)
        }
    }

    func invalidate(sessionID: String, directory: String?) {
        let key = SessionKey(sessionID: sessionID, directory: directory ?? "")
        guard handles[key] != nil else { return }
        let projection = ProjectionKey.todos(directory: key.directory)
        let coordinator = syncCoordinator
        coordinator.invalidate(projection)
        Task {
            await coordinator.ensureFresh(projection)
        }
    }

    func clearCache() {
        let stale = Array(handles.values)
        handles.removeAll()
        observedSessions.removeAll()
        let registrations = Array(directoryRegistrations.values)
        directoryRegistrations.removeAll()
        for entry in registrations {
            syncCoordinator.visible(entry.registration.key, false)
            syncCoordinator.unregister(entry.registration)
        }
        for handle in stale {
            handle.eventTask?.cancel()
            handle.registration = nil
            handle.continuations.values.forEach { $0.finish() }
            handle.continuations.removeAll()
        }
    }

    private func removeContinuation(key: SessionKey, id: UUID) {
        guard let handle = handles[key] else { return }
        handle.continuations.removeValue(forKey: id)
        if handle.continuations.isEmpty {
            handle.eventTask?.cancel()
            handle.eventTask = nil
            observedSessions.remove(key.sessionID, directory: key.directory)
            if var entry = directoryRegistrations[key.directory] {
                entry.count -= 1
                if entry.count <= 0 {
                    syncCoordinator.visible(entry.registration.key, false)
                    syncCoordinator.unregister(entry.registration)
                    directoryRegistrations.removeValue(forKey: key.directory)
                } else {
                    directoryRegistrations[key.directory] = entry
                }
            }
            handle.registration = nil
        }
    }

    private nonisolated static func loadDirectory(
        directory: String,
        sessionRepository: any SessionRepository,
        observedSessions: DirectorySessionSet,
        store: DefaultSessionTodoLiveStore?
    ) async throws {
        let sessionIDs = observedSessions.sessions(in: directory)
        var failed = false
        for sessionID in sessionIDs {
            let resolvedDirectory = directory.isEmpty ? nil : directory
            guard let todos = try? await sessionRepository.listSessionTodos(
                id: sessionID,
                directory: resolvedDirectory
            ) else {
                failed = true
                continue
            }
            await MainActor.run {
                store?.replace(
                    todos: todos,
                    key: SessionKey(sessionID: sessionID, directory: directory),
                    markCache: true
                )
            }
        }
        if failed {
            throw URLError(.cannotConnectToHost)
        }
    }

    private func replace(todos: [SessionTodo], key: SessionKey, markCache: Bool) {
        let handle = handles[key] ?? SessionHandle()
        handles[key] = handle
        handle.todos = todos
        handle.hasCache = markCache
        for continuation in handle.continuations.values {
            continuation.yield(todos)
        }
    }
}

private nonisolated final class DirectorySessionSet: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: Set<String>] = [:]

    func insert(_ sessionID: String, directory: String) {
        lock.lock()
        var bucket = values[directory] ?? []
        bucket.insert(sessionID)
        values[directory] = bucket
        lock.unlock()
    }

    func remove(_ sessionID: String, directory: String) {
        lock.lock()
        if var bucket = values[directory] {
            bucket.remove(sessionID)
            values[directory] = bucket.isEmpty ? nil : bucket
        }
        lock.unlock()
    }

    func sessions(in directory: String) -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return Array(values[directory] ?? [])
    }

    func removeAll() {
        lock.lock()
        values.removeAll()
        lock.unlock()
    }
}
