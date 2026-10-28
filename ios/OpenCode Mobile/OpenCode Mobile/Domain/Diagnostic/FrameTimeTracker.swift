import Foundation

/// Whether the performance HUD should be on screen.
enum PerformanceOverlayGate {
    static func isVisible(developerToolsUnlocked: Bool, enabled: Bool) -> Bool {
        developerToolsUnlocked && enabled
    }
}

/// One sample of the live frame-time HUD.
struct FrameTimeSnapshot: Equatable {
    var framesPerSecond: Int?
    var frameMilliseconds: Int?
    var spikeCount: Int
    var worstSpikeMilliseconds: Int?
    var spikeActive: Bool

    var label: String {
        let fps = framesPerSecond.map(String.init) ?? "—"
        let frame = frameMilliseconds.map { "\($0) ms" } ?? "— ms"
        let worst = worstSpikeMilliseconds.map { "\($0) ms" } ?? "—"
        return "\(fps) fps\n\(frame)\nspikes \(spikeCount)\nworst \(worst)"
    }

    static let idle = FrameTimeSnapshot(
        framesPerSecond: nil,
        frameMilliseconds: nil,
        spikeCount: 0,
        worstSpikeMilliseconds: nil,
        spikeActive: false
    )
}

/// Turns display timestamps into FPS and hitch spikes.
///
/// A spike is a frame at least ``spikeMultiple`` times the display vsync.
/// Gaps of ``suspendedGapSeconds`` or more are a resume, not a frame.
struct FrameTimeTracker {
    static let spikeMultiple = 2.0
    static let smoothingWindowSeconds = 0.5
    static let spikeHighlightSeconds = 1.0
    static let suspendedGapSeconds = 2.0

    private var lastTimestamp: Double?
    private var recent: [(timestamp: Double, delta: Double)] = []
    private var spikeCount = 0
    private var worstSpikeSeconds = 0.0
    private var lastSpikeTimestamp: Double?
    private var lastFrameSeconds: Double?

    mutating func record(timestamp: Double, expectedInterval: Double) -> FrameTimeSnapshot {
        guard expectedInterval > 0, expectedInterval < 0.1 else {
            return snapshot(at: timestamp, expectedInterval: expectedInterval)
        }
        guard let last = lastTimestamp else {
            lastTimestamp = timestamp
            return snapshot(at: timestamp, expectedInterval: expectedInterval)
        }
        let delta = timestamp - last
        lastTimestamp = timestamp
        guard delta > 0 else {
            return snapshot(at: timestamp, expectedInterval: expectedInterval)
        }
        if delta >= Self.suspendedGapSeconds {
            recent.removeAll()
            return snapshot(at: timestamp, expectedInterval: expectedInterval)
        }
        recent.append((timestamp, delta))
        let windowStart = timestamp - Self.smoothingWindowSeconds
        recent.removeAll { $0.timestamp < windowStart }
        lastFrameSeconds = delta
        if delta >= expectedInterval * Self.spikeMultiple {
            spikeCount += 1
            if delta > worstSpikeSeconds {
                worstSpikeSeconds = delta
            }
            lastSpikeTimestamp = timestamp
        }
        return snapshot(at: timestamp, expectedInterval: expectedInterval)
    }

    private func snapshot(at timestamp: Double, expectedInterval: Double) -> FrameTimeSnapshot {
        FrameTimeSnapshot(
            framesPerSecond: framesPerSecond(expectedInterval: expectedInterval),
            frameMilliseconds: lastFrameSeconds.map { Self.milliseconds($0) },
            spikeCount: spikeCount,
            worstSpikeMilliseconds: spikeCount > 0 ? Self.milliseconds(worstSpikeSeconds) : nil,
            spikeActive: lastSpikeTimestamp.map { timestamp - $0 <= Self.spikeHighlightSeconds } ?? false
        )
    }

    private func framesPerSecond(expectedInterval: Double) -> Int? {
        guard !recent.isEmpty else { return nil }
        let mean = recent.reduce(0.0) { $0 + $1.delta } / Double(recent.count)
        guard mean > 0 else { return nil }
        let raw = Int((1.0 / mean).rounded())
        guard expectedInterval > 0 else { return raw }
        let refresh = Int((1.0 / expectedInterval).rounded())
        return min(raw, max(refresh, 1))
    }

    private static func milliseconds(_ seconds: Double) -> Int {
        Int((seconds * 1_000).rounded())
    }
}
