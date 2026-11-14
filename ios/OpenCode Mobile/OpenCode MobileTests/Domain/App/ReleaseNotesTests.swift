import Foundation
import Testing
@testable import OpenCode_Mobile

private func version(_ string: String) -> SemanticVersion {
    SemanticVersion(string)!
}

private func release(_ string: String, kinds: [ReleaseNoteKind] = [.new]) -> ReleaseNotesRelease {
    ReleaseNotesRelease(
        version: version(string),
        released: nil,
        items: kinds.enumerated().map { index, kind in
            ReleaseNoteItem(kind: kind, icon: .edit, title: "\(string) item \(index)", body: "")
        }
    )
}

@Suite struct SemanticVersionTests {
    @Test func comparesNumerically() {
        #expect(version("1.10.0") > version("1.9.9"))
        #expect(version("2.0.0") > version("1.99.99"))
        #expect(version("1.2.10") > version("1.2.9"))
        #expect(version("1.4.0") == version("1.4.0"))
    }

    @Test func dropsPrereleaseAndBuildSuffix() {
        #expect(version("1.4.0-beta.2") == version("1.4.0"))
        #expect(version("1.4.0+42") == version("1.4.0"))
        #expect(version("1.4.0-rc1+7").description == "1.4.0")
    }

    @Test func missingPartsCountAsZero() {
        #expect(version("1.4") == version("1.4.0"))
        #expect(version("2") == version("2.0.0"))
    }

    @Test func rejectsMalformedVersions() {
        #expect(SemanticVersion("") == nil)
        #expect(SemanticVersion("abc") == nil)
        #expect(SemanticVersion("1..0") == nil)
        #expect(SemanticVersion("1.2.3.4") == nil)
        #expect(SemanticVersion("1.x.0") == nil)
    }
}

@Suite struct ReleaseNotesParserTests {
    private let json = Data("""
    {
      "schema": 2,
      "releases": [
        {
          "android": { "version": "1.6.0", "released": null },
          "ios": { "version": "1.5.0", "released": null },
          "items": [
            { "kind": "new", "icon": "edit", "title": "Ahead", "body": "Newer than installed." }
          ]
        },
        {
          "android": { "version": "1.5.0", "released": "2026-10-27" },
          "ios": { "version": "1.4.0", "released": "2026-10-27", "build": 99 },
          "codename": "ignored",
          "items": [
            { "kind": "fixed", "icon": "fix", "title": "Fix", "body": "Fixed.", "prs": [1] },
            { "kind": "new", "icon": "goal", "title": "Both", "body": "Both platforms." },
            { "kind": "new", "icon": "edit", "title": "Android only", "body": "", "platforms": ["android"] },
            { "kind": "tweaked", "icon": "hologram", "title": "Unknown kind and icon", "body": "Fallbacks." },
            { "kind": "improved", "icon": "list", "title": "iOS only", "body": "Here.", "platforms": ["ios"] }
          ]
        },
        {
          "android": { "version": "1.4.1", "released": "2026-10-20" },
          "items": [
            { "kind": "new", "icon": "edit", "title": "Android release", "body": "" }
          ]
        },
        {
          "android": { "version": "1.4.0", "released": "2026-10-19" },
          "ios": { "version": "1.3.0", "released": "2026-10-19" },
          "items": [
            { "kind": "new", "icon": "edit", "title": "Android only", "body": "", "platforms": ["android"] }
          ]
        },
        {
          "android": { "version": "1.0.0", "released": "2026-09-18" },
          "ios": { "version": "1.0.0", "released": "2026-09-18" },
          "items": [
            { "kind": "new", "icon": "phone", "title": "First", "body": "Hello." }
          ]
        }
      ]
    }
    """.utf8)

    @Test func keepsThisPlatformsReleasesAndItems() throws {
        let releases = try ReleaseNotesParser.parse(json, platform: .ios, installed: version("1.4.0"))

        // 1.5.0 is ahead of the install, the Android-only release has no iOS key,
        // and 1.3.0 is left with no iOS items.
        #expect(releases.map(\.version.description) == ["1.4.0", "1.0.0"])
        #expect(releases[0].items.map(\.title) == ["Fix", "Both", "Unknown kind and icon", "iOS only"])
        #expect(releases[0].released == DateComponents(year: 2026, month: 10, day: 27))
    }

    @Test func androidSeesItsOwnReleases() throws {
        let releases = try ReleaseNotesParser.parse(json, platform: .android, installed: version("1.5.0"))

        #expect(releases.map(\.version.description) == ["1.5.0", "1.4.1", "1.4.0", "1.0.0"])
        #expect(!releases[0].items.map(\.title).contains("iOS only"))
    }

