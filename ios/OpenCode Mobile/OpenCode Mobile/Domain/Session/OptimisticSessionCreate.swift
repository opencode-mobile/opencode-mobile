import Foundation

enum OptimisticSessionCreate {
    static let placeholderSessionID = "__optimistic_create__"
    /// Title of the session being created until the server's arrives, and the domain's
    /// fallback for a session without a title. Resolved from the String Catalog when read.
    nonisolated static var placeholderTitle: String {
        String(localized: "session.new_title", defaultValue: "New Session")
    }

    static func isPlaceholder(_ sessionID: String) -> Bool {
        sessionID == placeholderSessionID
    }
}
