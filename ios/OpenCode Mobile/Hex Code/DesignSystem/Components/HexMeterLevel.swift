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
    /// With Reduce Motion the pose changes at once instead of easing; the bars still follow the voice.
    static func follow(_ current: Double, towards target: Double, elapsed: TimeInterval, reduced: Bool) -> Double {
        reduced ? target : follow(current, towards: target, elapsed: elapsed)
    }

    /// The listening wave's clock: frozen with Reduce Motion, so the bars stand still at the wave's
    /// shape (the prototype freezes its phase).
    static func waveTime(_ time: TimeInterval, reduced: Bool) -> TimeInterval {
        reduced ? 0 : time
    }

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

    // MARK: Before audio arrives

    /// Real levels blend in over this long once audio arrives.
    static let blendTime: TimeInterval = 0.08

    /// The "listening" wave the bars run from the touch until audio arrives: about a third of the
    /// height, a crest travelling left to right.
    static func listening(bar: Int, at time: TimeInterval) -> Double {
        let phase = 2 * Double.pi * (time * 1.1 - Double(bar) / 14)
        return 0.32 + 0.1 * sin(phase)
    }

    /// A bar's height: the listening wave until audio arrives (`sinceAudio` nil), then the real
    /// level blending in over `blendTime`.
    static func blend(listening: Double, real: Double, sinceAudio: TimeInterval?) -> Double {
        guard let sinceAudio else { return listening }
        let t = max(0, sinceAudio / blendTime)
        guard t < 1 else { return real }
        return listening + (real - listening) * t
    }
}
