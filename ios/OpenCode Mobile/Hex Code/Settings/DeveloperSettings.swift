import Foundation
import Observation

/// Settings' Developer section, for whoever builds and tests the app, in every build. Tapping the
/// Version row 10 times, each tap within 2 s of the last, turns it on; it stays on, saved on the
/// phone, until Hide developer settings.
@MainActor
@Observable
final class DeveloperSettings {
    static let requiredTaps = 10
    /// The longest gap between two taps that still counts them together.
    static let tapWindow: TimeInterval = 2
    static let key = "hexcode.developerSettings"

    private(set) var isUnlocked: Bool

    @ObservationIgnored private var taps = 0
    @ObservationIgnored private var lastTap: Date?
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let now: () -> Date

    init(defaults: UserDefaults = .standard, now: @escaping () -> Date = Date.init) {
        self.defaults = defaults
        self.now = now
        isUnlocked = defaults.bool(forKey: Self.key)
    }

    /// A tap on the Version row. True on the tap that turns developer settings on.
    @discardableResult
    func tapVersion() -> Bool {
        guard !isUnlocked else { return false }
        let time = now()
        if let lastTap, time.timeIntervalSince(lastTap) <= Self.tapWindow {
            taps += 1
        } else {
            taps = 1
        }
        lastTap = time
        guard taps >= Self.requiredTaps else { return false }
        isUnlocked = true
        defaults.set(true, forKey: Self.key)
        resetTaps()
        return true
    }

    /// Hide developer settings: locks them again.
    func hide() {
        isUnlocked = false
        defaults.removeObject(forKey: Self.key)
        resetTaps()
    }

    private func resetTaps() {
        taps = 0
        lastTap = nil
    }
}

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
