package dev.opencode.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor
import kotlin.math.max

/** Semantic color of a chart's marks. */
enum class OpenCodeChartTone {
    Success,
    Warning,
    Error,
    Interactive,
}

@Composable
internal fun OpenCodeChartTone.color(): Color =
    when (this) {
        OpenCodeChartTone.Success -> openCodeColor("success")
        OpenCodeChartTone.Warning -> openCodeColor("warning")
        OpenCodeChartTone.Error -> openCodeColor("error")
        OpenCodeChartTone.Interactive -> openCodeColor("interactive")
    }

private const val SPARKLINE_FILL_ALPHA = 0.22f
private const val SPARKLINE_GAP_ALPHA = 0.14f

/**
 * A small line of recent values that fills the size it is given. A null value breaks the line;
 * with [showsGaps] its column is tinted as a failure. The last point gets a dot when it has a value.
 */
@Composable
fun OpenCodeSparkline(
    values: List<Double?>,
    modifier: Modifier = Modifier,
    tone: OpenCodeChartTone = OpenCodeChartTone.Success,
    showsGaps: Boolean = true,
    contentDescription: String? = null,
) {
    val lineColor = tone.color()
    val gapColor = openCodeColor("error").copy(alpha = SPARKLINE_GAP_ALPHA)
    val semanticsModifier =
        if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Canvas(modifier = modifier.then(semanticsModifier)) {
        if (values.isEmpty()) return@Canvas
        val present = values.filterNotNull()
        val count = values.size
        val slot = size.width / count

        fun x(index: Int): Float = if (count == 1) size.width / 2f else index * (size.width / (count - 1))
        if (showsGaps) {
            values.forEachIndexed { index, value ->
                if (value != null) return@forEachIndexed
                val left = (x(index) - slot / 2f).coerceAtLeast(0f)
                val right = (x(index) + slot / 2f).coerceAtMost(size.width)
                drawRect(gapColor, topLeft = Offset(left, 0f), size = Size(max(right - left, 1f), size.height))
            }
        }
        if (present.isEmpty()) return@Canvas
        val strokeWidth = 1.5.dp.toPx()
        val inset = strokeWidth + 2.dp.toPx()
        val low = present.min()
        val high = present.max()
        val pad = max((high - low) * 0.15, 1.0)
        val minY = (low - pad).coerceAtLeast(0.0)
        val maxY = high + pad

        fun y(value: Double): Float {
            val fraction = ((value - minY) / (maxY - minY)).toFloat()
            return inset + (1f - fraction) * (size.height - inset * 2f)
        }
        val runs = mutableListOf<List<Int>>()
        var run = mutableListOf<Int>()
        values.forEachIndexed { index, value ->
            if (value == null) {
                if (run.isNotEmpty()) runs += run
                run = mutableListOf()
            } else {
                run += index
            }
        }
        if (run.isNotEmpty()) runs += run
        val fillBrush =
            Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = SPARKLINE_FILL_ALPHA), lineColor.copy(alpha = 0f)),
                startY = 0f,
                endY = size.height,
            )
        for (indices in runs) {
            if (indices.size == 1) {
                val index = indices.first()
                drawCircle(lineColor, radius = strokeWidth, center = Offset(x(index), y(values[index]!!)))
                continue
            }
            val line = Path()
            indices.forEachIndexed { position, index ->
                val point = Offset(x(index), y(values[index]!!))
                if (position == 0) line.moveTo(point.x, point.y) else line.lineTo(point.x, point.y)
            }
            val fill =
                Path().apply {
                    addPath(line)
                    lineTo(x(indices.last()), size.height)
                    lineTo(x(indices.first()), size.height)
                    close()
                }
            drawPath(fill, fillBrush)
            drawPath(line, lineColor, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        values.last()?.let { last ->
            drawCircle(lineColor, radius = 2.5.dp.toPx(), center = Offset(x(count - 1), y(last)))
        }
    }
}
