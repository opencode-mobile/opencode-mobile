import Foundation

public nonisolated struct ServerConnection: Sendable {
    public static let defaultUsername = ServerConnectionDefaults.username

    public let baseURL: URL
    public let username: String
    public let password: String?
    public let serverProtocol: ServerProtocol?
    public let profileID: String
    public let serverURLs: [String]

    public var apiBaseURL: URL { baseURL }

    public var capabilities: ServerCapabilities {
        ServerCapabilities.forProtocol(serverProtocol ?? .v1)
    }

    /// `nil` when the profile's address does not parse. There is no default host: a
    /// password must never travel to an address the user did not enter.
    public init?(profile: ConnectionProfile, serverProtocol: ServerProtocol? = nil) {
        let normalized = profile.normalizedBaseURL
        guard let baseURL = URL(string: normalized), baseURL.host != nil else { return nil }
        self.baseURL = baseURL
        let trimmedUsername = profile.username.trimmingCharacters(in: .whitespacesAndNewlines)
        self.username = trimmedUsername.isEmpty ? Self.defaultUsername : trimmedUsername
        self.password = profile.password.isEmpty ? nil : profile.password
        self.serverProtocol = serverProtocol ?? profile.serverProtocol
        self.profileID = profile.id
        self.serverURLs = profile.serverURLs
    }

    public init(
        baseURL: URL,
        username: String = Self.defaultUsername,
        password: String?,
        serverProtocol: ServerProtocol? = nil,
        profileID: String = "",
        serverURLs: [String] = []
    ) {
        self.baseURL = baseURL
        self.username = username
        self.password = password
        self.serverProtocol = serverProtocol
        self.profileID = profileID
        self.serverURLs = serverURLs
    }

    public func hasSameIdentity(as other: ServerConnection) -> Bool {
        ConnectionProfileIdentity.sameLiveIdentity(
            leftURL: baseURL.absoluteString,
            leftUsername: username,
            leftPassword: password ?? "",
            rightURL: other.baseURL.absoluteString,
            rightUsername: other.username,
            rightPassword: other.password ?? "",
            leftProfileID: profileID,
            rightProfileID: other.profileID,
            leftServerURLs: serverURLs,
            rightServerURLs: other.serverURLs
        )
    }

    public func withProtocol(_ serverProtocol: ServerProtocol) -> ServerConnection {
        ServerConnection(
            baseURL: baseURL,
            username: username,
            password: password,
            serverProtocol: serverProtocol,
            profileID: profileID,
            serverURLs: serverURLs
        )
    }

    public func authorizedRequest(
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
