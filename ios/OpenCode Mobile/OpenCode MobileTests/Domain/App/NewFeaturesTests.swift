import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct NewFeaturePolicyTests {
    private let key = "select_message_text"

    private func synced(
        _ state: NewFeatureState = NewFeatureState(),
        keys: [String]? = nil,
        version: String,
        freshInstall: Bool
    ) -> NewFeatureState {
        NewFeaturePolicy.sync(state, keys: keys ?? [key], currentVersion: version, isFreshInstall: freshInstall)
    }

    @Test func freshInstall_isNotNew() {
        let state = synced(version: "1.4.0", freshInstall: true)

        #expect(state.initialized)
        #expect(state.entries[key]?.firstSeenVersion == NewFeaturePolicy.baselineVersion)
        #expect(!NewFeaturePolicy.isNew(state, key: key, currentVersion: "1.4.0"))
    }

    @Test func freshInstall_staysNotNewAfterUpdates() {
        var state = synced(version: "1.4.0", freshInstall: true)
        state = synced(state, version: "1.5.0", freshInstall: false)

        #expect(!NewFeaturePolicy.isNew(state, key: key, currentVersion: "1.5.0"))
    }

    @Test func existingInstallUpdating_isNew() {
        let state = synced(version: "1.4.0", freshInstall: false)

        #expect(state.entries[key]?.firstSeenVersion == "1.4.0")
        #expect(NewFeaturePolicy.isNew(state, key: key, currentVersion: "1.4.0"))
    }

    @Test func sameVersionRelaunch_isStillNew() {
        var state = synced(version: "1.4.0", freshInstall: false)
        state = synced(state, version: "1.4.0", freshInstall: false)

        #expect(NewFeaturePolicy.isNew(state, key: key, currentVersion: "1.4.0"))
    }

    @Test func versionChange_isNotNew() {
        var state = synced(version: "1.4.0", freshInstall: false)
        state = synced(state, version: "1.4.1", freshInstall: false)

        #expect(state.entries[key]?.firstSeenVersion == "1.4.0")
        #expect(!NewFeaturePolicy.isNew(state, key: key, currentVersion: "1.4.1"))
    }

    @Test func skippedRelease_isNewInTheLandingVersion() {
        // The feature shipped in 1.4.0; this install goes from 1.3.0 straight to 1.6.0.
        let state = synced(version: "1.6.0", freshInstall: false)

        #expect(NewFeaturePolicy.isNew(state, key: key, currentVersion: "1.6.0"))
    }

    @Test func used_isNotNew() {
        var state = synced(version: "1.4.0", freshInstall: false)
        state = NewFeaturePolicy.markUsed(state, key: key)

        #expect(!NewFeaturePolicy.isNew(state, key: key, currentVersion: "1.4.0"))
        #expect(synced(state, version: "1.4.0", freshInstall: false) == state)
    }

    @Test func keyAddedAfterInitialization_isNew() {
        // Installed fresh on 1.4.0, then a later release adds another feature.
        var state = synced(version: "1.4.0", freshInstall: true)
        // A fresh-install signal after the first sync must not make it baseline.
        state = synced(state, keys: [key, "later_feature"], version: "1.5.0", freshInstall: true)

        #expect(NewFeaturePolicy.isNew(state, key: "later_feature", currentVersion: "1.5.0"))
        #expect(!NewFeaturePolicy.isNew(state, key: key, currentVersion: "1.5.0"))
    }

    @Test func unknownKey_isNotNew() {
        #expect(!NewFeaturePolicy.isNew(NewFeatureState(), key: key, currentVersion: "1.4.0"))
    }
}

@Suite struct NewFeatureStoreTests {
    private func makeDefaults(_ name: String) -> UserDefaults {
        let suite = "NewFeatureStoreTests.\(name)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return defaults
    }

    @Test func existingInstall_showsBadgeUntilUsed_andPersists() {
        let defaults = makeDefaults("existing")
        let store = NewFeatureStore(defaults: defaults, currentVersion: "1.4.0")
        #expect(!store.isNew(.selectMessageText))

        store.sync(isFreshInstall: false)
        #expect(store.isNew(.selectMessageText))
        #expect(NewFeatureStore(defaults: defaults, currentVersion: "1.4.0").isNew(.selectMessageText))
        #expect(defaults.string(forKey: "newFeatures.select_message_text.firstSeenVersion") == "1.4.0")
        #expect(defaults.bool(forKey: "newFeatures.initialized"))

        store.markUsed(.selectMessageText)
        #expect(!store.isNew(.selectMessageText))
        #expect(!NewFeatureStore(defaults: defaults, currentVersion: "1.4.0").isNew(.selectMessageText))
        #expect(defaults.bool(forKey: "newFeatures.select_message_text.used"))
    }

    @Test func freshInstall_neverShowsBadge() {
        let defaults = makeDefaults("fresh")
        let store = NewFeatureStore(defaults: defaults, currentVersion: "1.4.0")
        store.sync(isFreshInstall: true)
        #expect(!store.isNew(.selectMessageText))

        let updated = NewFeatureStore(defaults: defaults, currentVersion: "1.5.0")
        updated.sync(isFreshInstall: false)
        #expect(!updated.isNew(.selectMessageText))
    }

    @Test func nextUpdate_hidesBadge() {
        let defaults = makeDefaults("update")
        let store = NewFeatureStore(defaults: defaults, currentVersion: "1.4.0")
        store.sync(isFreshInstall: false)

        let updated = NewFeatureStore(defaults: defaults, currentVersion: "1.4.1")
        updated.sync(isFreshInstall: false)
        #expect(!updated.isNew(.selectMessageText))
    }

    #if DEBUG
    @Test func debugOverride_showsUnusedFeatures() {
        let defaults = makeDefaults("debug")
        defaults.set(true, forKey: NewFeatureStore.debugShowBadgesKey)
        let store = NewFeatureStore(defaults: defaults, currentVersion: "1.4.0")
        store.sync(isFreshInstall: true)
        #expect(store.isNew(.selectMessageText))

        store.markUsed(.selectMessageText)
        #expect(!store.isNew(.selectMessageText))
    }
    #endif
}

@Suite struct NewFeatureRegistryTests {
    @Test func keysAreUnique() {
        #expect(Set(NewFeature.allCases.map(\.key)).count == NewFeature.allCases.count)
        #expect(NewFeature.selectMessageText.key == "select_message_text")
    }

    @Test func noEntryIsPastItsRemoveAfterDate() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try #require(TimeZone(identifier: "UTC"))
        let today = calendar.startOfDay(for: Date())
        for feature in NewFeature.allCases {
            let removeAfter = try #require(calendar.date(from: feature.removeAfter))
            let day = removeAfter.formatted(.iso8601.year().month().day())
            #expect(
                today <= removeAfter,
                "New feature \"\(feature.key)\" expired on \(day): remove the registry entry and its badge call sites"
            )
        }
    }
}
