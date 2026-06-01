import Foundation

struct OpencodeEvent: Sendable, Equatable {
    let type: String
    let sessionID: String?
    let rawProperties: [String: AnySendable]?

    init(type: String, sessionID: String?, rawProperties: [String: AnySendable]? = nil) {
        self.type = type
        self.sessionID = sessionID
        self.rawProperties = rawProperties
    }

    var triggersMessageRefresh: Bool {
        Self.messageRefreshTypes.contains(type)
    }

    var triggersHomeRefresh: Bool {
        Self.homeRefreshTypes.contains(type)
    }

    var triggersStatusRefresh: Bool {
        Self.statusRefreshTypes.contains(type)
    }

    var triggersMessagePartUpdate: Bool {
        type == "message.part.updated"
    }

    var triggersMessageSlotUpdate: Bool {
        type == "message.updated"
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

/// A type-erased value that can be sent across concurrency boundaries.
enum AnySendable: Sendable, Equatable {
    case string(String)
    case int(Int)
    case int64(Int64)
    case double(Double)
    case bool(Bool)
    case dictionary([String: AnySendable])
    case array([AnySendable])
    case null

    static func from(_ value: Any) -> AnySendable? {
        switch value {
        case let v as String: .string(v)
        case let v as Int: .int(v)
        case let v as Int64: .int64(v)
        case let v as Double: .double(v)
        case let v as Bool: .bool(v)
        case let v as [String: Any]: .dictionary(v.mapValues { AnySendable.from($0) ?? .null })
        case let v as [Any]: .array(v.compactMap { AnySendable.from($0) })
        case is NSNull: .null
        default: nil
        }
    }

    var dictionaryValue: [String: AnySendable]? {
        if case let .dictionary(dict) = self { dict }
        else { nil }
    }

    var stringValue: String? {
        if case let .string(s) = self { s }
        else { nil }
    }
}
