package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeMediumShape
import dev.opencode.mobile.theme.openCodeColor

enum class OpenCodeChartLegendSwatch {
    Success,
    Warning,
    Error,
    Interactive,
    InteractiveWeak,
    Unobserved,
}

data class OpenCodeChartLegendItem(
    val label: String,
    val swatch: OpenCodeChartLegendSwatch,
)

data class OpenCodeChartAxisLabels(
    val start: String,
    val middle: String? = null,
    val end: String,
)

@Composable
private fun OpenCodeChartLegendSwatch.color(): Color =
    when (this) {
        OpenCodeChartLegendSwatch.Success -> openCodeColor("success")
        OpenCodeChartLegendSwatch.Warning -> openCodeColor("warning")
        OpenCodeChartLegendSwatch.Error -> openCodeColor("error")
        OpenCodeChartLegendSwatch.Interactive -> openCodeColor("interactive")
        OpenCodeChartLegendSwatch.InteractiveWeak -> openCodeColor("interactive").copy(alpha = 0.3f)
        OpenCodeChartLegendSwatch.Unobserved -> openCodeColor("border-weaker-base")
    }

/** The card surface shared by charts and stat tiles. */
@Composable
internal fun OpenCodeChartSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = OpenCodeMediumShape,
        color = openCodeColor("surface-base"),
        border = BorderStroke(1.dp, openCodeColor("border-weak-base")),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/** A titled card around one chart, with an optional summary, time axis, and legend. */
@Composable
fun OpenCodeChartCard(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    axisLabels: OpenCodeChartAxisLabels? = null,
    legend: List<OpenCodeChartLegendItem> = emptyList(),
    content: @Composable () -> Unit,
) {
    val weak = openCodeColor("text-weaker")
    OpenCodeChartSurface(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = openCodeColor("text-strong"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!summary.isNullOrEmpty()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = openCodeColor("text-base"),
                    maxLines = 1,
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth()) { content() }
        if (axisLabels != null) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(text = axisLabels.start, style = MaterialTheme.typography.labelSmall, color = weak, modifier = Modifier.weight(1f))
                if (axisLabels.middle != null) {
                    Text(
                        text = axisLabels.middle,
                        style = MaterialTheme.typography.labelSmall,
                        color = weak,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = axisLabels.end,
                    style = MaterialTheme.typography.labelSmall,
                    color = weak,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (legend.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                legend.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.semantics(mergeDescendants = true) {},
                    ) {
                        val swatchShape = RoundedCornerShape(2.dp)
                        if (item.swatch == OpenCodeChartLegendSwatch.Unobserved) {
                            val stripe = openCodeColor("text-strong")
                            Canvas(
                                modifier =
                                    Modifier
                                        .size(8.dp)
                                        .clip(swatchShape)
                                        .border(0.5.dp, openCodeColor("border-weak-base"), swatchShape),
                            ) {
                                drawOpenCodeUnobserved(stripe, Offset.Zero, size)
                            }
                        } else {
                            Box(
                                modifier =
                                    Modifier
                                        .size(8.dp)
                                        .clip(swatchShape)
                                        .background(item.swatch.color()),
                            )
                        }
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(text = item.label, style = MaterialTheme.typography.labelSmall, color = openCodeColor("text-base"))
                    }
                }
            }
        }
    }
}

/** One headline number on the chart card surface. */
@Composable
fun OpenCodeStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    OpenCodeChartSurface(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = openCodeColor("text-weak"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = openCodeColor("text-strong"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!detail.isNullOrEmpty()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = openCodeColor("text-base"),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

enum class OpenCodeProportionTone {
    Warning,
    Error,
    Info,
}

/** Label, a track filled to [fraction], and a count: one share of a whole. */
@Composable
fun OpenCodeProportionRow(
    label: String,
    count: String,
    fraction: Double,
    modifier: Modifier = Modifier,
    tone: OpenCodeProportionTone = OpenCodeProportionTone.Warning,
) {
    val fill =
        when (tone) {
            OpenCodeProportionTone.Warning -> openCodeColor("warning")
            OpenCodeProportionTone.Error -> openCodeColor("error")
            OpenCodeProportionTone.Info -> openCodeColor("info")
        }
    val track = openCodeColor("border-weaker-base")
    Row(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = openCodeColor("text-strong"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.42f),
        )
        Box(
            modifier =
                Modifier
                    .weight(0.46f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(track),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(fill),
            )
        }
        Text(
            text = count,
            style = MaterialTheme.typography.bodySmall,
            color = openCodeColor("text-base"),
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.weight(0.12f),
        )
    }
}
