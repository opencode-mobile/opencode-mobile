import CryptoKit
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

    /// SHA-256 of `username + NUL + password`, lowercase hex, first 16 characters.
    /// Never log this value.
    static func credentialFingerprint(username: String, password: String) -> String {
        let material = "\(normalizeUsername(username))\u{0000}\(password)"
        let digest = SHA256.hash(data: Data(material.utf8))
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        return String(hex.prefix(16))
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

    static func sameLiveIdentity(
        leftURL: String,
        leftUsername: String,
        leftPassword: String,
        rightURL: String,
        rightUsername: String,
        rightPassword: String
    ) -> Bool {
        sameConnectionIdentity(
            leftURL: leftURL,
            leftUsername: leftUsername,
            rightURL: rightURL,
            rightUsername: rightUsername
        )
            && credentialFingerprint(username: leftUsername, password: leftPassword)
            == credentialFingerprint(username: rightUsername, password: rightPassword)
    }
}
