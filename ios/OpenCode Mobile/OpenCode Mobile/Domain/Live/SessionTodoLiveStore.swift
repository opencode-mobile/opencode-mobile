import Foundation

@MainActor
protocol SessionTodoLiveStore: AnyObject, Sendable {
    func observeTodos(sessionID: String, directory: String?) -> AsyncStream<[SessionTodo]>
    /// Drive live lifecycle: GET (force if cached) when becoming live; clear when idle.
    func setLive(_ isLive: Bool, sessionID: String, directory: String?)
    func clearCache()
}

@MainActor
final class EmptySessionTodoLiveStore: SessionTodoLiveStore {
    func observeTodos(sessionID: String, directory: String?) -> AsyncStream<[SessionTodo]> {
        AsyncStream { continuation in
            continuation.yield([])
            continuation.finish()
        }
    }

    func setLive(_ isLive: Bool, sessionID: String, directory: String?) {}
    func clearCache() {}
}
