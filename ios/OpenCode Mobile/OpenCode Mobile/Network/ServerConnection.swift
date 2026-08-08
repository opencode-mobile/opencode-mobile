import Foundation

nonisolated struct ServerConnection: Sendable {
    static let defaultUsername = ServerConnectionDefaults.username

    let baseURL: URL
    let username: String
    let password: String?

    var apiBaseURL: URL { baseURL }

    init(profile: ConnectionProfile) {
        let normalized = profile.normalizedBaseURL
        self.baseURL = URL(string: normalized) ?? AppConfig.serverBaseURL
        let trimmedUsername = profile.username.trimmingCharacters(in: .whitespacesAndNewlines)
        self.username = trimmedUsername.isEmpty ? Self.defaultUsername : trimmedUsername
        self.password = profile.password.isEmpty ? nil : profile.password
    }

    init(baseURL: URL, username: String = Self.defaultUsername, password: String?) {
        self.baseURL = baseURL
        self.username = username
        self.password = password
    }

    func authorizedRequest(path: String, method: String = "GET", directory: String? = nil) -> URLRequest {
        let normalizedPath = path.hasPrefix("/") ? String(path.dropFirst()) : path
        let url = apiBaseURL.appendingPathComponent(normalizedPath).appendingDirectoryQuery(directory)
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
