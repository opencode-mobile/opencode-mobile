import Foundation
import Observation

/// Heading above one release when the sheet merges several.
enum WhatsNewSectionHeader: Equatable {
    /// The installed release: "In X.Y.Z".
    case inVersion(String)
    /// An older release the person skipped: "Also new since your last update · X.Y.Z".
    case alsoSince(String)
}

/// One release laid out for the sheet or the history screen.
struct WhatsNewReleaseSection: Equatable, Identifiable {
    let version: String
    let released: DateComponents?
    let header: WhatsNewSectionHeader?
    let isInstalled: Bool
    /// `new` items first, then `improved`, each in file order.
    let features: [ReleaseNoteItem]
    /// Shown under a "Fixes" heading.
    let fixes: [ReleaseNoteItem]

    var id: String { version }
}

/// Groups releases into sections. Pure, shared by the sheet and history.
enum WhatsNewContent {
    /// - Parameter headers: label each release ("In …" / "Also new since …");
    ///   the sheet does this only when it merges several releases.
    static func sections(
        _ releases: [ReleaseNotesRelease],
        installed: SemanticVersion?,
        headers: Bool
    ) -> [WhatsNewReleaseSection] {
        releases.enumerated().map { index, release in
            let version = release.version.description
            let header: WhatsNewSectionHeader? = headers
                ? (index == 0 ? .inVersion(version) : .alsoSince(version))
                : nil
            return WhatsNewReleaseSection(
                version: version,
                released: release.released,
                header: header,
                isInstalled: release.version == installed,
                features: release.items.filter { $0.kind == .new } + release.items.filter { $0.kind == .improved },
                fixes: release.items.filter { $0.kind == .fixed }
            )
        }
    }
}

struct WhatsNewSheetState: Equatable {
    let installedVersion: String
    /// Releases shown besides the installed one.
    let skippedCount: Int
    let sections: [WhatsNewReleaseSection]

    init(releases: [ReleaseNotesRelease], installed: SemanticVersion) {
        installedVersion = installed.description
        skippedCount = max(releases.count - 1, 0)
        sections = WhatsNewContent.sections(releases, installed: installed, headers: releases.count > 1)
    }
}

/// Screen Model for the What's new sheet over Home.
@Observable
final class WhatsNewSheetModel {
    private(set) var sheet: WhatsNewSheetState?

    private let store: WhatsNewStore

    init(store: WhatsNewStore = .shared) {
        self.store = store
    }

    /// Home is the visible, idle screen: show pending notes once per process.
    func homeIsIdle() {
        guard sheet == nil, !store.pendingReleases.isEmpty, let installed = store.installedVersion else { return }
        sheet = WhatsNewSheetState(releases: store.pendingReleases, installed: installed)
        store.markPresented()
    }

    /// Done, swipe down, or a tap outside.
    func dismiss() {
        sheet = nil
    }
}

struct WhatsNewHistoryState: Equatable {
    let sections: [WhatsNewReleaseSection]
}

/// Screen Model for Settings → What's new.
@Observable
final class WhatsNewHistoryModel {
    let state: WhatsNewHistoryState

    private let store: WhatsNewStore

    init(store: WhatsNewStore = .shared) {
        self.store = store
        state = WhatsNewHistoryState(
            sections: WhatsNewContent.sections(store.releases, installed: store.installedVersion, headers: false)
        )
    }

    func onAppear() {
        store.clearBadge()
    }
}
