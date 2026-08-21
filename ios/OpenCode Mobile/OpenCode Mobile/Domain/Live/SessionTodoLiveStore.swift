import Foundation

@MainActor
protocol SessionTodoLiveStore: AnyObject, Sendable {
    func observeTodos(sessionID: String, directory: String?) -> AsyncStream<[SessionTodo]>
    /// Force GET when becoming live. Idle keeps the cached list.
    func setLive(_ isLive: Bool, sessionID: String, directory: String?)
    func clearCache()
    func invalidate(sessionID: String, directory: String?)
}

extension SessionTodoLiveStore {
    func invalidate(sessionID: String, directory: String?) {}
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
