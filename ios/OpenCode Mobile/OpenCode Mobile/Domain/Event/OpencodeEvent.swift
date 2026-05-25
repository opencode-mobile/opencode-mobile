import Foundation

struct OpencodeEvent: Sendable, Equatable {
    let type: String
    let sessionID: String?

    var triggersMessageRefresh: Bool {
        Self.messageRefreshTypes.contains(type)
    }

    var triggersHomeRefresh: Bool {
        Self.homeRefreshTypes.contains(type)
    }

    var triggersStatusRefresh: Bool {
        Self.statusRefreshTypes.contains(type)
    }

    private static let messageRefreshTypes: Set<String> = [
        "message.updated",
        "message.part.updated",
        "message.part.removed",
        "message.removed",
        "session.status",
        "session.idle",
        "session.error",
    ]

    private static let homeRefreshTypes: Set<String> = [
        "session.created",
        "session.updated",
        "session.deleted",
    ]

    private static let statusRefreshTypes: Set<String> = [
        "session.status",
        "session.idle",
    ]
}
