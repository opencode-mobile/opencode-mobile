import Foundation

/// What this build is: the version name, debug or release, and the git commit it was built from
/// when the build stamped it (the Hex Code target's "Stamp git commit" phase writes `HexGitCommit`
/// into Info.plist).
struct HexBuildInfo: Equatable, Sendable {
    static let commitKey = "HexGitCommit"

    let version: String
    let isDebug: Bool
    let commit: String?

    static var current: HexBuildInfo { HexBuildInfo(bundle: .main) }

    init(version: String, isDebug: Bool, commit: String?) {
        self.version = version
        self.isDebug = isDebug
        self.commit = commit
    }

    init(bundle: Bundle) {
        #if DEBUG
        let isDebug = true
        #else
        let isDebug = false
        #endif
        let version = (bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let commit = (bundle.object(forInfoDictionaryKey: Self.commitKey) as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        self.init(
            version: version.flatMap { $0.isEmpty ? nil : $0 } ?? "0.0.0",
            isDebug: isDebug,
            commit: commit.flatMap { $0.isEmpty ? nil : $0 }
        )
    }

    /// `debug` or `release`.
    var buildType: String { isDebug ? "debug" : "release" }

    /// The Version row's value: `0.1.0 (debug)`.
    var versionLabel: String { "\(version) (\(buildType))" }

    /// One line for a bug report: `Hex Code 0.1.0 (debug) db91a888c1ab`.
    var summary: String {
        ["Hex Code", versionLabel, commit].compactMap { $0 }.joined(separator: " ")
    }
}
