import Foundation

nonisolated struct ConnectionProfile: Equatable, Sendable {
    var baseURL: String
    var username: String
    var password: String
    var serverProtocol: ServerProtocol?
    var id: String = ""
    var name: String = ""

    var displayName: String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? ConnectionProfileIdentity.hostLabel(baseURL) : trimmed
    }

    static let defaultBaseURL = "http://127.0.0.1:4096/"
    static let defaultUsername = "opencode"
}

nonisolated enum ServerConnectionDefaults {
    static let username = ConnectionProfile.defaultUsername
    static let baseURL = ConnectionProfile.defaultBaseURL
}

extension ConnectionProfile {
    /// Empty host for new Connection Profiles — placeholder hint lives in the form UI.
    static var formDefaults: ConnectionProfile {
        ConnectionProfile(baseURL: "", username: defaultUsername, password: "")
    }
}

private func normalizeBaseURL(_ baseURL: String) -> String {
    let trimmed = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed.hasSuffix("/") ? trimmed : trimmed + "/"
}

extension ConnectionProfile {
    var normalizedBaseURL: String { normalizeBaseURL(baseURL) }
}
