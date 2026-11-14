package dev.opencode.mobile.ui.serverhealth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.serverhealth.ServerHealthBucketCondition
import dev.opencode.mobile.domain.serverhealth.ServerHealthFailureCategory
import dev.opencode.mobile.domain.serverhealth.ServerHealthRange
import dev.opencode.mobile.domain.serverhealth.ServerHealthReport
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeBandChart
import dev.opencode.mobile.ui.components.OpenCodeBandChartBackground
import dev.opencode.mobile.ui.components.OpenCodeBandChartPoint
import dev.opencode.mobile.ui.components.OpenCodeBarChart
import dev.opencode.mobile.ui.components.OpenCodeChartAxisLabels
import dev.opencode.mobile.ui.components.OpenCodeChartCard
import dev.opencode.mobile.ui.components.OpenCodeChartLegendItem
import dev.opencode.mobile.ui.components.OpenCodeChartLegendSwatch
import dev.opencode.mobile.ui.components.OpenCodeChartSurface
import dev.opencode.mobile.ui.components.OpenCodeChartTone
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeFilterTab
import dev.opencode.mobile.ui.components.OpenCodeFilterTabs
import dev.opencode.mobile.ui.components.OpenCodeFilterTabsStyle
import dev.opencode.mobile.ui.components.OpenCodeKeyValueRow
import dev.opencode.mobile.ui.components.OpenCodeProportionRow
import dev.opencode.mobile.ui.components.OpenCodeProportionTone
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeSparkline
import dev.opencode.mobile.ui.components.OpenCodeStatTile
import dev.opencode.mobile.ui.components.OpenCodeStatusStrip
import dev.opencode.mobile.ui.components.OpenCodeStatusStripSegment
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.text.resolve

