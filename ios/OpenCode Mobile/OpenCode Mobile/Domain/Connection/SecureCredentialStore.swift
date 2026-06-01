import Foundation

protocol SecureCredentialStore: Sendable {
    func readPassword(serverBaseURL: String) -> String?
    func writePassword(serverBaseURL: String, password: String)
    func deletePassword(serverBaseURL: String)
    func clearAll()
}
