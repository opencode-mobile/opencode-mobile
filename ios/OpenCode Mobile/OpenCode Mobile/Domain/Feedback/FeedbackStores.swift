import Foundation
import Security

/// 60 s between successful reports, checked on the phone before any request.
nonisolated protocol FeedbackCooldownStore: Sendable {
    func lastSentAt() -> Date?
    func recordSent(at date: Date)
}

nonisolated enum FeedbackCooldown {
    static let seconds = 60

    /// Whole seconds left before another report may be sent, or nil when none.
    static func remainingSeconds(lastSentAt: Date?, now: Date) -> Int? {
        guard let lastSentAt else { return nil }
        let elapsed = now.timeIntervalSince(lastSentAt)
        guard elapsed >= 0, elapsed < Double(seconds) else { return nil }
        return max(1, Int((Double(seconds) - elapsed).rounded(.up)))
    }
}

nonisolated struct UserDefaultsFeedbackCooldownStore: FeedbackCooldownStore, @unchecked Sendable {
    static let key = "feedback.lastSentAt"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func lastSentAt() -> Date? {
        defaults.object(forKey: Self.key) as? Date
    }

    func recordSent(at date: Date) {
        defaults.set(date, forKey: Self.key)
    }
}

/// Anonymous Firebase user refresh token for the release REST submitter.
nonisolated protocol FeedbackRefreshTokenStore: Sendable {
    func read() -> String?
    func write(_ token: String)
    func delete()
}

nonisolated struct KeychainFeedbackRefreshTokenStore: FeedbackRefreshTokenStore {
    private let service = "dev.opencode.mobile.feedback"
    private let account = "feedback.refreshToken"

    func read() -> String? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func write(_ token: String) {
        delete()
        var query = baseQuery()
        query[kSecValueData as String] = Data(token.utf8)
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(query as CFDictionary, nil)
    }

    func delete() {
        SecItemDelete(baseQuery() as CFDictionary)
    }

    private func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}
