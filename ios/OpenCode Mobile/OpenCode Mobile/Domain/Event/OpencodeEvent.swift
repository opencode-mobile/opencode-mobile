import Foundation

nonisolated struct OpencodeEvent: Sendable, Equatable {
    let type: String
    let sessionID: String?
    let rawProperties: [String: AnySendable]?
    let id: String?

    init(type: String, sessionID: String?, rawProperties: [String: AnySendable]? = nil, id: String? = nil) {
        self.type = type
        self.sessionID = sessionID
        self.rawProperties = rawProperties
        self.id = id
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

    var namedProjectID: String? {
        let properties = rawProperties
        let named = properties?["projectID"]?.stringValue ?? properties?["projectId"]?.stringValue
        let trimmed = named?.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed?.isEmpty == false ? trimmed : nil
    }

    /// v2: the server-wide viewed watermark moved (`{ sessionID, idle }`).
    static let sessionViewedType = "session.viewed"

    private static let messageRefreshTypes: Set<String> = [
        "message.updated",
        "message.part.updated",
        "message.part.delta",
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
        // v2 archive state changes; the list refetch drops or restores the session.
        "session.archived",
        "session.unarchived",
    ]

    private static let statusRefreshTypes: Set<String> = [
        "session.status",
        "session.idle",
    ]
}

/// A type-erased value that can be sent across concurrency boundaries.
nonisolated enum AnySendable: Sendable, Equatable {
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

    var boolValue: Bool? {
        if case let .bool(value) = self { value }
        else { nil }
    }

    var int64Value: Int64? {
        switch self {
        case let .int(value): Int64(value)
        case let .int64(value): value
        case let .double(value): Int64(value)
        case let .string(value): Int64(value)
        default: nil
        }
    }
}
