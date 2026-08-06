import Foundation

struct SessionListSnapshot: Sendable, Equatable {
    let sessions: [Session]
    let isFromCache: Bool

    init(sessions: [Session], isFromCache: Bool = false) {
        self.sessions = sessions
        self.isFromCache = isFromCache
    }
}
