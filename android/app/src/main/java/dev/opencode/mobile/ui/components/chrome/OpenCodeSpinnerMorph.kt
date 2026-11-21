package dev.opencode.mobile.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Where the spinner's arc is: its start angle and sweep, in radians. */
internal data class OpenCodeSpinnerPose(
    val start: Double,
    val sweep: Double,
)

/**
 * Pure geometry of the floating pill spinner: one arc that rotates and breathes. Driven by a
 * clock value, so a re-created composable picks up where the last one was.
 */
internal object OpenCodeSpinnerGeometry {
    const val BOX_SIZE = 16f
    const val CENTER = 8.0
    const val RADIUS = 6.0
    const val STROKE_WIDTH = 1.3f
    const val ROTATION_MS = 1_000L
    const val BREATHE_MS = 1_600L
    const val MIN_SWEEP_FRACTION = 0.28
    const val MAX_SWEEP_FRACTION = 0.72

    /** How far the arc's start has advanced at the middle of a breath, as a fraction of a turn. */
    private const val MID_OFFSET_FRACTION = 0.12
    private const val TAU = 2 * PI
    private val BreatheEasing = CubicBezierEasing(0.45f, 0f, 0.3f, 1f)

    /** The still arc shown with animations off. */
    val StillPose = OpenCodeSpinnerPose(start = -PI / 2, sweep = TAU * 0.6)

    fun pose(clockMs: Long): OpenCodeSpinnerPose {
        val rotation = clockMs.mod(ROTATION_MS).toDouble() / ROTATION_MS * TAU
        val phase = clockMs.mod(BREATHE_MS).toDouble() / BREATHE_MS
        val dash: Double
        val offset: Double
        if (phase < 0.5) {
            val eased = BreatheEasing.transform((phase / 0.5).toFloat()).toDouble()
            dash = lerp(MIN_SWEEP_FRACTION, MAX_SWEEP_FRACTION, eased)
            offset = lerp(0.0, MID_OFFSET_FRACTION, eased)
        } else {
            val eased = BreatheEasing.transform(((phase - 0.5) / 0.5).toFloat()).toDouble()
            dash = lerp(MAX_SWEEP_FRACTION, MIN_SWEEP_FRACTION, eased)
            offset = lerp(MID_OFFSET_FRACTION, 1.0, eased)
        }
        return OpenCodeSpinnerPose(start = rotation + offset * TAU, sweep = dash * TAU)
    }

    /** The arc as a polyline of [segments] + 1 points, in the 16-unit box. */
    fun arcPoints(
        pose: OpenCodeSpinnerPose,
        segments: Int = 48,
    ): List<Offset> =
        (0..segments).map { index ->
            val angle = pose.start + pose.sweep * index / segments
            Offset((CENTER + RADIUS * cos(angle)).toFloat(), (CENTER + RADIUS * sin(angle)).toFloat())
        }

    private fun lerp(
        from: Double,
        to: Double,
        progress: Double,
    ) = from + (to - from) * progress
}

