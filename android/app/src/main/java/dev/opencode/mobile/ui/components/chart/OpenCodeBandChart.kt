package dev.opencode.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** What a column of [OpenCodeBandChart] stands on. */
enum class OpenCodeBandChartBackground {
    None,
    Warning,
    Error,

    /** Nothing was measured in this column. */
    Unobserved,
}

data class OpenCodeBandChartPoint(
    val median: Double?,
    val upper: Double?,
    val background: OpenCodeBandChartBackground = OpenCodeBandChartBackground.None,
)

private const val BAND_ALPHA = 0.18f
private const val BACKGROUND_ERROR_ALPHA = 0.16f
private const val BACKGROUND_WARNING_ALPHA = 0.12f
private const val BACKGROUND_UNOBSERVED_ALPHA = 0.10f
private const val BAND_CHART_MIN_MAX = 40.0

/** Rounds up to 1, 2, 2.5 or 5 times a power of ten. */
internal fun openCodeNiceCeiling(value: Double): Double {
    if (value <= 0.0) return 0.0
    val magnitude = 10.0.pow(floor(log10(value)))
    val normalized = value / magnitude
    val step =
        listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it >= normalized - 1e-9 }
    return step * magnitude
}

internal fun openCodeBandChartMax(points: List<OpenCodeBandChartPoint>): Double {
    val top = points.maxOfOrNull { it.upper ?: it.median ?: 0.0 } ?: 0.0
    return max(openCodeNiceCeiling(top * 1.08), BAND_CHART_MIN_MAX)
}

/**
 * Median line over a band up to an upper value (such as p95), per equal-width column, on
 * column backgrounds that mark trouble or unmeasured time. [contentDescription] summarizes it.
 */
@Composable
fun OpenCodeBandChart(
    points: List<OpenCodeBandChartPoint>,
    valueLabel: (Double) -> String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val interactive = openCodeColor("interactive")
    val error = openCodeColor("error")
    val warning = openCodeColor("warning")
    val unobserved = openCodeColor("text-weaker")
    val grid = openCodeColor("border-weak-base")
    val labelColor = openCodeColor("text-weaker")
    val yMax = openCodeBandChartMax(points)
    val semanticsModifier =
        if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Box(modifier = modifier.then(semanticsModifier)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (points.isEmpty()) return@Canvas
            val slot = size.width / points.size

            fun x(index: Int): Float = slot * index + slot / 2f

            fun y(value: Double): Float = size.height - (value / yMax).toFloat().coerceIn(0f, 1f) * size.height
            points.forEachIndexed { index, point ->
                val left = slot * index
                when (point.background) {
                    OpenCodeBandChartBackground.None -> Unit
                    OpenCodeBandChartBackground.Error ->
                        drawRect(error.copy(alpha = BACKGROUND_ERROR_ALPHA), Offset(left, 0f), Size(slot, size.height))
                    OpenCodeBandChartBackground.Warning ->
                        drawRect(warning.copy(alpha = BACKGROUND_WARNING_ALPHA), Offset(left, 0f), Size(slot, size.height))
                    OpenCodeBandChartBackground.Unobserved ->
                        clipRect(left = left, top = 0f, right = left + slot, bottom = size.height) {
                            val spacing = 4.dp.toPx()
                            var start = left - size.height
                            while (start < left + slot) {
                                drawLine(
                                    color = unobserved.copy(alpha = BACKGROUND_UNOBSERVED_ALPHA * 2f),
                                    start = Offset(start, size.height),
                                    end = Offset(start + size.height, 0f),
                                    strokeWidth = 1.dp.toPx(),
                                )
                                start += spacing
                            }
                        }
                }
            }
            drawLine(
                color = grid,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
            )
            drawLine(
                color = grid,
                start = Offset(0f, size.height - 0.5.dp.toPx()),
                end = Offset(size.width, size.height - 0.5.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
            val runs = mutableListOf<List<Int>>()
            var run = mutableListOf<Int>()
            points.forEachIndexed { index, point ->
                if (point.median == null) {
                    if (run.isNotEmpty()) runs += run
                    run = mutableListOf()
                } else {
                    run += index
                }
            }
            if (run.isNotEmpty()) runs += run
            val strokeWidth = 1.5.dp.toPx()
            for (indices in runs) {
                val band = Path()
                indices.forEachIndexed { position, index ->
                    val point = points[index]
                    val top = y(point.upper ?: point.median!!)
                    if (position == 0) band.moveTo(x(index), top) else band.lineTo(x(index), top)
                }
                indices.asReversed().forEach { index -> band.lineTo(x(index), y(points[index].median!!)) }
                band.close()
                drawPath(band, interactive.copy(alpha = BAND_ALPHA))
                if (indices.size == 1) {
                    val index = indices.first()
                    drawCircle(interactive, radius = strokeWidth, center = Offset(x(index), y(points[index].median!!)))
                    continue
                }
                val line = Path()
                indices.forEachIndexed { position, index ->
                    val point = Offset(x(index), y(points[index].median!!))
                    if (position == 0) line.moveTo(point.x, point.y) else line.lineTo(point.x, point.y)
                }
                drawPath(line, interactive, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Text(
            text = valueLabel(yMax),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 2.dp),
        )
        Text(
            text = valueLabel(ceil(yMax / 2.0)),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp, bottom = 14.dp),
        )
    }
}
