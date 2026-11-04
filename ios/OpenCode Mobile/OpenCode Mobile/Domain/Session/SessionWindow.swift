import Foundation

/// The newest root sessions of a directory: one window of Home's list.
nonisolated struct SessionWindow: Sendable, Equatable {
    /// Unarchived root sessions, in server order (newest first).
    let sessions: [Session]
    /// The server may hold older root sessions than these.
    let hasMore: Bool
    /// Child sessions an older server listed anyway. They only seed ``SessionAncestry``.
    var children: [Session] = []

    /// A window from one list reply asked for `limit` rows. `hasMore` counts the reply before
    /// filtering: a full page may have more behind it, even when some rows were children.
    static func fromReply(_ listed: [Session], limit: Int) -> SessionWindow {
        SessionWindow(
            sessions: listed.filter { $0.parentID == nil && $0.archivedAtEpochMs == nil },
            hasMore: listed.count == limit,
            children: listed.filter { $0.parentID != nil }
        )
    }

    /// The first `limit` unarchived roots of a complete list, newest first.
    static func prefix(of all: [Session], limit: Int) -> SessionWindow {
        let roots = all.filter { $0.parentID == nil && $0.archivedAtEpochMs == nil }
            .sorted { ($0.updatedAtEpochMs ?? 0) > ($1.updatedAtEpochMs ?? 0) }
        return SessionWindow(
            sessions: Array(roots.prefix(limit)),
            hasMore: roots.count > limit,
            children: all.filter { $0.parentID != nil }
        )
    }
}

nonisolated enum HomeSessionPaging {
    /// Root sessions Home asks for at first, and how many more each load more adds.
    static let pageSize = 50
}