/** The spinner's arc bending into the check. Pure, so its endpoints are testable. */
internal class OpenCodeCheckMorph(
    pose: OpenCodeSpinnerPose,
) {
    val targets: List<Offset> = sampledCheck()
    val angles: List<Double>

    init {
        val forward = angles(pose, reversed = false)
        val backward = angles(pose, reversed = true)
        angles = if (travel(forward, targets) <= travel(backward, targets)) forward else backward
    }

    fun points(ms: Double): List<Offset> {
        val time = clamp(ms / DURATION_MS)
        val spin = EXTRA_ROTATION * (1 - (1 - time).pow(3))
        return targets.indices.map { index ->
            val fraction = index.toDouble() / POINT_COUNT
            val bend = easeBend(clamp((time - fraction * STAGGER) / (1 - STAGGER)))
            val angle = angles[index] + spin
            val arcX = OpenCodeSpinnerGeometry.CENTER + OpenCodeSpinnerGeometry.RADIUS * cos(angle)
            val arcY = OpenCodeSpinnerGeometry.CENTER + OpenCodeSpinnerGeometry.RADIUS * sin(angle)
            Offset(
                (arcX + (targets[index].x - arcX) * bend).toFloat(),
                (arcY + (targets[index].y - arcY) * bend).toFloat(),
            )
        }
    }

    companion object {
        const val DURATION_MS = 540.0
        const val POINT_COUNT = 36
        const val END_STROKE_WIDTH = 1f
        const val POP_LEAD_MS = 110.0
        const val POP_DURATION_MS = 240.0
        const val POP_SCALE = 1.09
        const val TOTAL_MS = DURATION_MS - POP_LEAD_MS + POP_DURATION_MS

        val CheckLine = listOf(Offset(2.667f, 8f), Offset(6f, 11.333f), Offset(13.333f, 4f))

        private const val EXTRA_ROTATION = 1.22
        private const val STAGGER = 0.22

        /** The rotation the direction choice assumes the arc gains on its way to the check. */
        private const val COST_ROTATION = 0.6
        private const val POP_PEAK = 0.45
        private val SoftEasing = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)
        private val PopEasing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

        /** The check sampled to [POINT_COUNT] + 1 points, with its corner on a sample. */
        fun sampledCheck(): List<Offset> {
            val (first, corner, last) = CheckLine
            val short = hypot((corner.x - first.x).toDouble(), (corner.y - first.y).toDouble())
            val long = hypot((last.x - corner.x).toDouble(), (last.y - corner.y).toDouble())
            val cornerIndex = (POINT_COUNT * short / (short + long)).roundToInt()
            return (0..POINT_COUNT).map { index ->
                if (index <= cornerIndex) {
                    interpolate(first, corner, index.toFloat() / cornerIndex)
                } else {
                    interpolate(corner, last, (index - cornerIndex).toFloat() / (POINT_COUNT - cornerIndex))
                }
            }
        }

        fun strokeWidth(ms: Double): Float {
            val bend = easeBend(clamp(ms / DURATION_MS)).toFloat()
            return OpenCodeSpinnerGeometry.STROKE_WIDTH + (END_STROKE_WIDTH - OpenCodeSpinnerGeometry.STROKE_WIDTH) * bend
        }

        /** 0 at the live spinner's `text-base`, 1 at the check's `text-strong`. */
        fun colorProgress(ms: Double): Float = SoftEasing.transform(clamp(ms / DURATION_MS).toFloat())

        /** 1, up to [POP_SCALE], and back, in the last moments of the morph. */
        fun scale(ms: Double): Double {
            val progress = clamp((ms - (DURATION_MS - POP_LEAD_MS)) / POP_DURATION_MS)
            if (progress <= 0 || progress >= 1) return 1.0
            val eased = PopEasing.transform(progress.toFloat()).toDouble()
            return if (eased < POP_PEAK) {
                1 + (POP_SCALE - 1) * eased / POP_PEAK
            } else {
                POP_SCALE - (POP_SCALE - 1) * (eased - POP_PEAK) / (1 - POP_PEAK)
            }
        }

        private fun angles(
            pose: OpenCodeSpinnerPose,
            reversed: Boolean,
        ): List<Double> =
            (0..POINT_COUNT).map { index ->
                val fraction = index.toDouble() / POINT_COUNT
                pose.start + pose.sweep * (if (reversed) 1 - fraction else fraction)
            }

        private fun travel(
            angles: List<Double>,
            targets: List<Offset>,
        ): Double =
            angles.indices.sumOf { index ->
                val angle = angles[index] + COST_ROTATION
                val x = OpenCodeSpinnerGeometry.CENTER + OpenCodeSpinnerGeometry.RADIUS * cos(angle)
                val y = OpenCodeSpinnerGeometry.CENTER + OpenCodeSpinnerGeometry.RADIUS * sin(angle)
                hypot(x - targets[index].x, y - targets[index].y)
            }

        private fun interpolate(
            from: Offset,
            to: Offset,
            progress: Float,
        ) = Offset(from.x + (to.x - from.x) * progress, from.y + (to.y - from.y) * progress)

        private fun easeBend(time: Double): Double = if (time < 0.5) 4 * time * time * time else 1 - abs(-2 * time + 2).pow(3) / 2

        private fun clamp(value: Double): Double = value.coerceIn(0.0, 1.0)
    }
}
