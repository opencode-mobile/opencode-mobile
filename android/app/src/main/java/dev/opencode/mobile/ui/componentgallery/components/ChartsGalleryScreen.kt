package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeBandChart
import dev.opencode.mobile.ui.components.OpenCodeBandChartBackground
import dev.opencode.mobile.ui.components.OpenCodeBandChartPoint
import dev.opencode.mobile.ui.components.OpenCodeBarChart
import dev.opencode.mobile.ui.components.OpenCodeChartAxisLabels
import dev.opencode.mobile.ui.components.OpenCodeChartCard
import dev.opencode.mobile.ui.components.OpenCodeChartLegendItem
import dev.opencode.mobile.ui.components.OpenCodeChartLegendSwatch
import dev.opencode.mobile.ui.components.OpenCodeChartTone
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemStatus
import dev.opencode.mobile.ui.components.OpenCodeListItemStatusTone
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeProportionRow
import dev.opencode.mobile.ui.components.OpenCodeProportionTone
import dev.opencode.mobile.ui.components.OpenCodeSparkline
import dev.opencode.mobile.ui.components.OpenCodeStatTile
import dev.opencode.mobile.ui.components.OpenCodeStatusStrip
import dev.opencode.mobile.ui.components.OpenCodeStatusStripSegment
import dev.opencode.mobile.ui.components.StartDecorator
import kotlin.math.sin

internal val sparkValues: List<Double?> =
    (0 until 60).map { index -> if (index in 38..40) null else 24.0 + 6 * sin(index / 4.0) + (index % 7) }

internal val bandPoints: List<OpenCodeBandChartPoint> =
    (0 until 48).map { index ->
        when (index) {
            in 0..5 -> OpenCodeBandChartPoint(null, null, OpenCodeBandChartBackground.Unobserved)
            in 20..21 -> OpenCodeBandChartPoint(null, null, OpenCodeBandChartBackground.Error)
            22 -> OpenCodeBandChartPoint(60.0, 140.0, OpenCodeBandChartBackground.Warning)
            else -> OpenCodeBandChartPoint(22.0 + (index % 5) * 3, 48.0 + (index % 9) * 6)
        }
    }

internal val stripSegments: List<OpenCodeStatusStripSegment> =
    (0 until 96).map { index ->
        when {
            index < 10 -> OpenCodeStatusStripSegment.Unobserved
            index in 40..42 -> OpenCodeStatusStripSegment.Error
            index in 43..45 || index == 70 -> OpenCodeStatusStripSegment.Warning
            else -> OpenCodeStatusStripSegment.Success
        }
    }

@Composable
private fun GalleryLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelSmall, color = openCodeColor("text-weaker"))
}

@Composable
fun ChartsGalleryScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GalleryLabel("Sparkline · success, with a gap")
        ChartsGallerySparkline(tone = OpenCodeChartTone.Success, height = 56.dp)
        GalleryLabel("Sparkline · warning, gaps hidden")
        ChartsGallerySparkline(tone = OpenCodeChartTone.Warning, height = 40.dp, showsGaps = false)
        ChartsGalleryBandCard()
        ChartsGalleryStripCard()
        ChartsGalleryBarCard()
        ChartsGalleryStatTiles()
        ChartsGalleryProportionCard()
        GalleryLabel("List Item · footer")
        ChartsGalleryListItemFooter()
    }
}

// The samples below are shared with the debug Component Capture registry.

@Composable
internal fun ChartsGallerySparkline(
    tone: OpenCodeChartTone,
    height: Dp,
    showsGaps: Boolean = true,
) {
    OpenCodeSparkline(values = sparkValues, tone = tone, showsGaps = showsGaps, modifier = Modifier.fillMaxWidth().height(height))
}

@Composable
internal fun ChartsGalleryBandCard() {
    OpenCodeChartCard(
        title = "Latency",
        summary = "median 24 ms · p95 61 ms",
        axisLabels = OpenCodeChartAxisLabels(start = "24 h ago", end = "now"),
        legend =
            listOf(
                OpenCodeChartLegendItem("Median", OpenCodeChartLegendSwatch.Interactive),
                OpenCodeChartLegendItem("p95", OpenCodeChartLegendSwatch.InteractiveWeak),
                OpenCodeChartLegendItem("Offline", OpenCodeChartLegendSwatch.Error),
                OpenCodeChartLegendItem("Not measured", OpenCodeChartLegendSwatch.Unobserved),
            ),
    ) {
        OpenCodeBandChart(
            points = bandPoints,
            valueLabel = { "${it.toInt()} ms" },
            modifier = Modifier.fillMaxWidth().height(120.dp),
        )
    }
}

@Composable
internal fun ChartsGalleryStripCard() {
    OpenCodeChartCard(
        title = "Connection",
        summary = "97.4% live",
        axisLabels = OpenCodeChartAxisLabels(start = "24 h ago", middle = "12 h", end = "now"),
        legend =
            listOf(
                OpenCodeChartLegendItem("Live", OpenCodeChartLegendSwatch.Success),
                OpenCodeChartLegendItem("Unsteady", OpenCodeChartLegendSwatch.Warning),
                OpenCodeChartLegendItem("Down", OpenCodeChartLegendSwatch.Error),
                OpenCodeChartLegendItem("Not measured", OpenCodeChartLegendSwatch.Unobserved),
            ),
    ) {
        OpenCodeStatusStrip(segments = stripSegments)
    }
}

@Composable
internal fun ChartsGalleryBarCard() {
    OpenCodeChartCard(title = "Reconnects", summary = "4 reconnects") {
        OpenCodeBarChart(
            values = (0 until 48).map { if (it % 11 == 3) (it % 3 + 1).toDouble() else 0.0 },
            unobservedSlots = (18..21).toSet(),
            tone = OpenCodeChartTone.Warning,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        )
    }
}

@Composable
internal fun ChartsGalleryStatTiles() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OpenCodeStatTile(label = "Uptime", value = "99.97%", detail = "of 6 h 12 m measured", modifier = Modifier.weight(1f))
        OpenCodeStatTile(label = "Reconnects", value = "0", detail = "no drops", modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun ChartsGalleryProportionCard() {
    OpenCodeChartCard(title = "Errors") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeProportionRow(label = "Heartbeat stall", count = "6", fraction = 0.6, tone = OpenCodeProportionTone.Warning)
            OpenCodeProportionRow(label = "Server error", count = "3", fraction = 0.3, tone = OpenCodeProportionTone.Error)
            OpenCodeProportionRow(label = "Other", count = "1", fraction = 0.1, tone = OpenCodeProportionTone.Info)
        }
    }
}

@Composable
internal fun ChartsGalleryListItemFooter() {
    OpenCodeListItem(
        title = "Workstation",
        subtitle = "192.168.1.2:4096",
        status = OpenCodeListItemStatus("Active", OpenCodeListItemStatusTone.Success),
        style = OpenCodeListItemStyle.Card,
        startDecorator = StartDecorator.Letter('W', LetterAccent.Teal),
        onClick = {},
        footer = { ChartsGalleryFooterSparkline() },
    )
}

/** The sparkline a List Item footer carries. */
@Composable
internal fun ChartsGalleryFooterSparkline() {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        OpenCodeSparkline(values = sparkValues, modifier = Modifier.fillMaxWidth().height(40.dp))
    }
}
