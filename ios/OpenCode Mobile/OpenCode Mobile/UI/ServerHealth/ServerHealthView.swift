import FactoryKit
import SwiftUI

/// Server Health for one Connection Profile: live status, then uptime, latency, reconnects,
/// errors, and server details over 1 h, 24 h, or 7 d.
struct ServerHealthView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: ServerHealthViewModel

    init(route: ServerHealthRoute) {
        _viewModel = State(initialValue: Container.makeServerHealthViewModel(route: route))
    }

    init(viewModel: ServerHealthViewModel) {
        _viewModel = State(initialValue: viewModel)
    }

    var body: some View {
        let state = viewModel.state
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: .verbatim(state.profileName),
                subtitle: OpenCodeText(LocalizedStringResource("server_health.title", defaultValue: "Server health")),
                leading: .back(action: { dismiss() }),
                showDivider: true
            )
            ScrollView {
                ServerHealthContent(state: state, onSelectRange: { range in
                    Task { await viewModel.selectRange(range) }
                })
                .padding(.horizontal, 16)
                .padding(.top, 16)
                .padding(.bottom, 32)
                .openCodeReadableColumn()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .toolbar(.hidden, for: .navigationBar)
        .task {
            await viewModel.reload()
            await viewModel.observe()
        }
    }
}

/// The screen body, apart from the view model so snapshots and previews can render a state.
struct ServerHealthContent: View {
    let state: ServerHealthUiState
    let onSelectRange: (ServerHealthRange) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ServerHealthHeroCard(state: state)
            if let report = state.report, !report.isEmpty {
                OpenCodeFilterTabs(
                    tabs: ServerHealthRange.allCases.map { OpenCodeFilterTab(id: $0.rawValue, title: Self.rangeTitle($0)) },
                    selectedTabId: state.range.rawValue,
                    style: .segmented,
                    onTabSelected: { id in
                        if let range = ServerHealthRange(rawValue: id) { onSelectRange(range) }
                    }
                )
                ServerHealthTiles(report: report)
                ServerHealthCharts(report: report)
                ServerHealthErrorsCard(report: report)
                ServerHealthServerCard(state: state, report: report)
                footnote
            } else if state.report != nil {
                OpenCodeEmptyState(
                    title: OpenCodeText(LocalizedStringResource("server_health.empty_title", defaultValue: "No measurements yet")),
                    message: OpenCodeText(LocalizedStringResource(
                        "server_health.empty_message",
                        defaultValue: "Health is measured while the app is open and connected to this server."
                    )),
                    fillsAvailableSpace: false
                )
                .padding(.vertical, 24)
                footnote
            }
        }
    }

    private var footnote: some View {
        Text("server_health.footnote")
            .font(OpenCodeTypography.caption)
            .foregroundStyle(OpenCodeColors.textWeak)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.top, 4)
    }

    static func rangeTitle(_ range: ServerHealthRange) -> OpenCodeText {
        switch range {
        case .hour: OpenCodeText(LocalizedStringResource("server_health.range_hour", defaultValue: "1 h"))
        case .day: OpenCodeText(LocalizedStringResource("server_health.range_day", defaultValue: "24 h"))
        case .week: OpenCodeText(LocalizedStringResource("server_health.range_week", defaultValue: "7 d"))
        }
    }
}

private struct ServerHealthHeroCard: View {
    let state: ServerHealthUiState

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                ServerHealthStatusChip(state: state.status)
                Spacer(minLength: 8)
                Text("server_health.last_30_min")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeak)
            }
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(verbatim: state.currentPingMs.map(String.init) ?? ServerHealthText.placeholder)
                    .font(.system(size: 40, weight: .medium).monospacedDigit())
                    .foregroundStyle(OpenCodeColors.textStrong)
                Text("server_health.ms_ping")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeak)
            }
            .accessibilityElement(children: .combine)
            if let caption {
                Text(openCode: caption)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .lineLimit(1)
            }
            OpenCodeSparkline(
                values: state.recentPings.map { $0.map(Double.init) },
                tone: state.status == .live ? .success : .warning,
                showsGaps: true
            )
            .frame(height: 54)
            OpenCodeChartAxis(labels: OpenCodeChartAxisLabels(
                start: OpenCodeText(LocalizedStringResource("server_health.30_min_ago", defaultValue: "30 min ago")),
                middle: OpenCodeText(LocalizedStringResource(
                    "server_health.p95",
                    defaultValue: "p95 \(ServerHealthText.milliseconds(state.recentP95Ms))"
                )),
                end: OpenCodeText(LocalizedStringResource("server_health.now", defaultValue: "now"))
            ))
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .openCodeChartCardSurface()
    }

    /// Event streams for the active profile, so the live state has its evidence beside it.
    private var caption: OpenCodeText? {
        guard state.isActiveProfile, state.requiredTargets > 0 else { return nil }
        return OpenCodeText(LocalizedStringResource(
            "server_health.hero_streams",
            defaultValue: "\(state.liveTargets) of \(state.requiredTargets) event streams live"
        ))
    }
}

