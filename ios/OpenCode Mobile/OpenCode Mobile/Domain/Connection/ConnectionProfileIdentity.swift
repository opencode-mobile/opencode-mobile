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

    static func normalizedAddressList(_ urls: [String]) -> [String] {
        var seen = Set<String>()
        var ordered: [String] = []
        for raw in urls {
            let normalized = normalizeBaseURL(raw)
            guard !normalized.isEmpty, seen.insert(normalized).inserted else { continue }
            ordered.append(normalized)
        }
        return ordered
    }

    static func savedAddressSet(_ profile: ConnectionProfile) -> Set<String> {
        var set = Set(normalizedAddressList(profile.serverURLs))
        let base = normalizeBaseURL(profile.baseURL)
        if !base.isEmpty {
            set.insert(base)
        }
        return set
    }

    /// Manual profiles compare base URL and username. A QR profile matches when the
    /// username matches and any saved address overlaps.
    static func sameSavedServer(_ left: ConnectionProfile, _ right: ConnectionProfile) -> Bool {
        guard normalizeUsername(left.username) == normalizeUsername(right.username) else { return false }
        if left.serverURLs.isEmpty && right.serverURLs.isEmpty {
            return normalizeBaseURL(left.baseURL) == normalizeBaseURL(right.baseURL)
        }
        return !savedAddressSet(left).isDisjoint(with: savedAddressSet(right))
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
        rightPassword: String,
        leftProfileID: String = "",
        rightProfileID: String = "",
        leftServerURLs: [String] = [],
        rightServerURLs: [String] = []
    ) -> Bool {
        let fingerprintsMatch = credentialFingerprint(username: leftUsername, password: leftPassword)
            == credentialFingerprint(username: rightUsername, password: rightPassword)
        guard fingerprintsMatch else { return false }
        if !leftProfileID.isEmpty, leftProfileID == rightProfileID {
            let pool = Set((leftServerURLs + rightServerURLs).map(normalizeBaseURL).filter { !$0.isEmpty })
            let left = normalizeBaseURL(leftURL)
            let right = normalizeBaseURL(rightURL)
            if !pool.isEmpty, pool.contains(left), pool.contains(right) {
                return true
            }
        }
        return sameConnectionIdentity(
            leftURL: leftURL,
            leftUsername: leftUsername,
            rightURL: rightURL,
            rightUsername: rightUsername
        )
    }
}
