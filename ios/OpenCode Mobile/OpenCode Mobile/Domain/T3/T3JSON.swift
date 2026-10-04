import Foundation

nonisolated enum T3JSON {
    static func object(_ value: Any?) -> [String: Any]? {
        value as? [String: Any]
    }

    static func array(_ value: Any?) -> [Any] {
        value as? [Any] ?? []
    }

    static func string(_ value: Any?) -> String? {
        if let string = value as? String {
            let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
        if let number = value as? NSNumber {
            return number.stringValue
        }
        return nil
    }

    static func string(_ object: [String: Any], _ keys: String...) -> String? {
        for key in keys {
            if let value = string(object[key]) { return value }
        }
        return nil
    }

    static func bool(_ value: Any?) -> Bool {
        if let flag = value as? Bool { return flag }
        if let number = value as? NSNumber { return number.boolValue }
        if let string = value as? String {
            switch string.lowercased() {
            case "true", "1", "yes": return true
            default: return false
            }
        }
        return false
    }

    static func int(_ value: Any?) -> Int? {
        if let int = value as? Int { return int }
        if let number = value as? NSNumber { return number.intValue }
        if let string = value as? String { return Int(string) }
        return nil
    }

    static func int64(_ value: Any?) -> Int64? {
        if value == nil || value is NSNull { return nil }
        if let number = value as? NSNumber { return number.int64Value }
        if let string = value as? String { return Int64(string) }
        return nil
    }

    static func epochMs(_ value: Any?) -> Int64? {
        if value == nil || value is NSNull { return nil }
        if let number = value as? NSNumber {
            let raw = number.doubleValue
            if raw > 1_000_000_000_000 { return Int64(raw) }
            if raw > 1_000_000_000 { return Int64(raw * 1_000) }
            return Int64(raw)
        }
        guard let string = value as? String, !string.isEmpty else { return nil }
        if let millis = Int64(string), millis > 1_000_000_000_000 { return millis }
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = fractional.date(from: string) {
            return Int64(date.timeIntervalSince1970 * 1_000)
        }
        let whole = ISO8601DateFormatter()
        whole.formatOptions = [.withInternetDateTime]
        if let date = whole.date(from: string) {
            return Int64(date.timeIntervalSince1970 * 1_000)
        }
        return nil
    }
}

/// Errors from a T3 host. `errorDescription` is always user-readable and never
/// echoes the server body; the raw body stays on the case for diagnostics.
nonisolated enum T3ClientError: Error, Equatable, LocalizedError {
    case transport(String)
    /// Non-2xx reply other than 401/403. `message` is the raw server body (diagnostics only).
    case http(statusCode: Int, message: String?)
    /// 401/403 from the T3 host: the stored access token no longer works and the user must pair again.
    case accessRevoked(statusCode: Int, body: String?)
    case decoding(String)
    case unsupportedProtocol(Int)
    case missingAccessToken
    case notConnected
    case rpc(String)

    var errorDescription: String? {
        switch self {
        case let .transport(message):
            message
        case let .http(statusCode, _):
            "T3 request failed (\(statusCode))."
        case .accessRevoked:
            Self.accessRevokedMessage
        case let .decoding(message):
            message
        case let .unsupportedProtocol(version):
            "This T3 environment uses orchestration protocol \(version). OpenCode Mobile needs protocol 2."
        case .missingAccessToken:
            "The T3 environment did not return an access token."
        case .notConnected:
            "No T3 environment is connected."
        case let .rpc(message):
            message
        }
    }

    static let accessRevokedMessage = "T3 access was revoked or expired. Re-pair this environment."

    /// Raw server body for logs; never user-facing.
    var diagnosticBody: String? {
        switch self {
        case let .http(_, message): message
        case let .accessRevoked(_, body): body
        default: nil
        }
    }

    /// Maps a non-2xx status to the dedicated 401/403 case or a plain `.http`.
    static func fromStatus(_ statusCode: Int, body: String?) -> T3ClientError {
        if statusCode == 401 || statusCode == 403 {
            return .accessRevoked(statusCode: statusCode, body: body)
        }
        return .http(statusCode: statusCode, message: body)
    }
}

nonisolated enum T3Auth {
    static let username = "t3"
    static let grantType = "urn:ietf:params:oauth:grant-type:token-exchange"
    static let subjectTokenType = "urn:t3:params:oauth:token-type:environment-bootstrap"
    static let requestedTokenType = "urn:ietf:params:oauth:token-type:access_token"
    static let clientLabel = "OpenCode Mobile"
    static let clientDeviceType = "mobile"
    static let protocolHeader = "x-t3-orchestration-protocol"
    static let protocolVersion = "2"

    static func tokenExchangeBody(pairingToken: String) -> String {
        let fields: [(String, String)] = [
            ("grant_type", grantType),
            ("subject_token", pairingToken),
            ("subject_token_type", subjectTokenType),
            ("requested_token_type", requestedTokenType),
            ("client_label", clientLabel),
            ("client_device_type", clientDeviceType),
        ]
        return fields
            .map { key, value in "\(formEncode(key))=\(formEncode(value))" }
            .joined(separator: "&")
    }

    static func formEncode(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }
}

nonisolated enum T3RuntimeModeLabel {
    static func displayName(_ raw: String?) -> String? {
        guard let raw, !raw.isEmpty else { return nil }
        return switch raw {
        case "approval-required": "Supervised"
        case "auto-accept-edits": "Auto-accept edits"
        case "auto": "Auto"
        case "full-access": "Full access"
        default: raw
        }
    }
}

struct T3EnvironmentDescriptor: Equatable, Sendable {
    var environmentID: String
    var label: String
    var orchestrationProtocolVersion: Int?
}

struct T3ModelSelection: Equatable, Sendable {
    var instanceID: String
    var model: String
}

struct T3Thread: Equatable, Sendable {
    var id: String
    var title: String
    var projectID: String?
    var status: String
    var archivedAt: String?
    var settledAt: String?
    var settledOverride: String?
    var deletedAt: String?
    var activeRunID: String?
    var activityRunStatus: String?
    var providerInstanceID: String?
    var runtimeMode: String?
    var interactionMode: String?
    var modelSelection: T3ModelSelection?
    var worktreePath: String?
    var updatedAtEpochMs: Int64?
    var createdAtEpochMs: Int64?
    var pendingRequestID: String? = nil
    var pendingRequestKind: String? = nil

    var isBusy: Bool {
        if activeRunID != nil { return true }
        switch activityRunStatus?.lowercased() {
        case "preparing", "starting", "running", "waiting":
            return true
        default:
            break
        }
        let normalized = status.lowercased()
        if normalized.isEmpty || normalized == "idle" { return false }
        return !["completed", "interrupted", "failed", "cancelled", "rolled_back"].contains(normalized)
    }

    var isActiveListItem: Bool {
        if deletedAt != nil { return false }
        if archivedAt != nil { return false }
        if settledAt != nil {
            return settledOverride == "active"
        }
        return true
    }
}

struct T3Project: Equatable, Sendable {
    var id: String
    var title: String
    var workspaceRoot: String?
    var defaultModelSelection: T3ModelSelection?
}

struct T3ShellSnapshot: Equatable, Sendable {
    var sequence: Int
    var threads: [T3Thread]
    var projects: [T3Project]

    var activeThreads: [T3Thread] {
        threads.filter(\.isActiveListItem)
    }

    func thread(id: String) -> T3Thread? {
        threads.first { $0.id == id }
    }
}

struct T3Attachment: Equatable, Sendable {
    var id: String
    var type: String
    var name: String
    var mimeType: String
    var sizeBytes: Int64
}

struct T3ConversationMessage: Equatable, Sendable {
    var id: String
    var role: String
    var text: String
    var streaming: Bool
    var createdAtEpochMs: Int64?
    var attachments: [T3Attachment] = []
}

struct T3TurnItem: Equatable, Sendable {
    var id: String
    var ordinal: Int = 0
    var type: String
    var status: String? = nil
    var title: String? = nil
    var startedAtEpochMs: Int64? = nil
    var completedAtEpochMs: Int64? = nil
    var messageID: String? = nil
    var text: String = ""
    var streaming: Bool = false
    var attachments: [T3Attachment] = []
    var toolName: String? = nil
    var inputText: String? = nil
    var inputFields: [String: String] = [:]
    var outputText: String? = nil
    var exitCode: Int? = nil
    var pattern: String? = nil
    var patterns: [String] = []
    var fileName: String? = nil
    var prompt: String? = nil
    var progress: String? = nil
    var result: String? = nil
    var childThreadID: String? = nil
}

struct T3Transcript: Equatable, Sendable {
    var messages: [T3ConversationMessage] = []
    var turnItems: [T3TurnItem] = []
}

struct T3ThreadDetail: Equatable, Sendable {
    var sequence: Int
    var thread: T3Thread?
    var messages: [T3ConversationMessage]
    var turnItems: [T3TurnItem] = []
}
