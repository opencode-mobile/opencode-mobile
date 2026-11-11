import Foundation
import Observation

/// Bundled release notes and what this install has seen of them, kept in
/// `UserDefaults` under `whatsNew.*`.
///
/// The app root calls `prepareLaunch(isUpgrade:)` once per launch. Home asks
/// for `pendingReleases` when it is idle and calls `markPresented()` the
/// moment the sheet shows, which records the version as seen and raises the
/// Settings badge. Nothing is recorded while the sheet is still waiting, so a
/// launch that never reaches an idle Home shows it next time.
@Observable
final class WhatsNewStore {
    static let shared = WhatsNewStore()

    enum StorageKeys {
        static let lastSeenVersion = "whatsNew.lastSeenVersion"
        static let badgePending = "whatsNew.badgePending"
    }

    /// Every bundled release up to the installed version, newest first.
    let releases: [ReleaseNotesRelease]
    let installedVersion: SemanticVersion?
    /// Releases waiting for an idle Home in this process; empty once shown.
    private(set) var pendingReleases: [ReleaseNotesRelease] = []
    private(set) var badgePending: Bool

    private let defaults: UserDefaults
    private var pendingLastSeen: SemanticVersion?
    private var prepared = false

    init(
        defaults: UserDefaults = .standard,
        installedVersion: String = AppVersion.current().version,
        releases: [ReleaseNotesRelease]? = nil
    ) {
        self.defaults = defaults
        let installed = SemanticVersion(installedVersion)
        self.installedVersion = installed
        self.releases = releases ?? Self.loadBundled(installed: installed)
        badgePending = defaults.bool(forKey: StorageKeys.badgePending)
    }

    /// Once per launch, before Home appears. Later calls do nothing.
    func prepareLaunch(isUpgrade: Bool) {
        guard !prepared, let installedVersion else { return }
        prepared = true
        let decision = WhatsNewLaunchPolicy.decide(
            installed: installedVersion,
            lastSeen: defaults.string(forKey: StorageKeys.lastSeenVersion).flatMap(SemanticVersion.init),
            isUpgrade: isUpgrade,
            releases: releases
        )
        if decision.releases.isEmpty {
            store(lastSeen: decision.lastSeen)
        } else {
            pendingReleases = decision.releases
            pendingLastSeen = decision.lastSeen
        }
    }

    /// The sheet is on screen: never show it again, and badge Settings.
    func markPresented() {
        guard !pendingReleases.isEmpty else { return }
        pendingReleases = []
        if let pendingLastSeen { store(lastSeen: pendingLastSeen) }
        pendingLastSeen = nil
        badgePending = true
        defaults.set(true, forKey: StorageKeys.badgePending)
    }

    /// The person opened Settings → What's new.
    func clearBadge() {
        guard badgePending else { return }
        badgePending = false
        defaults.set(false, forKey: StorageKeys.badgePending)
    }

    private func store(lastSeen: SemanticVersion) {
        defaults.set(lastSeen.description, forKey: StorageKeys.lastSeenVersion)
    }

    private static func loadBundled(installed: SemanticVersion?) -> [ReleaseNotesRelease] {
        guard let url = Bundle.main.url(forResource: "release-notes", withExtension: "json"),
              let data = try? Data(contentsOf: url)
        else {
            assertionFailure("release-notes.json is missing from the app bundle")
            return []
        }
        do {
            return try ReleaseNotesParser.parse(data, platform: .ios, installed: installed)
        } catch {
            assertionFailure("release-notes.json could not be read: \(error)")
            return []
        }
    }
}
