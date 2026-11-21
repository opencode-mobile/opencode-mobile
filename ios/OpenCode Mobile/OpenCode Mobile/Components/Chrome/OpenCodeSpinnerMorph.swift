import CoreGraphics
import Foundation

/// Where the spinner's arc is: its start angle and sweep, in radians.
struct OpenCodeSpinnerPose: Equatable {
    var start: Double
    var sweep: Double
}

/// Pure geometry of the floating pill spinner: one arc that rotates and breathes.
/// Driven by a clock value, so a re-created view picks up where the last one was.
enum OpenCodeSpinnerGeometry {
    static let boxSize: CGFloat = 16
    static let center: Double = 8
    static let radius: Double = 6
    static let strokeWidth: CGFloat = 1.3
    static let rotationSeconds: Double = 1
    static let breatheSeconds: Double = 1.6
    static let minSweepFraction: Double = 0.28
    static let maxSweepFraction: Double = 0.72
    /// The still arc shown under Reduce Motion.
    static let stillPose = OpenCodeSpinnerPose(start: -.pi / 2, sweep: 2 * .pi * 0.6)

    private static let tau = 2 * Double.pi
    /// How far the arc's start has advanced at the middle of a breath, as a fraction of a turn.
    private static let midOffsetFraction = 0.12

    static func pose(atSeconds seconds: Double) -> OpenCodeSpinnerPose {
        let rotation = positiveRemainder(seconds, rotationSeconds) / rotationSeconds * tau
        let phase = positiveRemainder(seconds, breatheSeconds) / breatheSeconds
        let dash: Double
        let offset: Double
        if phase < 0.5 {
            let eased = OpenCodeMotionCurve.breathe(phase / 0.5)
            dash = lerp(minSweepFraction, maxSweepFraction, eased)
            offset = lerp(0, midOffsetFraction, eased)
        } else {
            let eased = OpenCodeMotionCurve.breathe((phase - 0.5) / 0.5)
            dash = lerp(maxSweepFraction, minSweepFraction, eased)
            offset = lerp(midOffsetFraction, 1, eased)
        }
        return OpenCodeSpinnerPose(start: rotation + offset * tau, sweep: dash * tau)
    }

    /// The arc as a polyline of `segments + 1` points.
    static func arcPoints(_ pose: OpenCodeSpinnerPose, segments: Int = 48) -> [CGPoint] {
        (0 ... segments).map { index in
            let angle = pose.start + pose.sweep * Double(index) / Double(segments)
            return CGPoint(x: center + radius * cos(angle), y: center + radius * sin(angle))
        }
    }

    private static func positiveRemainder(_ value: Double, _ divisor: Double) -> Double {
        let remainder = value.truncatingRemainder(dividingBy: divisor)
        return remainder < 0 ? remainder + divisor : remainder
    }

    private static func lerp(_ from: Double, _ to: Double, _ progress: Double) -> Double {
        from + (to - from) * progress
    }
}

/// CSS-style cubic Bézier easing, solved numerically.
enum OpenCodeMotionCurve {
    static func breathe(_ progress: Double) -> Double { cubic(0.45, 0, 0.3, 1, progress) }
    static func soft(_ progress: Double) -> Double { cubic(0.3, 0, 0.2, 1, progress) }
    static func easeOut(_ progress: Double) -> Double { cubic(0, 0, 0.58, 1, progress) }

    static func cubic(_ x1: Double, _ y1: Double, _ x2: Double, _ y2: Double, _ progress: Double) -> Double {
        let target = min(max(progress, 0), 1)
        if target == 0 || target == 1 { return target }
        var low = 0.0
        var high = 1.0
        for _ in 0 ..< 32 {
            let mid = (low + high) / 2
            if bezier(x1, x2, mid) < target { low = mid } else { high = mid }
        }
        return bezier(y1, y2, (low + high) / 2)
    }

    private static func bezier(_ first: Double, _ second: Double, _ t: Double) -> Double {
        let inverse = 1 - t
        return 3 * inverse * inverse * t * first + 3 * inverse * t * t * second + t * t * t
    }
}

/// The spinner's arc bending into the check. Pure, so its endpoints are testable.
struct OpenCodeCheckMorph: Equatable {
    static let durationMs: Double = 540
    static let pointCount = 36
    static let checkLine = [CGPoint(x: 2.667, y: 8), CGPoint(x: 6, y: 11.333), CGPoint(x: 13.333, y: 4)]
    static let endStrokeWidth: CGFloat = 1
    /// The pop starts this long before the morph ends and lasts `popDurationMs`.
    static let popLeadMs: Double = 110
    static let popDurationMs: Double = 240
    static let popScale: Double = 1.09
    /// When the morph and its pop have both finished.
    static var totalMs: Double { durationMs - popLeadMs + popDurationMs }

