import Foundation

/// MAJOR.MINOR.PATCH of a marketing version. A `-prerelease` or `+build`
/// suffix is dropped, and a missing minor or patch counts as 0.
struct SemanticVersion: Comparable, Hashable, Sendable, CustomStringConvertible {
    let major: Int
    let minor: Int
    let patch: Int

    init(major: Int, minor: Int, patch: Int) {
        self.major = major
        self.minor = minor
        self.patch = patch
    }

    init?(_ string: String) {
        let core = string
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .split(whereSeparator: { $0 == "-" || $0 == "+" })
            .first
            .map(String.init) ?? ""
        let parts = core.split(separator: ".", omittingEmptySubsequences: false)
        guard (1 ... 3).contains(parts.count) else { return nil }
        var numbers: [Int] = []
        for part in parts {
            guard !part.isEmpty, part.allSatisfy(\.isASCII), let value = Int(part), value >= 0 else { return nil }
            numbers.append(value)
        }
        while numbers.count < 3 { numbers.append(0) }
        self.init(major: numbers[0], minor: numbers[1], patch: numbers[2])
    }

    var description: String { "\(major).\(minor).\(patch)" }

    static func < (lhs: SemanticVersion, rhs: SemanticVersion) -> Bool {
        (lhs.major, lhs.minor, lhs.patch) < (rhs.major, rhs.minor, rhs.patch)
    }
}

enum ReleaseNotesPlatform: String, Sendable {
    case ios
    case android
}

enum ReleaseNoteKind: Equatable, Sendable {
    case new
    case improved
    case fixed

    /// Unknown kinds read as improvements.
    init(key: String) {
        switch key {
        case "new": self = .new
        case "fixed": self = .fixed
        default: self = .improved
        }
    }
}

/// Semantic icon key from `release-notes.json`. Unknown keys show `.sparkle`.
enum ReleaseNoteIcon: String, CaseIterable, Sendable {
    case edit
    case goal
    case agent
    case image
    case note
    case search
    case keyboard
    case attach
    case sparkle
    case textSelect = "text-select"
    case chat
    case time
    case sheet
    case server
    case star
    case send
    case fix
    case code
    case qr
    case gitBranch = "git-branch"
    case list
    case terminal
    case slash
    case lock
    case archive
    case folder
    case sidebar
    case mic
    case atSign = "at-sign"
    case fork
    case feedback
    case phone

    init(key: String) {
        self = ReleaseNoteIcon(rawValue: key) ?? .sparkle
    }
}

struct ReleaseNoteItem: Equatable, Sendable {
    let kind: ReleaseNoteKind
    let icon: ReleaseNoteIcon
    let title: String
    let body: String
}

/// One shipped release as this platform sees it.
struct ReleaseNotesRelease: Equatable, Sendable {
    let version: SemanticVersion
    /// Calendar day the release shipped; nil before it ships.
    let released: DateComponents?
    let items: [ReleaseNoteItem]
}

/// Reads `release-notes.json` into this platform's releases, newest first.
enum ReleaseNotesParser {
    /// - Parameter installed: releases newer than this are dropped (a debug
    ///   build can be ahead of the notes). Nil keeps every release.
    static func parse(
        _ data: Data,
        platform: ReleaseNotesPlatform,
        installed: SemanticVersion?
    ) throws -> [ReleaseNotesRelease] {
        let file = try JSONDecoder().decode(FileDTO.self, from: data)
        let releases: [ReleaseNotesRelease] = file.releases.compactMap { release in
            guard let entry = platform == .ios ? release.ios : release.android,
                  let version = SemanticVersion(entry.version)
            else { return nil }
            if let installed, version > installed { return nil }
            let items = release.items
                .filter { $0.platforms == nil || $0.platforms?.contains(platform.rawValue) == true }
                .map {
                    ReleaseNoteItem(
                        kind: ReleaseNoteKind(key: $0.kind),
                        icon: ReleaseNoteIcon(key: $0.icon ?? ""),
                        title: $0.title,
                        body: $0.body ?? ""
                    )
                }
            guard !items.isEmpty else { return nil }
            return ReleaseNotesRelease(version: version, released: entry.released.flatMap(day), items: items)
        }
        return releases.sorted { $0.version > $1.version }
    }

    /// `yyyy-MM-dd` as a calendar day, with no time zone to shift it.
    private static func day(_ string: String) -> DateComponents? {
        let parts = string.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return DateComponents(year: parts[0], month: parts[1], day: parts[2])
    }

    private struct FileDTO: Decodable {
        let releases: [ReleaseDTO]
    }

    private struct ReleaseDTO: Decodable {
        let android: PlatformDTO?
        let ios: PlatformDTO?
        let items: [ItemDTO]
    }

    private struct PlatformDTO: Decodable {
        let version: String
        let released: String?
    }

    private struct ItemDTO: Decodable {
        let kind: String
        let icon: String?
        let title: String
        let body: String?
        let platforms: [String]?
    }
}

/// What to show at launch and what to remember as seen.
struct WhatsNewLaunchDecision: Equatable, Sendable {
    /// Newest first; empty shows nothing.
    let releases: [ReleaseNotesRelease]
    /// Value to store as the last seen version.
    let lastSeen: SemanticVersion
}

/// When the What's new sheet opens. Pure, shared with Android.
enum WhatsNewLaunchPolicy {
    static func decide(
        installed: SemanticVersion,
        lastSeen: SemanticVersion?,
        isUpgrade: Bool,
        releases: [ReleaseNotesRelease]
    ) -> WhatsNewLaunchDecision {
        guard let lastSeen else {
            // Nothing stored yet: a fresh install shows nothing; the first
            // version with this feature shows only its own notes.
            let shown = isUpgrade ? releases.filter { $0.version == installed } : []
            return WhatsNewLaunchDecision(releases: shown, lastSeen: installed)
        }
        guard installed > lastSeen else {
            // Same version or a downgrade: never lower what was seen.
            return WhatsNewLaunchDecision(releases: [], lastSeen: lastSeen)
        }
        let shown = releases
            .filter { $0.version > lastSeen && $0.version <= installed }
            .sorted { $0.version > $1.version }
        return WhatsNewLaunchDecision(releases: shown, lastSeen: installed)
    }
}
