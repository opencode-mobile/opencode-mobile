import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct DeveloperToolsUnlockTests {
    @Test func firstTap_startsAtOne() {
        #expect(DeveloperToolsUnlock.nextCount(previousCount: 0, lastTapAtMs: 0, nowMs: 10_000) == 1)
    }

    @Test func consecutiveTaps_countUp() {
        var count = 0
        var last: Int64 = 0
        for _ in 1 ... 10 {
            let now = last + 200
            count = DeveloperToolsUnlock.nextCount(previousCount: count, lastTapAtMs: last, nowMs: now)
            last = now
        }
        #expect(count == 10)
    }

    @Test func staleTap_resetsToOne() {
        let next = DeveloperToolsUnlock.nextCount(
            previousCount: 7,
            lastTapAtMs: 1_000,
            nowMs: 1_000 + DeveloperToolsUnlock.tapWindowMs + 1
        )
        #expect(next == 1)
    }

    @Test func tapInsideWindow_continues() {
        let next = DeveloperToolsUnlock.nextCount(
            previousCount: 7,
            lastTapAtMs: 1_000,
            nowMs: 1_000 + DeveloperToolsUnlock.tapWindowMs
        )
        #expect(next == 8)
    }
}

@Suite struct DeveloperToolsPreferencesTests {
    @Test func missingKey_defaultsLocked() {
        let suite = "DeveloperToolsPreferencesTests.default"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)

        let preferences = DeveloperToolsPreferences(defaults: defaults)

        #expect(preferences.unlocked == false)
    }

    @Test func unlock_isPersisted() {
        let suite = "DeveloperToolsPreferencesTests.unlock"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let preferences = DeveloperToolsPreferences(defaults: defaults)

        preferences.unlock()

        #expect(preferences.unlocked == true)
        #expect(defaults.bool(forKey: "developerToolsUnlocked") == true)
    }

    @Test func clear_locksAgain() {
        let suite = "DeveloperToolsPreferencesTests.clear"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let preferences = DeveloperToolsPreferences(defaults: defaults)
        preferences.unlock()

        preferences.clear()

        #expect(preferences.unlocked == false)
        #expect(defaults.object(forKey: "developerToolsUnlocked") == nil)
    }
}