    private static let extraRotation = 1.22
    private static let stagger = 0.22
    /// The rotation the direction choice assumes the arc gains on its way to the check.
    private static let costRotation = 0.6

    let targets: [CGPoint]
    let angles: [Double]

    init(pose: OpenCodeSpinnerPose) {
        let targets = Self.sampledCheck()
        let forward = Self.angles(pose, reversed: false)
        let backward = Self.angles(pose, reversed: true)
        self.targets = targets
        angles = Self.travel(forward, targets) <= Self.travel(backward, targets) ? forward : backward
    }

    /// The check sampled to `pointCount + 1` points, with its corner on a sample.
    static func sampledCheck() -> [CGPoint] {
        let (first, corner, last) = (checkLine[0], checkLine[1], checkLine[2])
        let short = hypot(corner.x - first.x, corner.y - first.y)
        let long = hypot(last.x - corner.x, last.y - corner.y)
        let cornerIndex = Int((Double(pointCount) * Double(short / (short + long))).rounded())
        return (0 ... pointCount).map { index in
            if index <= cornerIndex {
                return interpolate(first, corner, Double(index) / Double(cornerIndex))
            }
            return interpolate(corner, last, Double(index - cornerIndex) / Double(pointCount - cornerIndex))
        }
    }

    func points(atMs ms: Double) -> [CGPoint] {
        let time = Self.clamp(ms / Self.durationMs)
        let spin = Self.extraRotation * (1 - pow(1 - time, 3))
        return targets.indices.map { index in
            let fraction = Double(index) / Double(Self.pointCount)
            let bend = Self.easeBend(Self.clamp((time - fraction * Self.stagger) / (1 - Self.stagger)))
            let angle = angles[index] + spin
            let arc = Self.arcPoint(angle)
            return Self.interpolate(arc, targets[index], bend)
        }
    }

    static func strokeWidth(atMs ms: Double) -> CGFloat {
        let bend = easeBend(clamp(ms / durationMs))
        return OpenCodeSpinnerGeometry.strokeWidth
            + (endStrokeWidth - OpenCodeSpinnerGeometry.strokeWidth) * CGFloat(bend)
    }

    /// 0 at the live spinner's `text-base`, 1 at the check's `text-strong`.
    static func colorProgress(atMs ms: Double) -> Double {
        OpenCodeMotionCurve.soft(clamp(ms / durationMs))
    }

    /// 1, up to `popScale`, and back, in the last moments of the morph.
    static func scale(atMs ms: Double) -> Double {
        let progress = clamp((ms - (durationMs - popLeadMs)) / popDurationMs)
        guard progress > 0, progress < 1 else { return 1 }
        let eased = OpenCodeMotionCurve.easeOut(progress)
        let peak = 0.45
        if eased < peak { return 1 + (popScale - 1) * eased / peak }
        return popScale - (popScale - 1) * (eased - peak) / (1 - peak)
    }

    private static func angles(_ pose: OpenCodeSpinnerPose, reversed: Bool) -> [Double] {
        (0 ... pointCount).map { index in
            let fraction = Double(index) / Double(pointCount)
            return pose.start + pose.sweep * (reversed ? 1 - fraction : fraction)
        }
    }

    private static func travel(_ angles: [Double], _ targets: [CGPoint]) -> Double {
        zip(angles, targets).reduce(0) { total, pair in
            let point = arcPoint(pair.0 + costRotation)
            return total + Double(hypot(point.x - pair.1.x, point.y - pair.1.y))
        }
    }

    private static func arcPoint(_ angle: Double) -> CGPoint {
        CGPoint(
            x: OpenCodeSpinnerGeometry.center + OpenCodeSpinnerGeometry.radius * cos(angle),
            y: OpenCodeSpinnerGeometry.center + OpenCodeSpinnerGeometry.radius * sin(angle)
        )
    }

    private static func interpolate(_ from: CGPoint, _ to: CGPoint, _ progress: Double) -> CGPoint {
        CGPoint(
            x: from.x + (to.x - from.x) * CGFloat(progress),
            y: from.y + (to.y - from.y) * CGFloat(progress)
        )
    }

    private static func easeBend(_ time: Double) -> Double {
        time < 0.5 ? 4 * time * time * time : 1 - pow(-2 * time + 2, 3) / 2
    }

    private static func clamp(_ value: Double) -> Double {
        min(max(value, 0), 1)
    }
}
