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

    func writePassword(serverBaseURL: String, password: String) throws {
        let account = account(for: serverBaseURL)
        deletePassword(serverBaseURL: serverBaseURL)

        var query = baseQuery(account: account)
        query[kSecValueData as String] = Data(password.utf8)
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(query as CFDictionary, nil)
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
