import Foundation

enum OptimisticSessionCreate {
    static let placeholderSessionID = "__optimistic_create__"

    static func isPlaceholder(_ sessionID: String) -> Bool {
        sessionID == placeholderSessionID
    }
}
