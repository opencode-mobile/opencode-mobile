import Foundation

nonisolated struct SessionListSnapshot: Sendable, Equatable {
    let sessions: [Session]
    let isFromCache: Bool

    nonisolated init(sessions: [Session], isFromCache: Bool = false) {
        self.sessions = sessions
        self.isFromCache = isFromCache
    }
}
