import Foundation

/// Background main-thread hang watchdog (OPE-134).
/// Pings the main queue from a dedicated background queue; when acknowledgment
/// is overdue past ``detectThresholdMilliseconds``, writes a redacted
/// `hang` Diagnostic Log line. Logs recovery when the main queue responds again.
/// Does not block the main thread.
nonisolated final class MainThreadHangWatchdog: @unchecked Sendable {
    static let shared = MainThreadHangWatchdog()

    /// Overdue ping age that counts as a hang (2.5s; within the 2–3s product band).
    static let detectThresholdMilliseconds: Int64 = 2_500

    /// How often the background loop checks / issues a ping.
    static let pingIntervalMilliseconds: UInt64 = 500

    private let lock = NSLock()
    private var detector = MainThreadHangDetector()
    private var timer: DispatchSourceTimer?
    private let queue = DispatchQueue(label: "dev.opencode.mobile.hang-watchdog")
    private let clock: () -> Int64
    private let scheduleOnMain: (@escaping () -> Void) -> Void
    private let logEvent: (MainThreadHangDetector.Event) -> Void

    init(
        clock: @escaping () -> Int64 = {
            Int64(Date().timeIntervalSince1970 * 1_000)
        },
        scheduleOnMain: @escaping (@escaping () -> Void) -> Void = { work in
            DispatchQueue.main.async(execute: work)
        },
        logEvent: @escaping (MainThreadHangDetector.Event) -> Void = { event in
            MainThreadHangWatchdog.writeDiagnostic(event)
        }
    ) {
        self.clock = clock
        self.scheduleOnMain = scheduleOnMain
        self.logEvent = logEvent
    }

    func start() {
        lock.lock()
        defer { lock.unlock() }
        guard timer == nil else { return }

        let source = DispatchSource.makeTimerSource(queue: queue)
        source.schedule(
            deadline: .now(),
            repeating: .milliseconds(Int(Self.pingIntervalMilliseconds)),
            leeway: .milliseconds(50)
        )
        source.setEventHandler { [weak self] in
            self?.tick()
        }
        timer = source
        source.resume()
    }

    func stop() {
        lock.lock()
        defer { lock.unlock() }
        timer?.cancel()
        timer = nil
        detector = MainThreadHangDetector()
    }

    /// Test seam: drive one watchdog tick without waiting on the timer.
    func tickForTesting() {
        tick()
    }

    private func tick() {
        lock.lock()
        let now = clock()
        if let event = detector.evaluate(
            now: now,
            thresholdMilliseconds: Self.detectThresholdMilliseconds
        ) {
            lock.unlock()
            logEvent(event)
            lock.lock()
        }

        let shouldPing = !detector.hasPendingPing
        if shouldPing {
            detector.notePingSent(at: now)
            lock.unlock()
            scheduleOnMain { [weak self] in
                self?.acknowledgeOnMain()
            }
        } else {
            lock.unlock()
        }
    }

    private func acknowledgeOnMain() {
        lock.lock()
        let now = clock()
        let event = detector.notePingAcknowledged(at: now)
        lock.unlock()
        if let event {
            logEvent(event)
        }
    }

    private static func writeDiagnostic(_ event: MainThreadHangDetector.Event) {
        switch event {
        case let .hangDetected(overdueMilliseconds):
            DiagnosticLog.warning(
                .hang,
                "hang detected overdueMs=\(overdueMilliseconds)"
            )
        case let .hangRecovered(durationMilliseconds):
            DiagnosticLog.info(
                .hang,
                "hang recovered durationMs=\(durationMilliseconds)"
            )
        }
    }
}
