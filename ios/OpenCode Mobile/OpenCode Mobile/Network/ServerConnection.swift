import Foundation

nonisolated struct ServerConnection: Sendable {
    static let defaultUsername = ServerConnectionDefaults.username

    let baseURL: URL
    let username: String
    let password: String?
    let serverProtocol: ServerProtocol?

    var apiBaseURL: URL { baseURL }

    var capabilities: ServerCapabilities {
        ServerCapabilities.forProtocol(serverProtocol ?? .v1)
    }

    init(profile: ConnectionProfile, serverProtocol: ServerProtocol? = nil) {
        let normalized = profile.normalizedBaseURL
        self.baseURL = URL(string: normalized) ?? AppConfig.serverBaseURL
        let trimmedUsername = profile.username.trimmingCharacters(in: .whitespacesAndNewlines)
        self.username = trimmedUsername.isEmpty ? Self.defaultUsername : trimmedUsername
        self.password = profile.password.isEmpty ? nil : profile.password
        self.serverProtocol = serverProtocol
    }

    init(
        baseURL: URL,
        username: String = Self.defaultUsername,
        password: String?,
        serverProtocol: ServerProtocol? = nil
    ) {
        self.baseURL = baseURL
        self.username = username
        self.password = password
        self.serverProtocol = serverProtocol
    }

    func hasSameIdentity(as other: ServerConnection) -> Bool {
        baseURL == other.baseURL && username == other.username
    }

    func withProtocol(_ serverProtocol: ServerProtocol) -> ServerConnection {
        ServerConnection(
            baseURL: baseURL,
            username: username,
            password: password,
            serverProtocol: serverProtocol
        )
    }

    func authorizedRequest(
        path: String,
        method: String = "GET",
        directory: String? = nil,
        locationDirectory: String? = nil
    ) -> URLRequest {
        let normalizedPath = path.hasPrefix("/") ? String(path.dropFirst()) : path
        let url = apiBaseURL.appendingPathComponent(normalizedPath)
            .appendingDirectoryQuery(directory)
            .appendingLocationDirectoryQuery(locationDirectory)
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let password, !password.isEmpty {
            let credentials = "\(username):\(password)"
            let encoded = Data(credentials.utf8).base64EncodedString()
            request.setValue("Basic \(encoded)", forHTTPHeaderField: "Authorization")
        }
        return request
    }
}
