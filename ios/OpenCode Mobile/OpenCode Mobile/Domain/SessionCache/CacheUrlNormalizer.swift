import Foundation

nonisolated enum CacheUrlNormalizer {
    static func normalize(_ baseURL: String) -> String {
        let trimmed = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.hasSuffix("/") ? trimmed : trimmed + "/"
    }

    /// Connection identity aligned with MessageSync (`normalizedBaseURL|username`).
    static func connectionId(baseURL: String, username: String) -> String {
        "\(normalize(baseURL).lowercased())|\(username.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())"
    }

    /// Match rows whose connection id was built from this server URL (any username).
    static func connectionIdPrefix(baseURL: String) -> String {
        "\(normalize(baseURL).lowercased())|"
    }

    // Swift-style ID aliases used by some call sites.
    static func connectionID(baseURL: String, username: String) -> String {
        connectionId(baseURL: baseURL, username: username)
    }

    static func connectionIDPrefix(baseURL: String) -> String {
        connectionIdPrefix(baseURL: baseURL)
    }
}
