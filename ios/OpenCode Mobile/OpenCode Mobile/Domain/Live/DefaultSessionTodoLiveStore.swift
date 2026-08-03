import Foundation

@MainActor
final class DefaultSessionTodoLiveStore: SessionTodoLiveStore {
    private struct SessionKey: Hashable {
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
                handle.eventTask = Task { await self.runEventLoop(key: key) }
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

    private func runEventLoop(key: SessionKey) async {
        for await event in eventStream.events() {
            guard let handle = handles[key] else { return }
            if event.type == "todo.updated", event.sessionID == key.sessionID {
                guard handle.isLive else { continue }
                if let todos = adapter.todos(fromEventProperties: event.rawProperties) {
                    replace(todos: todos, key: key, markCache: true)
                }
                continue
            }
            if handle.isLive,
               event.type == "stream.reconnected" || event.type == "server.connected"
            {
                await fetch(key: key, force: true)
            }
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
