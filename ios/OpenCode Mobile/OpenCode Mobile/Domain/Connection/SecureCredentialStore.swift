import Foundation

protocol SecureCredentialStore: Sendable {
    func readPassword(serverBaseURL: String) -> String?
    /// Throws `SecureCredentialStoreError` when the secure store refused the write. Callers
    /// must not save the profile in that case: a profile without its password is useless,
    /// and a password written anywhere else would defeat the store.
    func writePassword(serverBaseURL: String, password: String) throws
    func deletePassword(serverBaseURL: String)
    func clearAll()
}

enum SecureCredentialStoreError: Error, Equatable, LocalizedError {
    case writeFailed(status: Int32)

    var errorDescription: String? {
        switch self {
        case let .writeFailed(status):
            return "Couldn't store the password securely on this device (Keychain error \(status)). The server was not saved."
        }
    }
}
