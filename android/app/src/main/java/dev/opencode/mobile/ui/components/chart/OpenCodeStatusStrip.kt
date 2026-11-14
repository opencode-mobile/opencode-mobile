package dev.opencode.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor

enum class OpenCodeStatusStripSegment {
    Success,
    Warning,
    Error,
    Unobserved,
}

/** A row of equal-width bars, one per period, colored by how that period went. */
@Composable
fun OpenCodeStatusStrip(
    segments: List<OpenCodeStatusStripSegment>,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val success = openCodeColor("success")
    val warning = openCodeColor("warning")
    val error = openCodeColor("error")
    val unobserved = openCodeColor("border-weaker-base")
    val semanticsModifier =
        if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Canvas(modifier = modifier.fillMaxWidth().height(14.dp).then(semanticsModifier)) {
        if (segments.isEmpty()) return@Canvas
        val gap = (if (segments.size > 70) 0.5.dp else 1.dp).toPx()
        val width = ((size.width - gap * (segments.size - 1)) / segments.size).coerceAtLeast(0.5f)
        val radius = CornerRadius(minOf(1.5.dp.toPx(), width / 2f))
        segments.forEachIndexed { index, segment ->
            val color =
                when (segment) {
                    OpenCodeStatusStripSegment.Success -> success
                    OpenCodeStatusStripSegment.Warning -> warning
                    OpenCodeStatusStripSegment.Error -> error
                    OpenCodeStatusStripSegment.Unobserved -> unobserved
                }
            drawRoundRect(
                color = color,
                topLeft = Offset(index * (width + gap), 0f),
                size = Size(width, size.height),
                cornerRadius = radius,
            )
        }
    }
}

/** Bars of [values] on a baseline, 70% of each slot wide, with the largest value labeled. */
@Composable
fun OpenCodeBarChart(
    values: List<Double>,
    modifier: Modifier = Modifier,
    tone: OpenCodeChartTone = OpenCodeChartTone.Warning,
    valueLabel: (Double) -> String = { it.toInt().toString() },
    contentDescription: String? = null,
) {
    val barColor = tone.color()
    val baseline = openCodeColor("border-weak-base")
    val labelColor = openCodeColor("text-weaker")
    val maxValue = values.maxOrNull() ?: 0.0
    val semanticsModifier =
        if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Box(modifier = modifier.then(semanticsModifier)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawLine(
                color = baseline,
                start = Offset(0f, size.height - 0.5.dp.toPx()),
                end = Offset(size.width, size.height - 0.5.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
            if (values.isEmpty() || maxValue <= 0.0) return@Canvas
            val slot = size.width / values.size
            val barWidth = slot * 0.7f
            val top = 14.dp.toPx()
            val usable = size.height - top
            val radius = CornerRadius(minOf(1.5.dp.toPx(), barWidth / 2f))
            values.forEachIndexed { index, value ->
                if (value <= 0.0) return@forEachIndexed
                val height = (value / maxValue).toFloat() * usable
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(slot * index + (slot - barWidth) / 2f, size.height - height),
                    size = Size(barWidth, height),
                    cornerRadius = radius,
                )
            }
        }
        if (maxValue > 0.0) {
            Text(
                text = valueLabel(maxValue),
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 2.dp),
            )
        }
    }
}
