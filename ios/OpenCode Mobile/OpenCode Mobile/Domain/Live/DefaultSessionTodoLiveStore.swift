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
    }

    private let sessionRepository: any SessionRepository
    private let eventStream: any ForegroundEventStream
    private let adapter: SessionDomainAdapter
    private var handles: [SessionKey: SessionHandle] = [:]

    init(
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        adapter: SessionDomainAdapter = SessionDomainAdapter()
    ) {
        self.sessionRepository = sessionRepository
        self.eventStream = eventStream
        self.adapter = adapter
    }

    func observeTodos(sessionID: String, directory: String?) -> AsyncStream<[SessionTodo]> {
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
                let adapter = self.adapter
                handle.eventTask = Task.detached { [weak self] in
                    let store = self
                    for await event in eventStream.events() {
                        if event.type == "todo.updated", event.sessionID == key.sessionID {
                            let isLive = await MainActor.run { store?.handles[key]?.isLive == true }
                            guard isLive else { continue }
                            if let todos = adapter.todos(fromEventProperties: event.rawProperties) {
                                await MainActor.run {
                                    store?.replace(todos: todos, key: key, markCache: true)
                                }
                            }
                            continue
                        }
                        if event.type == "stream.reconnected" || event.type == "server.connected" {
                            await store?.fetch(key: key, force: true)
                        }
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
        Task {
            if isLive {
                await self.fetch(key: key, force: handle.hasCache)
            } else {
                self.replace(todos: [], key: key, markCache: false)
            }
        }
    }

    func clearCache() {
        for handle in handles.values {
            handle.eventTask?.cancel()
            for continuation in handle.continuations.values {
                continuation.finish()
            }
        }
        handles.removeAll()
    }

    private func removeContinuation(key: SessionKey, id: UUID) {
        guard let handle = handles[key] else { return }
        handle.continuations.removeValue(forKey: id)
        if handle.continuations.isEmpty {
            handle.eventTask?.cancel()
            handle.eventTask = nil
        }
    }

    private func fetch(key: SessionKey, force: Bool) async {
        guard let handle = handles[key], handle.isLive else { return }
        if handle.hasCache, !force { return }
        let directory = key.directory.isEmpty ? nil : key.directory
        guard let todos = try? await sessionRepository.listSessionTodos(
            id: key.sessionID,
            directory: directory
        ) else { return }
        replace(todos: todos, key: key, markCache: true)
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
