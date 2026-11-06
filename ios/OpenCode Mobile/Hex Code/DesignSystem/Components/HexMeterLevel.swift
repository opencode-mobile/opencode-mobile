import Foundation

/// How loud the talk button's meter looks (app plan, "Dictation" › Meter): a cue that you're being
/// heard, not a measurement. Tuned on real phones.
enum HexMeterLevel {
    /// At or below this the bars rest as dots.
    static let floor: Double = -50
    /// At or above this the bars are full.
    static let ceiling: Double = -18
    /// Bars rise this fast…
    static let attack: TimeInterval = 0.04
    /// …and fall this slowly.
    static let release: TimeInterval = 0.22

    /// Bar height, 0 (a resting dot) … 1 (full), for a loudness in dBFS (RMS), on an ease-out
    /// curve so quiet speech already shows.
    static func height(dBFS: Double) -> Double {
        guard dBFS.isFinite, dBFS > floor else { return 0 }
        let t = min(1, (dBFS - floor) / (ceiling - floor))
        return 1 - (1 - t) * (1 - t)
    }

    /// One step of the envelope: towards `target` with a fast attack and a slow release, over
    /// `elapsed` seconds.
    static func follow(_ current: Double, towards target: Double, elapsed: TimeInterval) -> Double {
        let time = target > current ? attack : release
        let step = 1 - exp(-max(0, elapsed) / time)
        return current + (target - current) * step
    }

    /// A small, steady difference between neighbouring bars (about ±12 %), so it looks alive.
    static func variation(bar: Int) -> Double {
        let x = sin(Double(bar) * 12.9898 + 78.233) * 43_758.5453
        let unit = x - x.rounded(.down)
        return 0.88 + 0.24 * unit
    }
}
