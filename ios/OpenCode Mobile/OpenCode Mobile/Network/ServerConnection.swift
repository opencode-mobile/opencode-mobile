import Foundation

struct ServerConnection: Sendable {
    static let defaultUsername = "opencode"

    let baseURL: URL
    let username: String
    let password: String?

    var apiBaseURL: URL {
        baseURL
    }

    static func fromAppConfig() -> ServerConnection {
        let password = ProcessInfo.processInfo.environment["OPENCODE_SERVER_PASSWORD"]
        return ServerConnection(
            baseURL: AppConfig.serverBaseURL,
            username: Self.defaultUsername,
            password: password?.isEmpty == false ? password : nil
        )
    }

    func authorizedRequest(path: String, method: String = "GET") -> URLRequest {
        let normalizedPath = path.hasPrefix("/") ? String(path.dropFirst()) : path
        var request = URLRequest(url: apiBaseURL.appendingPathComponent(normalizedPath))
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
