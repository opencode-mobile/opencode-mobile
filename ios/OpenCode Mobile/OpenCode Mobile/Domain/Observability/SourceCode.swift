import Foundation

enum SourceCode {
    /// GitHub `owner/name` slug, shown as-is (never translated).
    static let repository = "opencode-mobile/opencode-mobile"
    static let url = URL(string: "https://github.com/\(repository)")!
}
