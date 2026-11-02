import Foundation

enum OptimisticSessionCreate {
    static let placeholderSessionID = "__optimistic_create__"
    /// Title of the session being created until the server's arrives. Matches the domain's
    /// untitled-session fallback; both are keyed with Session Chat's localisation (#886).
    static let placeholderTitle = "New Session"

    static func isPlaceholder(_ sessionID: String) -> Bool {
        sessionID == placeholderSessionID
    }
}