    @Test func unknownKindReadsAsImprovedAndUnknownIconAsSparkle() throws {
        let releases = try ReleaseNotesParser.parse(json, platform: .ios, installed: version("1.4.0"))
        let item = try #require(releases[0].items.first { $0.title == "Unknown kind and icon" })

        #expect(item.kind == .improved)
        #expect(item.icon == .sparkle)
        #expect(releases[0].items.first?.kind == .fixed)
        #expect(releases[0].items.first?.icon == .fix)
    }

    @Test func noInstalledVersionKeepsEveryRelease() throws {
        let releases = try ReleaseNotesParser.parse(json, platform: .ios, installed: nil)

        #expect(releases.first?.version.description == "1.5.0")
    }

    @Test func unreleasedHasNoDate() throws {
        let releases = try ReleaseNotesParser.parse(json, platform: .ios, installed: nil)

        #expect(releases.first?.released == nil)
    }

    @Test func malformedFileThrows() {
        #expect(throws: (any Error).self) {
            try ReleaseNotesParser.parse(Data("{ \"releases\": 3 }".utf8), platform: .ios, installed: nil)
        }
    }

    @Test func bundledFileParsesWithIOSReleases() throws {
        let url = try #require(Bundle.main.url(forResource: "release-notes", withExtension: "json"))
        let releases = try ReleaseNotesParser.parse(Data(contentsOf: url), platform: .ios, installed: nil)

        #expect(!releases.isEmpty)
        #expect(releases.allSatisfy { !$0.items.isEmpty })
    }
}

@Suite struct WhatsNewLaunchPolicyTests {
    private let releases = [release("1.4.0"), release("1.2.1"), release("1.2.0"), release("1.0.0")]

    private func decide(installed: String, lastSeen: String?, isUpgrade: Bool = true) -> WhatsNewLaunchDecision {
        WhatsNewLaunchPolicy.decide(
            installed: version(installed),
            lastSeen: lastSeen.map(version),
            isUpgrade: isUpgrade,
            releases: releases
        )
    }

    @Test func freshInstall_showsNothingAndRecordsInstalled() {
        let decision = decide(installed: "1.4.0", lastSeen: nil, isUpgrade: false)

        #expect(decision.releases.isEmpty)
        #expect(decision.lastSeen == version("1.4.0"))
    }

    @Test func firstVersionWithFeature_onUpgrade_showsOnlyInstalledRelease() {
        let decision = decide(installed: "1.4.0", lastSeen: nil, isUpgrade: true)

        #expect(decision.releases.map(\.version.description) == ["1.4.0"])
        #expect(decision.lastSeen == version("1.4.0"))
    }

    @Test func firstVersionWithFeature_onUpgrade_withoutNotesForInstalled_showsNothing() {
        let decision = decide(installed: "1.3.0", lastSeen: nil, isUpgrade: true)

        #expect(decision.releases.isEmpty)
        #expect(decision.lastSeen == version("1.3.0"))
    }

    @Test func update_showsEveryReleaseSinceLastSeen_newestFirst() {
        let decision = decide(installed: "1.4.0", lastSeen: "1.0.0")

        #expect(decision.releases.map(\.version.description) == ["1.4.0", "1.2.1", "1.2.0"])
        #expect(decision.lastSeen == version("1.4.0"))
    }

    @Test func update_toVersionWithoutNotes_showsOlderUnseenOnes() {
        let decision = decide(installed: "1.3.0", lastSeen: "1.2.0")

        #expect(decision.releases.map(\.version.description) == ["1.2.1"])
        #expect(decision.lastSeen == version("1.3.0"))
    }

    @Test func sameVersion_showsNothing() {
        let decision = decide(installed: "1.4.0", lastSeen: "1.4.0")

        #expect(decision.releases.isEmpty)
        #expect(decision.lastSeen == version("1.4.0"))
    }

    @Test func downgrade_showsNothingAndKeepsLastSeen() {
        let decision = decide(installed: "1.2.0", lastSeen: "1.4.0")

        #expect(decision.releases.isEmpty)
        #expect(decision.lastSeen == version("1.4.0"))
    }
}

@Suite struct WhatsNewStoreTests {
    private let releases = [release("1.4.0"), release("1.2.1"), release("1.2.0")]

    private func freshDefaults() -> UserDefaults {
        let name = "WhatsNewStoreTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    @Test func pendingNotesAreNotSeenUntilPresented() {
        let defaults = freshDefaults()
        defaults.set("1.2.0", forKey: WhatsNewStore.StorageKeys.lastSeenVersion)
        let store = WhatsNewStore(defaults: defaults, installedVersion: "1.4.0", releases: releases)

        store.prepareLaunch(isUpgrade: true)

        #expect(store.pendingReleases.map(\.version.description) == ["1.4.0", "1.2.1"])
        #expect(defaults.string(forKey: WhatsNewStore.StorageKeys.lastSeenVersion) == "1.2.0")
        #expect(!store.badgePending)

        // A relaunch that never reached an idle Home still shows them.
        let relaunched = WhatsNewStore(defaults: defaults, installedVersion: "1.4.0", releases: releases)
        relaunched.prepareLaunch(isUpgrade: true)
        #expect(relaunched.pendingReleases.count == 2)
    }

