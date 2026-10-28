import Foundation
import Testing
@testable import OpenCode_Mobile

struct AppLockPolicyTests {
    @Test func coverShowsOnlyWhenAppLockIsOnAndTheAppIsNotInTheForegroundOrIsLocked() {
        #expect(AppLockPolicy.coverVisible(isForeground: false, locked: false, enabled: true))
        #expect(AppLockPolicy.coverVisible(isForeground: false, locked: false, enabled: false) == false)
        #expect(AppLockPolicy.coverVisible(isForeground: true, locked: true, enabled: true))
        #expect(AppLockPolicy.coverVisible(isForeground: true, locked: true, enabled: false))
        #expect(AppLockPolicy.coverVisible(isForeground: true, locked: false, enabled: true) == false)
        #expect(AppLockPolicy.coverVisible(isForeground: true, locked: false, enabled: false) == false)
    }

    @Test func lockEngagesOnlyWhenEnabledAndTheAppWasInTheBackground() {
        #expect(AppLockPolicy.shouldLock(enabled: true, wasInBackground: true))
        #expect(AppLockPolicy.shouldLock(enabled: true, wasInBackground: false) == false)
        #expect(AppLockPolicy.shouldLock(enabled: false, wasInBackground: true) == false)
    }

    @Test func preferencesDefaultToOffAndPersist() {
        let suite = "AppLockPolicyTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let preferences = AppLockPreferences(defaults: defaults)
        #expect(preferences.enabled == false)
        preferences.setEnabled(true)
        #expect(AppLockPreferences(defaults: defaults).enabled)
        preferences.clear()
        #expect(AppLockPreferences(defaults: defaults).enabled == false)
    }
}
