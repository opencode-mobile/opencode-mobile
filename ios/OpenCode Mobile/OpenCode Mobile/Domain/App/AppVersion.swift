import Foundation

struct AppVersion: Equatable, Sendable {
    static let fallbackVersion = "1.0"
    static let fallbackBuild = "1"

    let version: String
    let build: String

    static func from(version: String?, build: String?) -> AppVersion {
        AppVersion(
            version: normalized(version, fallback: fallbackVersion),
            build: normalized(build, fallback: fallbackBuild)
        )
    }

    static func current(bundle: Bundle = .main) -> AppVersion {
        from(
            version: bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
            build: bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String
        )
    }

    private static func normalized(_ value: String?, fallback: String) -> String {
        guard let value else { return fallback }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? fallback : trimmed
    }
}
