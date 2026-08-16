import Foundation

enum ConnectionProfileIdentity {
    static let fallbackName = "Server"

    static func normalizeBaseURL(_ baseURL: String) -> String {
        let trimmed = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return trimmed }
        return trimmed.hasSuffix("/") ? trimmed : trimmed + "/"
    }

    static func normalizeUsername(_ username: String) -> String {
        let trimmed = username.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? ServerConnectionDefaults.username : trimmed
    }

    static func hostLabel(_ baseURL: String) -> String {
        guard let url = URL(string: baseURL.trimmingCharacters(in: .whitespacesAndNewlines)),
              let host = url.host, !host.isEmpty
        else {
            return fallbackName
        }
        if let port = url.port {
            return "\(host):\(port)"
        }
        return host
    }

    static func sameConnectionIdentity(
        leftURL: String,
        leftUsername: String,
        rightURL: String,
        rightUsername: String
    ) -> Bool {
        normalizeBaseURL(leftURL) == normalizeBaseURL(rightURL)
            && normalizeUsername(leftUsername) == normalizeUsername(rightUsername)
    }
}