private struct ServerHealthTiles: View {
    let report: ServerHealthReport

    var body: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                OpenCodeStatTile(
                    label: OpenCodeText(LocalizedStringResource("server_health.uptime", defaultValue: "Uptime")),
                    value: ServerHealthText.percent(report.uptimePercent),
                    detail: report.observedMs > 0
                        ? OpenCodeText(LocalizedStringResource(
                            "server_health.uptime_detail",
                            defaultValue: "of \(ServerHealthFormat.duration(report.observedMs)) measured"
                        ))
                        : nil
                )
                OpenCodeStatTile(
                    label: OpenCodeText(LocalizedStringResource("server_health.reconnects", defaultValue: "Reconnects")),
                    value: "\(report.drops)",
                    detail: reconnectsDetail
                )
            }
            .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 10) {
                OpenCodeStatTile(
                    label: OpenCodeText(LocalizedStringResource("server_health.error_rate", defaultValue: "Error rate")),
                    value: ServerHealthText.percent(report.errorRatePercent),
                    detail: report.checks > 0
                        ? OpenCodeText(LocalizedStringResource(
                            "server_health.error_rate_detail",
                            defaultValue: "\(report.failedChecks) of \(report.checks) checks failed"
                        ))
                        : nil
                )
                OpenCodeStatTile(
                    label: OpenCodeText(LocalizedStringResource("server_health.between_drops", defaultValue: "Between drops")),
                    value: report.meanTimeBetweenDropsMs.map(ServerHealthFormat.duration) ?? ServerHealthText.placeholder,
                    detail: report.drops > 0
                        ? OpenCodeText(LocalizedStringResource(
                            "server_health.between_drops_detail",
                            defaultValue: "average live stretch"
                        ))
                        : Self.noDrops
                )
            }
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    private static let noDrops = OpenCodeText(LocalizedStringResource("server_health.no_drops", defaultValue: "no drops"))

    private var reconnectsDetail: OpenCodeText? {
        if let recovery = report.medianRecoveryMs {
            return OpenCodeText(LocalizedStringResource(
                "server_health.reconnects_detail",
                defaultValue: "back in \(ServerHealthFormat.duration(recovery)) median"
            ))
        }
        return report.drops == 0 ? Self.noDrops : nil
    }
}

private struct ServerHealthCharts: View {
    let report: ServerHealthReport

    var body: some View {
        let axis = axisLabels
        OpenCodeChartCard(
            title: OpenCodeText(LocalizedStringResource("server_health.latency", defaultValue: "Latency")),
            summary: latencySummary,
            axisLabels: axis,
            legend: [
                OpenCodeChartLegendItem(label: OpenCodeText(LocalizedStringResource("server_health.legend_median", defaultValue: "Median")), swatch: .interactive),
                OpenCodeChartLegendItem(label: OpenCodeText(LocalizedStringResource("server_health.legend_p95", defaultValue: "p95")), swatch: .interactiveWeak),
                OpenCodeChartLegendItem(label: OpenCodeText(LocalizedStringResource("server_health.legend_offline", defaultValue: "Offline")), swatch: .error),
                Self.notMeasured
            ]
        ) {
            OpenCodeBandChart(
                points: report.buckets.map { bucket in
                    OpenCodeBandChartPoint(
                        median: bucket.medianMs.map(Double.init),
                        upper: bucket.p95Ms.map(Double.init),
                        background: Self.background(bucket.condition)
                    )
                },
                valueLabel: { ServerHealthFormat.milliseconds(Int($0.rounded())) },
                accessibilityLabel: latencySummary
            )
            .frame(height: 120)
        }

        OpenCodeChartCard(
            title: OpenCodeText(LocalizedStringResource("server_health.connection", defaultValue: "Connection")),
            summary: OpenCodeText(LocalizedStringResource(
                "server_health.connection_summary",
                defaultValue: "\(ServerHealthText.percent(report.uptimePercent)) live"
            )),
            axisLabels: axis,
            legend: [
                OpenCodeChartLegendItem(label: OpenCodeText(LocalizedStringResource("server_health.legend_live", defaultValue: "Live")), swatch: .success),
                OpenCodeChartLegendItem(label: OpenCodeText(LocalizedStringResource("server_health.legend_unsteady", defaultValue: "Unsteady")), swatch: .warning),
                OpenCodeChartLegendItem(label: OpenCodeText(LocalizedStringResource("server_health.legend_down", defaultValue: "Down")), swatch: .error),
                Self.notMeasured
            ]
        ) {
            OpenCodeStatusStrip(segments: report.buckets.map { Self.segment($0.condition) })
        }

        OpenCodeChartCard(
            title: OpenCodeText(LocalizedStringResource("server_health.reconnects", defaultValue: "Reconnects")),
            summary: OpenCodeText(LocalizedStringResource(
                "server_health.reconnects_summary",
                defaultValue: "\(report.drops) reconnects"
            )),
            axisLabels: axis
        ) {
            OpenCodeBarChart(values: report.buckets.map { Double($0.drops) }, tone: .warning)
                .frame(height: 56)
        }
    }

