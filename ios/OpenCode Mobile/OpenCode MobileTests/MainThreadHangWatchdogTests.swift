import Foundation
import Testing
@testable import OpenCode_Mobile

struct MainThreadHangWatchdogTests {
    @Test func detectsHangWhenAcknowledgmentIsOverdue() {
        var detector = MainThreadHangDetector()
        detector.notePingSent(at: 1_000)
        let event = detector.evaluate(now: 1_000 + 2_500, thresholdMilliseconds: 2_500)
        #expect(event == .hangDetected(overdueMilliseconds: 2_500))
        #expect(detector.isHung)
    }

    @Test func doesNotSpamWhileStillHung() {
        var detector = MainThreadHangDetector()
        detector.notePingSent(at: 0)
        #expect(detector.evaluate(now: 3_000, thresholdMilliseconds: 2_500) != nil)
        #expect(detector.evaluate(now: 4_000, thresholdMilliseconds: 2_500) == nil)
        #expect(detector.isHung)
    }

    @Test func recoversAfterAcknowledgment() {
        var detector = MainThreadHangDetector()
        detector.notePingSent(at: 100)
        _ = detector.evaluate(now: 3_000, thresholdMilliseconds: 2_500)
        let recovered = detector.notePingAcknowledged(at: 4_100)
        #expect(recovered == .hangRecovered(durationMilliseconds: 4_000))
        #expect(!detector.isHung)
        #expect(!detector.hasPendingPing)
    }

    @Test func acknowledgmentWithoutHangIsSilent() {
        var detector = MainThreadHangDetector()
        detector.notePingSent(at: 50)
        #expect(detector.notePingAcknowledged(at: 60) == nil)
        #expect(!detector.hasPendingPing)
    }

    @Test func watchdogLogsDetectAndRecoverThroughInjectedSink() {
        var now: Int64 = 0
        var pendingMainWork: (() -> Void)?
        var events: [MainThreadHangDetector.Event] = []

        let watchdog = MainThreadHangWatchdog(
            clock: { now },
            scheduleOnMain: { work in pendingMainWork = work },
            logEvent: { events.append($0) }
        )

        watchdog.tickForTesting()
        #expect(pendingMainWork != nil)
        #expect(events.isEmpty)

        now = MainThreadHangWatchdog.detectThresholdMilliseconds
        watchdog.tickForTesting()
        #expect(events == [.hangDetected(overdueMilliseconds: MainThreadHangWatchdog.detectThresholdMilliseconds)])

        now = MainThreadHangWatchdog.detectThresholdMilliseconds + 250
        pendingMainWork?()
        #expect(
            events == [
                .hangDetected(overdueMilliseconds: MainThreadHangWatchdog.detectThresholdMilliseconds),
                .hangRecovered(durationMilliseconds: MainThreadHangWatchdog.detectThresholdMilliseconds + 250),
            ]
        )

        watchdog.stop()
    }

    @Test func hangMessagesStayWithinRedactionRules() {
        let detected = DiagnosticLogRedactor.redact("hang detected overdueMs=2500")
        let recovered = DiagnosticLogRedactor.redact("hang recovered durationMs=4000")
        #expect(detected == "hang detected overdueMs=2500")
        #expect(recovered == "hang recovered durationMs=4000")
        #expect(!detected.lowercased().contains("password"))
        #expect(!recovered.lowercased().contains("token"))
    }
}
