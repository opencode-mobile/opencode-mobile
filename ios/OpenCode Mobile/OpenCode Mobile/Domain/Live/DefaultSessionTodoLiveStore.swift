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
        let authority = GapAuthority()
    }

    private let sessionRepository: any SessionRepository
    private let eventStream: any ForegroundEventStream
    private let worldSignals: any WorldSignals
    private let adapter: SessionDomainAdapter
    private var handles: [SessionKey: SessionHandle] = [:]

    init(
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        adapter: SessionDomainAdapter = SessionDomainAdapter(),
        worldSignals: any WorldSignals = SilentWorldSignals.shared
    ) {
        self.sessionRepository = sessionRepository
        self.eventStream = eventStream
        self.worldSignals = worldSignals
        self.adapter = adapter
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

        return AsyncStream { [weak self] continuation in
            guard let self else {
                continuation.finish()
                return
            }
            let id = UUID()
            handle.continuations[id] = continuation
            continuation.yield(handle.todos)
            if handle.eventTask == nil {
                let sessionRepository = self.sessionRepository
                let eventStream = self.eventStream
                let worldSignals = self.worldSignals
                let adapter = self.adapter
                let authority = handle.authority
                handle.eventTask = Task.detached { [weak self] in
                    let store = self
                    let liveConnection = eventStream.asLiveConnection()
                    let needsBootstrap = await MainActor.run {
                        store?.handles[key]?.hasCache != true
                    }
                    if needsBootstrap {
                        _ = await store?.fetch(
                            key: key,
                            force: true,
                            reason: StoreRefreshReason.initialSubscribe,
                            liveConnection: liveConnection,
                            authority: authority
                        )
                    }
                    await withTaskGroup(of: Void.self) { group in
                        group.addTask {
                            for await signal in worldSignals.signals() {
                                let isObserved = await MainActor.run {
                                    store?.handles[key]?.continuations.isEmpty == false
                                }
                                guard isObserved else { continue }
                                if signal.marksStale {
                                    authority.invalidate(liveConnection.snapshot)
                                    continue
                                }
                                guard let reason = signal.catchUpReason else { continue }
                                _ = await store?.fetch(
                                    key: key,
                                    force: false,
                                    reason: reason,
                                    liveConnection: liveConnection,
                                    authority: authority
                                )
                            }
                        }
                        group.addTask {
                            for await _ in liveConnection.snapshotUpdates() {
                                let isObserved = await MainActor.run {
                                    store?.handles[key]?.continuations.isEmpty == false
                                }
                                guard isObserved else { continue }
                                _ = await store?.fetch(
                                    key: key,
                                    force: false,
                                    reason: StoreRefreshReason.streamReconnected,
                                    liveConnection: liveConnection,
                                    authority: authority
                                )
                            }
                        }
                        group.addTask {
                            var reconnectTask: Task<Void, Never>?
                            defer { reconnectTask?.cancel() }
                            for await event in eventStream.events() {
                                if event.type == "todo.updated", event.sessionID == key.sessionID {
                                    if let todos = adapter.todos(fromEventProperties: event.rawProperties) {
                                        await MainActor.run {
                                            store?.replace(todos: todos, key: key, markCache: true)
                                        }
                                    }
                                    continue
                                }
                                if event.type == "stream.reconnected" || event.type == "server.connected" {
                                    reconnectTask?.cancel()
                                    let reason = event.type
                                    reconnectTask = Task {
                                        try? await Task.sleep(
                                            for: .milliseconds(LiveStoreRefreshPolicy.statusDebounceMs)
                                        )
                                        guard !Task.isCancelled else { return }
                                        let isObserved = await MainActor.run {
                                            store?.handles[key]?.continuations.isEmpty == false
                                        }
                                        guard isObserved else { return }
                                        _ = await store?.fetch(
                                            key: key,
                                            force: false,
                                            reason: reason,
                                            liveConnection: liveConnection,
                                            authority: authority
                                        )
                                    }
                                }
                            }
                        }
                        await group.next()
                        group.cancelAll()
                    }
                    _ = sessionRepository
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
        Task {
            await self.fetch(key: key, force: true)
        }
    }

    func invalidate(sessionID: String, directory: String?) {
        let key = SessionKey(sessionID: sessionID, directory: directory ?? "")
        guard handles[key] != nil else { return }
        Task {
            await self.fetch(key: key, force: true, reason: StoreRefreshReason.pullToRefresh)
        }
    }

    func clearCache() {
        let stale = Array(handles.values)
        handles.removeAll()
        for handle in stale {
            handle.eventTask?.cancel()
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
        }
    }

    private func fetch(
        key: SessionKey,
        force: Bool,
        reason: String? = nil,
        liveConnection: (any LiveConnection)? = nil,
        authority: GapAuthority? = nil
    ) async -> Bool {
        guard let handle = handles[key] else { return false }
        let connection = liveConnection ?? eventStream.asLiveConnection()
        let gap = authority ?? handle.authority
        let snapshot = connection.snapshot
        if !force {
            guard canCatchUp(occupancy: worldSignals.occupancy, snapshot: snapshot) else {
                return handle.hasCache
            }
        }
        let generation: UInt64
        if force {
            generation = gap.captureBegin(snapshot)
        } else {
            guard let started = gap.tryBegin(snapshot) else { return handle.hasCache }
            generation = started
        }
        let directory = key.directory.isEmpty ? nil : key.directory
        guard let todos = try? await sessionRepository.listSessionTodos(
            id: key.sessionID,
            directory: directory
        ) else {
            gap.fail(generation)
            return false
        }
        if let reason {
            DiagnosticLog.info(
                .session,
                "sessionTodoLiveStore fetch session=\(key.sessionID) reason=\(reason)"
            )
        }
        replace(todos: todos, key: key, markCache: true)
        gap.acknowledge(generation)
        if gap.needsReconcile(connection.snapshot) {
            return await fetch(
                key: key,
                force: false,
                reason: StoreRefreshReason.streamReconnected,
                liveConnection: connection,
                authority: gap
            )
        }
        return true
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
