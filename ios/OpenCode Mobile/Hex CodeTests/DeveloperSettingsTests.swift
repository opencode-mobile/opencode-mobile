import Foundation
import Testing
@testable import Hex_Code

/// Ten quick taps on Settings' Version row turn developer settings on; they stay on until hidden.
@MainActor
struct DeveloperSettingsTests {
    /// A clock the test moves by hand.
    private final class Clock {
        var now = Date(timeIntervalSince1970: 1_000_000)
        func advance(_ seconds: TimeInterval) { now += seconds }
    }

    private let clock = Clock()
    private let defaults: UserDefaults

    init() {
        let suite = "DeveloperSettingsTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
    }

    private func settings() -> DeveloperSettings {
        DeveloperSettings(defaults: defaults, now: { [clock] in clock.now })
    }

    private func tap(_ settings: DeveloperSettings, times: Int, every gap: TimeInterval = 0.3) -> [Bool] {
        (0 ..< times).map { _ in
            clock.advance(gap)
            return settings.tapVersion()
        }
    }

    @Test func startsLocked() {
        #expect(!settings().isUnlocked)
    }

    @Test func tenQuickTapsUnlock() {
        let developer = settings()
        let results = tap(developer, times: 10)
        #expect(results == Array(repeating: false, count: 9) + [true])
        #expect(developer.isUnlocked)
    }

    @Test func nineTapsDoNot() {
        let developer = settings()
        _ = tap(developer, times: 9)
        #expect(!developer.isUnlocked)
    }

    @Test func tapsExactlyTwoSecondsApartStillCount() {
        let developer = settings()
        _ = tap(developer, times: 10, every: DeveloperSettings.tapWindow)
        #expect(developer.isUnlocked)
    }

    @Test func aGapLongerThanTwoSecondsStartsOver() {
        let developer = settings()
        _ = tap(developer, times: 9)
        clock.advance(2.1)
        // This tap counts as the first again, so nine more are needed.
        #expect(!developer.tapVersion())
        _ = tap(developer, times: 8)
        #expect(!developer.isUnlocked)
        #expect(tap(developer, times: 1) == [true])
        #expect(developer.isUnlocked)
    }

    @Test func theUnlockIsSavedOnThePhone() {
        _ = tap(settings(), times: 10)
        #expect(defaults.bool(forKey: DeveloperSettings.key))
        #expect(settings().isUnlocked)
    }

    @Test func tapsWhileUnlockedDoNothing() {
        let developer = settings()
        _ = tap(developer, times: 10)
        #expect(tap(developer, times: 10).allSatisfy { !$0 })
        #expect(developer.isUnlocked)
    }

    @Test func hideLocksAgainAndForgetsTheUnlock() {
        let developer = settings()
        _ = tap(developer, times: 10)
        developer.hide()
        #expect(!developer.isUnlocked)
        #expect(!defaults.bool(forKey: DeveloperSettings.key))
        #expect(!settings().isUnlocked)
        // Unlocking again takes ten fresh taps.
        _ = tap(developer, times: 9)
        #expect(!developer.isUnlocked)
        _ = tap(developer, times: 1)
        #expect(developer.isUnlocked)
    }

    @Test func buildInfoLabels() {
        let debug = HexBuildInfo(version: "0.1.0", isDebug: true, commit: "abc123def456")
        #expect(debug.versionLabel == "0.1.0 (debug)")
        #expect(debug.summary == "Hex Code 0.1.0 (debug) abc123def456")
        let release = HexBuildInfo(version: "0.1.0", isDebug: false, commit: nil)
        #expect(release.versionLabel == "0.1.0 (release)")
        #expect(release.summary == "Hex Code 0.1.0 (release)")
    }
}
