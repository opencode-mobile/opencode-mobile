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

    /// A run ended (v1 native `session.idle`; v2 projects `session.execution.*` onto it).
    var isSessionRunEnd: Bool {
        type == "session.idle"
    }

    var triggersMessagePartUpdate: Bool {
        type == "message.part.updated"
    }

    var triggersMessageSlotUpdate: Bool {
        type == "message.updated"
    }

    /// The user's own Stop/Deny (`error.type: "aborted"` / `MessageAbortedError`): the
    /// timeline shows it neutrally, so it is not an error toast.
    var isUserAbortSessionError: Bool {
        guard type == "session.error" else { return false }
        let error = rawProperties?["error"]?.dictionaryValue
        return ToolInterruption.isUserAbortError(
            type: error?["type"]?.stringValue,
            name: error?["name"]?.stringValue
        )
    }

    var sessionErrorMessage: String? {
        guard type == "session.error" else { return nil }
        let error = rawProperties?["error"]?.dictionaryValue
        let data = error?["data"]?.dictionaryValue
        let candidates = [
            data?["message"]?.stringValue,
            error?["message"]?.stringValue,
            rawProperties?["message"]?.stringValue,
            error?["name"]?.stringValue,
            error?["type"]?.stringValue,
        ]
        if let message = candidates.compactMap({ $0 }).first(where: { !$0.isEmpty }) {
            return message
        }
        return "Session work failed"
    }

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
