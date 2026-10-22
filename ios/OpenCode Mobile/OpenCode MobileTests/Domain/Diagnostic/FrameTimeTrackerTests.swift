import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct PerformanceOverlayGateTests {
    @Test func visibleOnlyWhenUnlockedAndEnabled() {
        #expect(PerformanceOverlayGate.isVisible(developerToolsUnlocked: true, enabled: true))
        #expect(!PerformanceOverlayGate.isVisible(developerToolsUnlocked: true, enabled: false))
        #expect(!PerformanceOverlayGate.isVisible(developerToolsUnlocked: false, enabled: true))
        #expect(!PerformanceOverlayGate.isVisible(developerToolsUnlocked: false, enabled: false))
    }
}

@Suite struct FrameTimeTrackerTests {
    private let frame60 = 1.0 / 60.0
    private let frame120 = 1.0 / 120.0

    @Test func firstSample_isIdle() {
        var tracker = FrameTimeTracker()

        let snapshot = tracker.record(timestamp: 1, expectedInterval: frame60)

        #expect(snapshot == .idle)
        #expect(snapshot.label == "— fps\n— ms\nspikes 0\nworst —")
    }

    @Test func steady60Hz_reportsRefreshRateWithoutSpikes() {
        var tracker = FrameTimeTracker()
        var snapshot = FrameTimeSnapshot.idle
        let frames = 40
        for index in 0 ... frames {
            snapshot = tracker.record(timestamp: Double(index) * frame60, expectedInterval: frame60)
        }

        #expect(snapshot.framesPerSecond == 60)
        #expect(snapshot.frameMilliseconds == 17)
        #expect(snapshot.spikeCount == 0)
        #expect(snapshot.worstSpikeMilliseconds == nil)
        #expect(!snapshot.spikeActive)
    }

    @Test func frameUnderTwiceVsync_isNotASpike() {
        var tracker = FrameTimeTracker()
        _ = tracker.record(timestamp: 0, expectedInterval: frame60)

        let snapshot = tracker.record(timestamp: 0.020, expectedInterval: frame60)

        #expect(snapshot.spikeCount == 0)
        #expect(snapshot.frameMilliseconds == 20)
    }

    @Test func frameAtLeastTwiceVsync_isASpike() {
        var tracker = FrameTimeTracker()
        _ = tracker.record(timestamp: 0, expectedInterval: frame60)

        let snapshot = tracker.record(timestamp: 0.050, expectedInterval: frame60)

        #expect(snapshot.spikeCount == 1)
        #expect(snapshot.worstSpikeMilliseconds == 50)
        #expect(snapshot.frameMilliseconds == 50)
        #expect(snapshot.spikeActive)
        #expect(snapshot.label == "20 fps\n50 ms\nspikes 1\nworst 50 ms")
    }

    @Test func sameGap_isASpikeAt120Hz() {
        var tracker = FrameTimeTracker()
        _ = tracker.record(timestamp: 0, expectedInterval: frame120)

        let snapshot = tracker.record(timestamp: 0.020, expectedInterval: frame120)

        #expect(snapshot.spikeCount == 1)
        #expect(snapshot.spikeActive)
    }

    @Test func highlightEnds_butWorstSpikeStays() {
        var tracker = FrameTimeTracker()
        _ = tracker.record(timestamp: 0, expectedInterval: frame60)
        _ = tracker.record(timestamp: 0.050, expectedInterval: frame60)

        var timestamp = 0.050
        var snapshot = FrameTimeSnapshot.idle
        let end = 0.050 + FrameTimeTracker.spikeHighlightSeconds + frame60
        while timestamp < end - 0.000_001 {
            timestamp = min(timestamp + frame60, end)
            snapshot = tracker.record(timestamp: timestamp, expectedInterval: frame60)
        }

        #expect(!snapshot.spikeActive)
        #expect(snapshot.spikeCount == 1)
        #expect(snapshot.worstSpikeMilliseconds == 50)
    }

    @Test func suspendedGap_isNotASpike() {
        var tracker = FrameTimeTracker()
        _ = tracker.record(timestamp: 0, expectedInterval: frame60)
        _ = tracker.record(timestamp: frame60, expectedInterval: frame60)

        let snapshot = tracker.record(timestamp: frame60 + FrameTimeTracker.suspendedGapSeconds, expectedInterval: frame60)

        #expect(snapshot.spikeCount == 0)
        #expect(snapshot.worstSpikeMilliseconds == nil)
        #expect(snapshot.framesPerSecond == nil)
    }

    @Test func invalidIntervalAndBackwardsTimestamp_areIgnored() {
        var tracker = FrameTimeTracker()

        let invalid = tracker.record(timestamp: 1, expectedInterval: 0)
        let backwards = tracker.record(timestamp: 0.5, expectedInterval: frame60)

        #expect(invalid.spikeCount == 0)
        #expect(backwards.spikeCount == 0)
        #expect(backwards.frameMilliseconds == nil)
    }
}

@Suite struct PerformanceOverlayPreferencesTests {
    @Test func missingKey_defaultsOff() {
        let defaults = suite("default")
        let preferences = PerformanceOverlayPreferences(defaults: defaults)

        #expect(preferences.enabled == false)
    }

    @Test func setEnabled_isPersisted() {
        let defaults = suite("enabled")
        let preferences = PerformanceOverlayPreferences(defaults: defaults)

        preferences.setEnabled(true)

        #expect(preferences.enabled == true)
        #expect(defaults.bool(forKey: "performanceOverlayEnabled") == true)
    }

    @Test func clear_turnsOffAndRemovesKey() {
        let defaults = suite("clear")
        let preferences = PerformanceOverlayPreferences(defaults: defaults)
        preferences.setEnabled(true)

        preferences.clear()

        #expect(preferences.enabled == false)
        #expect(defaults.object(forKey: "performanceOverlayEnabled") == nil)
    }

    private func suite(_ name: String) -> UserDefaults {
        let suite = "PerformanceOverlayPreferencesTests.\(name)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return defaults
    }
}