@Composable
fun ServerHealthScreen(
    profileId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServerHealthViewModel = hiltViewModel(),
) {
    LaunchedEffect(profileId) { viewModel.bind(profileId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ServerHealthContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onSelectRange = viewModel::selectRange,
        modifier = modifier,
    )
}

@Composable
fun ServerHealthContent(
    uiState: ServerHealthUiState,
    onNavigateBack: () -> Unit,
    onSelectRange: (ServerHealthRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = uiState.profileName,
            subtitle = stringResource(R.string.server_health_title),
            leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
            showDivider = true,
        )
        OpenCodeReadableColumn(modifier = Modifier.weight(1f)) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ServerHealthHero(uiState)
                val report = uiState.report
                if (report != null && report.isEmpty) {
                    OpenCodeEmptyState(
                        title = stringResource(R.string.server_health_empty_title),
                        message = stringResource(R.string.server_health_empty_message),
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else if (report != null) {
                    OpenCodeFilterTabs(
                        tabs =
                            ServerHealthRange.entries.map {
                                OpenCodeFilterTab(
                                    it.name,
                                    stringResource(ServerHealthFormat.rangeLabel(it)),
                                )
                            },
                        selectedTabId = uiState.range.name,
                        onTabSelected = { id -> onSelectRange(ServerHealthRange.valueOf(id)) },
                        style = OpenCodeFilterTabsStyle.Segmented,
                    )
                    ServerHealthTiles(report)
                    ServerHealthCharts(report)
                    ServerHealthErrors(report)
                    ServerHealthServerCard(uiState, report)
                }
                Text(
                    text = stringResource(R.string.server_health_footnote),
                    style = MaterialTheme.typography.labelSmall,
                    color = openCodeColor("text-weaker"),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ServerHealthHero(uiState: ServerHealthUiState) {
    val weak = openCodeColor("text-weaker")
    OpenCodeChartSurface(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ServerHealthStatusChip(state = uiState.status)
            Spacer(modifier = Modifier.weight(1f))
            Text(stringResource(R.string.server_health_last_30_min), style = MaterialTheme.typography.labelSmall, color = weak)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = uiState.currentPingMs?.toString() ?: stringResource(R.string.server_health_unknown_value),
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
                color = openCodeColor("text-strong"),
            )
            Text(
                text = stringResource(R.string.server_health_ms_ping),
                style = MaterialTheme.typography.bodySmall,
                color = openCodeColor("text-base"),
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (uiState.isActiveProfile && uiState.requiredTargets > 0) {
            Text(
                text = stringResource(R.string.server_health_hero_streams, uiState.liveTargets, uiState.requiredTargets),
                style = MaterialTheme.typography.labelSmall,
                color = openCodeColor("text-base"),
            )
        }
        OpenCodeSparkline(
            values = uiState.recentPings.map { it?.toDouble() },
            tone = uiState.status.sparklineTone(),
            showsGaps = true,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.server_health_30_min_ago),
                style = MaterialTheme.typography.labelSmall,
                color = weak,
                modifier = Modifier.weight(1f),
            )
            Text(
                text =
                    stringResource(
                        R.string.server_health_p95,
                        uiState.recentP95Ms?.let(ServerHealthFormat::milliseconds) ?: stringResource(R.string.server_health_unknown_value),
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = weak,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.server_health_now),
                style = MaterialTheme.typography.labelSmall,
                color = weak,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ServerHealthTiles(report: ServerHealthReport) {
    val unknown = stringResource(R.string.server_health_unknown_value)
    val noDrops = stringResource(R.string.server_health_no_drops)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeStatTile(
                label = stringResource(R.string.server_health_uptime),
                value = report.uptimePercent?.let(ServerHealthFormat::percent) ?: unknown,
                detail = stringResource(R.string.server_health_uptime_detail, ServerHealthFormat.duration(report.observedMs)),
                modifier = Modifier.weight(1f),
            )
            OpenCodeStatTile(
                label = stringResource(R.string.server_health_reconnects),
                value = report.drops.toString(),
                detail =
                    when {
                        report.drops == 0 -> noDrops
                        report.medianRecoveryMs != null ->
                            stringResource(R.string.server_health_reconnects_detail, ServerHealthFormat.duration(report.medianRecoveryMs))
                        else -> null
                    },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeStatTile(
                label = stringResource(R.string.server_health_error_rate),
                value = report.errorRatePercent?.let(ServerHealthFormat::percent) ?: unknown,
                detail = stringResource(R.string.server_health_error_rate_detail, report.failedChecks, report.checks),
                modifier = Modifier.weight(1f),
            )
            OpenCodeStatTile(
                label = stringResource(R.string.server_health_between_drops),
                value = report.meanTimeBetweenDropsMs?.let(ServerHealthFormat::duration) ?: unknown,
                detail = if (report.drops == 0) noDrops else stringResource(R.string.server_health_between_drops_detail),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun rangeAxisLabels(range: ServerHealthRange): OpenCodeChartAxisLabels =
    OpenCodeChartAxisLabels(
        start = stringResource(R.string.server_health_axis_ago, stringResource(ServerHealthFormat.rangeLabel(range))),
        end = stringResource(R.string.server_health_now),
    )

@Composable
private fun ServerHealthCharts(report: ServerHealthReport) {
    val axis = rangeAxisLabels(report.range)
    val latencySummary =
        if (report.p50LatencyMs != null && report.p95LatencyMs != null) {
            stringResource(
                R.string.server_health_latency_summary,
                ServerHealthFormat.milliseconds(report.p50LatencyMs),
                ServerHealthFormat.milliseconds(report.p95LatencyMs),
            )
        } else {
            null
        }
    OpenCodeChartCard(
        title = stringResource(R.string.server_health_latency),
        summary = latencySummary,
        axisLabels = axis,
        legend =
            listOf(
                OpenCodeChartLegendItem(stringResource(R.string.server_health_legend_median), OpenCodeChartLegendSwatch.Interactive),
                OpenCodeChartLegendItem(stringResource(R.string.server_health_legend_p95), OpenCodeChartLegendSwatch.InteractiveWeak),
                OpenCodeChartLegendItem(stringResource(R.string.server_health_legend_offline), OpenCodeChartLegendSwatch.Error),
                OpenCodeChartLegendItem(stringResource(R.string.server_health_legend_not_measured), OpenCodeChartLegendSwatch.Unobserved),
            ),
    ) {
        OpenCodeBandChart(
            points =
                report.buckets.map { bucket ->
                    OpenCodeBandChartPoint(
                        median = bucket.medianMs?.toDouble(),
                        upper = bucket.p95Ms?.toDouble(),
                        background =
                            when (bucket.condition) {
                                ServerHealthBucketCondition.Live -> OpenCodeBandChartBackground.None
                                ServerHealthBucketCondition.Degraded -> OpenCodeBandChartBackground.Warning
                                ServerHealthBucketCondition.Down -> OpenCodeBandChartBackground.Error
                                ServerHealthBucketCondition.Unobserved -> OpenCodeBandChartBackground.Unobserved
                            },
                    )
                },
            valueLabel = { ServerHealthFormat.milliseconds(it.toInt()) },
            contentDescription = latencySummary,
            modifier = Modifier.fillMaxWidth().height(120.dp),
        )
    }
    val connectionSummary =
        report.uptimePercent?.let {
            stringResource(R.string.server_health_connection_summary, ServerHealthFormat.percent(it))
        }
    OpenCodeChartCard(
        title = stringResource(R.string.server_health_connection),
        summary = connectionSummary,
        axisLabels = axis,
        legend =
            listOf(
                OpenCodeChartLegendItem(stringResource(R.string.server_health_legend_live), OpenCodeChartLegendSwatch.Success),
                OpenCodeChartLegendItem(stringResource(R.string.server_health_legend_unsteady), OpenCodeChartLegendSwatch.Warning),
                OpenCodeChartLegendItem(stringResource(R.string.server_health_legend_down), OpenCodeChartLegendSwatch.Error),
                OpenCodeChartLegendItem(stringResource(R.string.server_health_legend_not_measured), OpenCodeChartLegendSwatch.Unobserved),
            ),
    ) {
        OpenCodeStatusStrip(
            segments =
                report.buckets.map { bucket ->
                    when (bucket.condition) {
                        ServerHealthBucketCondition.Live -> OpenCodeStatusStripSegment.Success
                        ServerHealthBucketCondition.Degraded -> OpenCodeStatusStripSegment.Warning
                        ServerHealthBucketCondition.Down -> OpenCodeStatusStripSegment.Error
                        ServerHealthBucketCondition.Unobserved -> OpenCodeStatusStripSegment.Unobserved
                    }
                },
            contentDescription = connectionSummary,
        )
    }
    val reconnectsSummary = pluralStringResource(R.plurals.server_health_reconnects_summary, report.drops, report.drops)
    OpenCodeChartCard(
        title = stringResource(R.string.server_health_reconnects),
        summary = reconnectsSummary,
        axisLabels = axis,
    ) {
        OpenCodeBarChart(
            values = report.buckets.map { it.drops.toDouble() },
            tone = OpenCodeChartTone.Warning,
            contentDescription = reconnectsSummary,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        )
    }
}

private fun ServerHealthFailureCategory.proportionTone(): OpenCodeProportionTone =
    when (this) {
        ServerHealthFailureCategory.Auth, ServerHealthFailureCategory.Server -> OpenCodeProportionTone.Error
        ServerHealthFailureCategory.Network,
        ServerHealthFailureCategory.Timeout,
        ServerHealthFailureCategory.Heartbeat,
        -> OpenCodeProportionTone.Warning
        ServerHealthFailureCategory.Other -> OpenCodeProportionTone.Info
    }

@Composable
private fun ServerHealthErrors(report: ServerHealthReport) {
    OpenCodeChartCard(title = stringResource(R.string.server_health_errors)) {
        if (report.failuresByCategory.isEmpty()) {
            Text(
                text = stringResource(R.string.server_health_errors_empty),
                style = MaterialTheme.typography.bodySmall,
                color = openCodeColor("text-base"),
            )
        } else {
            val total = report.failuresByCategory.sumOf { it.count }.coerceAtLeast(1)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                report.failuresByCategory.forEach { failure ->
                    OpenCodeProportionRow(
                        label = stringResource(ServerHealthFormat.categoryLabel(failure.category)),
                        count = failure.count.toString(),
                        fraction = failure.count.toDouble() / total,
                        tone = failure.category.proportionTone(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerHealthServerCard(
    uiState: ServerHealthUiState,
    report: ServerHealthReport,
) {
    val unknown = stringResource(R.string.server_health_unknown_value)
    OpenCodeChartCard(title = stringResource(R.string.server_health_server)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeKeyValueRow(label = stringResource(R.string.server_health_version), value = uiState.serverVersion ?: unknown)
            OpenCodeKeyValueRow(
                label = stringResource(R.string.server_health_protocol),
                value = uiState.protocolLabel?.resolve() ?: unknown,
            )
            OpenCodeKeyValueRow(
                label = stringResource(R.string.server_health_last_restart),
                value =
                    report.lastRestartAtMs?.let {
                        stringResource(R.string.server_health_last_restart_ago, ServerHealthFormat.duration(report.nowMs - it))
                    } ?: stringResource(R.string.server_health_last_restart_none),
            )
            OpenCodeKeyValueRow(
                label = stringResource(R.string.server_health_restarts_seen),
                value = stringResource(R.string.server_health_restarts_seen_value, report.restarts.size),
            )
            if (uiState.isActiveProfile) {
                OpenCodeKeyValueRow(
                    label = stringResource(R.string.server_health_event_streams),
                    value = stringResource(R.string.server_health_event_streams_value, uiState.liveTargets, uiState.requiredTargets),
                )
            }
        }
    }
}
