import Foundation

public nonisolated struct ConnectionProfile: Equatable, Sendable {
    public var baseURL: String
    /// Every non-loopback address from a QR. Empty for a typed connection.
    public var serverURLs: [String] = []
    public var username: String
    public var password: String
    public var serverProtocol: ServerProtocol?
    public var id: String = ""
    public var name: String = ""

    public var displayName: String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? ConnectionProfileIdentity.hostLabel(baseURL) : trimmed
    }

    public static let defaultBaseURL = "http://127.0.0.1:4096/"
    public static let defaultUsername = "opencode"

    public init(
        baseURL: String,
        serverURLs: [String] = [],
        username: String,
        password: String,
        serverProtocol: ServerProtocol? = nil,
        id: String = "",
        name: String = ""
    ) {
        self.baseURL = baseURL
        self.serverURLs = serverURLs
        self.username = username
        self.password = password
        self.serverProtocol = serverProtocol
        self.id = id
        self.name = name
    }
}

public nonisolated enum ServerConnectionDefaults {
    public static let username = ConnectionProfile.defaultUsername
    public static let baseURL = ConnectionProfile.defaultBaseURL
}

extension ConnectionProfile {
    /// Empty host for new Connection Profiles — placeholder hint lives in the form UI.
    public static var formDefaults: ConnectionProfile {
        ConnectionProfile(baseURL: "", username: defaultUsername, password: "")
    }
}

private func normalizeBaseURL(_ baseURL: String) -> String {
    let trimmed = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed.hasSuffix("/") ? trimmed : trimmed + "/"
}

extension ConnectionProfile {
    public var normalizedBaseURL: String { normalizeBaseURL(baseURL) }
}