    private static let notMeasured = OpenCodeChartLegendItem(
        label: OpenCodeText(LocalizedStringResource("server_health.legend_not_measured", defaultValue: "Not measured")),
        swatch: .unobserved
    )

    private var latencySummary: OpenCodeText {
        OpenCodeText(LocalizedStringResource(
            "server_health.latency_summary",
            defaultValue: "median \(ServerHealthText.milliseconds(report.p50LatencyMs)) · p95 \(ServerHealthText.milliseconds(report.p95LatencyMs))"
        ))
    }

    /// "24 h ago" … "now".
    private var axisLabels: OpenCodeChartAxisLabels {
        let span = ServerHealthContent.rangeTitle(report.range).resolved
        return OpenCodeChartAxisLabels(
            start: OpenCodeText(LocalizedStringResource("server_health.axis_ago", defaultValue: "\(span) ago")),
            end: OpenCodeText(LocalizedStringResource("server_health.now", defaultValue: "now"))
        )
    }

    static func background(_ condition: ServerHealthBucketCondition) -> OpenCodeBandChartBackground {
        switch condition {
        case .live: .none
        case .degraded: .warning
        case .down: .error
        case .unobserved: .unobserved
        }
    }

    static func segment(_ condition: ServerHealthBucketCondition) -> OpenCodeStatusStripSegment {
        switch condition {
        case .live: .success
        case .degraded: .warning
        case .down: .error
        case .unobserved: .unobserved
        }
    }
}

private struct ServerHealthErrorsCard: View {
    let report: ServerHealthReport

    var body: some View {
        let total = report.failuresByCategory.reduce(0) { $0 + $1.count }
        OpenCodeChartCard(title: OpenCodeText(LocalizedStringResource("server_health.errors", defaultValue: "Errors"))) {
            if report.failuresByCategory.isEmpty {
                Text("server_health.errors_empty")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textBase)
            } else {
                VStack(spacing: 10) {
                    ForEach(report.failuresByCategory, id: \.category) { item in
                        OpenCodeProportionRow(
                            label: ServerHealthText.category(item.category),
                            count: "\(item.count)",
                            fraction: total > 0 ? Double(item.count) / Double(total) : 0,
                            tone: ServerHealthText.categoryTone(item.category)
                        )
                    }
                }
            }
        }
    }
}

struct ServerHealthServerCard: View {
    let state: ServerHealthUiState
    let report: ServerHealthReport

    var body: some View {
        OpenCodeChartCard(title: OpenCodeText(LocalizedStringResource("server_health.server", defaultValue: "Server"))) {
            VStack(spacing: 10) {
                OpenCodeKeyValueRow(
                    label: OpenCodeText(LocalizedStringResource("server_health.version", defaultValue: "Version")),
                    value: state.serverVersion ?? ServerHealthText.placeholder
                )
                OpenCodeKeyValueRow(
                    label: OpenCodeText(LocalizedStringResource("server_health.protocol", defaultValue: "Protocol")),
                    value: state.protocolLabel ?? ServerHealthText.placeholder
                )
                OpenCodeKeyValueRow(
                    label: OpenCodeText(LocalizedStringResource("server_health.last_restart", defaultValue: "Last restart")),
                    value: lastRestart
                )
                OpenCodeKeyValueRow(
                    label: OpenCodeText(LocalizedStringResource("server_health.restarts_seen", defaultValue: "Restarts seen")),
                    value: String(localized: "server_health.restarts_seen_value", defaultValue: "\(report.restarts.count)")
                )
                if state.isActiveProfile {
                    OpenCodeKeyValueRow(
                        label: OpenCodeText(LocalizedStringResource("server_health.event_streams", defaultValue: "Event streams")),
                        value: Self.eventStreamsValue(state)
                    )
                }
            }
        }
    }

    private var lastRestart: String {
        guard let at = report.lastRestartAtMs else {
            return String(localized: "server_health.last_restart_none", defaultValue: "None seen")
        }
        let ago = ServerHealthFormat.duration(max(0, report.nowMs - at))
        return String(localized: "server_health.last_restart_ago", defaultValue: "\(ago) ago")
    }

    static func eventStreamsValue(_ state: ServerHealthUiState) -> String {
        String(
            localized: "server_health.event_streams_value",
            defaultValue: "\(state.liveTargets) of \(state.requiredTargets) live"
        )
    }
}
