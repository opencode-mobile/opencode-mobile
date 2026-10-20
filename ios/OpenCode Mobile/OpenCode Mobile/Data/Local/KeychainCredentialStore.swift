import Foundation
import Security

struct KeychainCredentialStore: SecureCredentialStore, Sendable {
    private let service = "dev.opencode.mobile.connection"

    func readPassword(serverBaseURL: String) -> String? {
        var query = baseQuery(account: account(for: serverBaseURL))
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// Updates the item in place, or adds it when missing, so a failed write never removes the
    /// password that was already stored.
    func writePassword(serverBaseURL: String, password: String) throws {
        let query = baseQuery(account: account(for: serverBaseURL))
        let attributes: [String: Any] = [
            kSecValueData as String: Data(password.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            let addQuery = query.merging(attributes) { _, new in new }
            status = SecItemAdd(addQuery as CFDictionary, nil)
        }
        guard status == errSecSuccess else {
            DiagnosticLog.warning(.connection, "keychain write failed status=\(status)")
            throw SecureCredentialStoreError.writeFailed(status: status)
        }
    }

    func deletePassword(serverBaseURL: String) {
        let query = baseQuery(account: account(for: serverBaseURL))
        SecItemDelete(query as CFDictionary)
    }

    func clearAll() {
        SecItemDelete(serviceQuery() as CFDictionary)
    }

    private func account(for serverBaseURL: String) -> String {
        "connection.password.\(serverBaseURL)"
    }

    private func serviceQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]
    }

    private func baseQuery(account: String) -> [String: Any] {
        var query = serviceQuery()
        query[kSecAttrAccount as String] = account
        return query
    }
}
