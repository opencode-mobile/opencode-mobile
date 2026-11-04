// Stand-ins for Hex types the mirrored files mention but Hex Code doesn't mirror.

/// Hex's DotScope animation lives in a large UI file; the audio backend only needs its loudness curve.
nonisolated enum DotScopeSimulation {
    static func loudness(fromNormalized level: Float) -> Float {
        let clamped = min(1.0, max(0.0, level))
        return clamped <= 0.001 ? -90.0 : (-54.0 + 34.0 * clamped)
    }
}
