import Foundation

@MainActor
protocol SessionReadyStore: AnyObject, Sendable {
    func observeReadyIds(directory: String) -> AsyncStream<Set<String>>
    func notifyPromptSent(sessionID: String, directory: String)
    func setFocusedSession(sessionID: String?, directory: String?)
    /// Clears focus only while `sessionID` still owns it. A leaving Session Chat calls this
    /// after the next screen (child, parent, fork) may already have focused itself.
    func releaseFocusedSession(sessionID: String)
    func bindProfile(profileID: String?)
    func clearCache()
}

@MainActor
final class EmptySessionReadyStore: SessionReadyStore {
    func observeReadyIds(directory: String) -> AsyncStream<Set<String>> {
        AsyncStream { continuation in
            continuation.yield([])
            continuation.finish()
        }
    }

    func notifyPromptSent(sessionID: String, directory: String) {}

    func setFocusedSession(sessionID: String?, directory: String?) {}

    func releaseFocusedSession(sessionID: String) {}

    func bindProfile(profileID: String?) {}

    func clearCache() {}
}