    @Test func presentingRecordsSeenAndRaisesBadgeOnce() {
        let defaults = freshDefaults()
        defaults.set("1.2.0", forKey: WhatsNewStore.StorageKeys.lastSeenVersion)
        let store = WhatsNewStore(defaults: defaults, installedVersion: "1.4.0", releases: releases)
        store.prepareLaunch(isUpgrade: true)

        store.markPresented()

        #expect(store.pendingReleases.isEmpty)
        #expect(store.badgePending)
        #expect(defaults.string(forKey: WhatsNewStore.StorageKeys.lastSeenVersion) == "1.4.0")

        let relaunched = WhatsNewStore(defaults: defaults, installedVersion: "1.4.0", releases: releases)
        relaunched.prepareLaunch(isUpgrade: true)
        #expect(relaunched.pendingReleases.isEmpty)
        #expect(relaunched.badgePending)

        relaunched.clearBadge()
        #expect(!WhatsNewStore(defaults: defaults, installedVersion: "1.4.0", releases: releases).badgePending)
    }

    @Test func openingHistoryFirstCountsPendingAsSeenSoTheSheetNeverShows() {
        let defaults = freshDefaults()
        defaults.set("1.2.0", forKey: WhatsNewStore.StorageKeys.lastSeenVersion)
        let store = WhatsNewStore(defaults: defaults, installedVersion: "1.4.0", releases: releases)
        store.prepareLaunch(isUpgrade: true)
        let sheet = WhatsNewSheetModel(store: store)

        WhatsNewHistoryModel(store: store).onAppear()
        sheet.homeIsIdle()

        #expect(sheet.sheet == nil)
        #expect(!store.badgePending)
        #expect(defaults.string(forKey: WhatsNewStore.StorageKeys.lastSeenVersion) == "1.4.0")
    }

    @Test func freshInstallRecordsInstalledAtLaunch() {
        let defaults = freshDefaults()
        let store = WhatsNewStore(defaults: defaults, installedVersion: "1.4.0-beta+3", releases: releases)

        store.prepareLaunch(isUpgrade: false)

        #expect(store.pendingReleases.isEmpty)
        #expect(defaults.string(forKey: WhatsNewStore.StorageKeys.lastSeenVersion) == "1.4.0")
    }

    @Test func sheetModelShowsPendingOnceAndMergesSkippedReleases() {
        let defaults = freshDefaults()
        defaults.set("1.0.0", forKey: WhatsNewStore.StorageKeys.lastSeenVersion)
        let store = WhatsNewStore(defaults: defaults, installedVersion: "1.4.0", releases: releases)
        store.prepareLaunch(isUpgrade: true)
        let model = WhatsNewSheetModel(store: store)

        model.homeIsIdle()

        #expect(model.sheet?.installedVersion == "1.4.0")
        #expect(model.sheet?.skippedCount == 2)
        #expect(model.sheet?.sections.map(\.header) == [.inVersion("1.4.0"), .alsoSince("1.2.1"), .alsoSince("1.2.0")])

        model.dismiss()
        model.homeIsIdle()
        #expect(model.sheet == nil)
    }
}

@Suite struct WhatsNewContentTests {
    @Test func ordersNewThenImprovedThenFixes() {
        let mixed = release("1.4.0", kinds: [.fixed, .improved, .new, .improved, .new])

        let section = WhatsNewContent.sections([mixed], installed: version("1.4.0"), headers: false)[0]

        #expect(section.features.map(\.title) == ["1.4.0 item 2", "1.4.0 item 4", "1.4.0 item 1", "1.4.0 item 3"])
        #expect(section.fixes.map(\.title) == ["1.4.0 item 0"])
        #expect(section.header == nil)
        #expect(section.isInstalled)
    }

    @Test func singleReleaseSheetHasNoHeaders() {
        let sheet = WhatsNewSheetState(releases: [release("1.4.0")], installed: version("1.4.0"))

        #expect(sheet.skippedCount == 0)
        #expect(sheet.sections.map(\.header) == [nil])
    }

    @Test func historyMarksOnlyTheInstalledRelease() {
        let sections = WhatsNewContent.sections(
            [release("1.4.0"), release("1.2.0")],
            installed: version("1.4.0"),
            headers: false
        )

        #expect(sections.map(\.isInstalled) == [true, false])
    }
}
