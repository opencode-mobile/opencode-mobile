import SwiftUI

/// Live or settled: a live card shows "Working" and its running lines; a
/// settled card folds into a one-line summary.
enum OpenCodeActivityCardState: Equatable, Sendable {
    case live
    case settled
}

enum OpenCodeActivityLineKind: Equatable, Sendable {
    case tool
    case childSession
    case thought
}

/// The family glyph in front of a line; callers never pass a raw icon.
enum OpenCodeActivityLineIcon: Equatable, Sendable {
    case read
    case search
    case edit
    case command
    case fetch
    case agent
    case thought
    case skill
    /// A tool outside the families above (questions, MCP tools).
    case other

    var icon: OpenCodeIcon {
        switch self {
        case .read: .description
        case .search: .search
        case .edit: .edit
        case .command: .terminal
        case .fetch: .cloud
        case .agent: .agent
        case .thought: .architecture
        case .skill: .slash
        case .other: .widgets
        }
    }
}

enum OpenCodeActivityLineStatus: String, CaseIterable, Sendable {
    case pending
    case running
    case completed
    case failed
    /// Stopped by the user (Stop, Deny, Dismiss): neutral, not a failure.
    case interrupted

    var isInFlight: Bool {
        self == .pending || self == .running
    }
}

/// One step on an Activity Card.
struct OpenCodeActivityLine: Equatable, Sendable, Identifiable {
    let id: String
    let kind: OpenCodeActivityLineKind
    let icon: OpenCodeActivityLineIcon
    let title: String
    let detail: String?
    let status: OpenCodeActivityLineStatus
    let additions: Int?
    let deletions: Int?
    let startedAtMs: Int64?
    let endedAtMs: Int64?
    let isActionable: Bool

    init(
        id: String,
        kind: OpenCodeActivityLineKind = .tool,
        icon: OpenCodeActivityLineIcon,
        title: String,
        detail: String? = nil,
        status: OpenCodeActivityLineStatus,
        additions: Int? = nil,
        deletions: Int? = nil,
        startedAtMs: Int64? = nil,
        endedAtMs: Int64? = nil,
        isActionable: Bool = true
    ) {
        self.id = id
        self.kind = kind
        self.icon = icon
        self.title = title
        self.detail = detail
        self.status = status
        self.additions = additions
        self.deletions = deletions
        self.startedAtMs = startedAtMs
        self.endedAtMs = endedAtMs
        self.isActionable = isActionable
    }
}

/// Activity Card (#779): one card per run of an agent's steps. Live, it lists
/// each running step with a spinner and live elapsed time and folds finished
/// work into a footer count; settled, it is one summary line. Tapping the
/// header, footer or settled line expands the card inline to list finished steps.
struct OpenCodeActivityCard: View {
    let state: OpenCodeActivityCardState
    /// Live only: the run's first start, for the header's elapsed time.
    let startedAtMs: Int64?
    /// Live with nothing running: an italic "Thinking…" line instead of running lines.
    let thinking: Bool
    let runningLines: [OpenCodeActivityLine]
    let doneLines: [OpenCodeActivityLine]
    /// Live: the footer ("4 done · 3 reads"). Settled: the one-liner ("9 steps · 3 reads").
    let summary: String
    /// Tints the settled card's leading dot: `.failed` or `.completed`.
    let worstStatus: OpenCodeActivityLineStatus
    let isExpanded: Bool
    let onToggleExpanded: () -> Void
    let onLineTap: (String) -> Void
    let onChildSessionTap: (String) -> Void

    init(
        state: OpenCodeActivityCardState,
        startedAtMs: Int64? = nil,
        thinking: Bool = false,
        runningLines: [OpenCodeActivityLine] = [],
        doneLines: [OpenCodeActivityLine] = [],
        summary: String,
        worstStatus: OpenCodeActivityLineStatus = .completed,
        isExpanded: Bool = false,
        onToggleExpanded: @escaping () -> Void = {},
        onLineTap: @escaping (String) -> Void = { _ in },
        onChildSessionTap: @escaping (String) -> Void = { _ in }
    ) {
        self.state = state
        self.startedAtMs = startedAtMs
        self.thinking = thinking
        self.runningLines = runningLines
        self.doneLines = doneLines
        self.summary = summary
        self.worstStatus = worstStatus
        self.isExpanded = isExpanded
        self.onToggleExpanded = onToggleExpanded
        self.onLineTap = onLineTap
        self.onChildSessionTap = onChildSessionTap
    }

    /// More than this many running lines show `maxRunningLines - 1` and "+N more running".
    static let maxRunningLines = 3

    var body: some View {
        Group {
            if state == .settled, !isExpanded {
                settledLine
            } else {
                VStack(alignment: .leading, spacing: 0) {
                    header
                    if state == .live, thinking, runningLines.isEmpty {
                        Text("Thinking\u{2026}")
                            .font(OpenCodeTypography.caption)
                            .italic()
                            .foregroundStyle(OpenCodeColors.textWeak)
                            .padding(.vertical, ActivityCardMetrics.lineVerticalPadding)
                            .accessibilityLabel("Thinking")
                    }
                    if isExpanded {
                        ForEach(doneLines) { line in
                            lineRow(line)
                        }
                    }
                    if state == .live {
                        ForEach(visibleRunningLines) { line in
                            lineRow(line)
                        }
                        if hiddenRunningCount > 0 {
                            Text("+\(hiddenRunningCount) more running")
                                .font(OpenCodeTypography.bodySecondary)
                                .foregroundStyle(OpenCodeColors.textWeaker)
                                .padding(.vertical, ActivityCardMetrics.lineVerticalPadding)
                        }
                    }
                    footer
                }
                .padding(.horizontal, ActivityCardMetrics.paddingHorizontal)
                .padding(.vertical, ActivityCardMetrics.paddingVertical)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            RoundedRectangle(cornerRadius: ActivityCardMetrics.cornerRadius, style: .continuous)
                .fill(OpenCodeColors.surfaceRaisedBase)
                .overlay(
                    RoundedRectangle(cornerRadius: ActivityCardMetrics.cornerRadius, style: .continuous)
                        .strokeBorder(OpenCodeColors.borderWeakBase, lineWidth: 1)
                )
        }
    }

    private var visibleRunningLines: [OpenCodeActivityLine] {
        guard runningLines.count > Self.maxRunningLines else { return runningLines }
        return Array(runningLines.prefix(Self.maxRunningLines - 1))
    }

    private var hiddenRunningCount: Int {
        runningLines.count - visibleRunningLines.count
    }

    // MARK: - Header and footer

    private var header: some View {
        Button(action: onToggleExpanded) {
            HStack(spacing: 8) {
                if state == .live {
                    ActivityCardWorkingLabel()
                } else {
                    Text("Done")
                        .font(OpenCodeTypography.caption.weight(.semibold))
                        .foregroundStyle(OpenCodeColors.textWeaker)
                }
                Spacer(minLength: 0)
                if state == .live, let startedAtMs {
                    ActivityCardElapsed(startedAtMs: startedAtMs, endedAtMs: nil)
                }
            }
            .padding(.bottom, 2)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(headerAccessibilityLabel)
        .accessibilityAddTraits(.isButton)
    }

    private var headerAccessibilityLabel: String {
        guard state == .live else { return "Done, \(doneLines.count) steps" }
        var parts = ["Working", "\(runningLines.count) running", "\(doneLines.count) done"]
        if let startedAtMs {
            parts.append(OpenCodeStepRailElapsed.format(
                startedAtMs: startedAtMs,
                endMs: OpenCodeStepRailElapsed.nowMs(Date())
            ))
        }
        return parts.joined(separator: ", ")
    }

    private var footer: some View {
        Button(action: onToggleExpanded) {
            HStack(spacing: 8) {
                Text(summary)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeaker)
                    .lineLimit(1)
                    .truncationMode(.tail)
                Spacer(minLength: 0)
                ActivityCardChevron(isExpanded: isExpanded)
            }
            .padding(.top, ActivityCardMetrics.footerTopPadding)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .overlay(alignment: .top) {
            Rectangle()
                .fill(OpenCodeColors.borderWeakBase)
                .frame(height: 1)
        }
        .padding(.top, ActivityCardMetrics.footerTopMargin)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(summary), \(isExpanded ? "collapses" : "expands")")
        .accessibilityAddTraits(.isButton)
    }

    /// "9 steps" in bold, then the families muted, then a chevron.
    private var settledLine: some View {
        Button(action: onToggleExpanded) {
            HStack(spacing: 8) {
                ActivityCardDot(status: worstStatus)
                settledSummaryText
                    .font(OpenCodeTypography.bodySecondary)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)
                ActivityCardChevron(isExpanded: false)
            }
            .padding(.horizontal, ActivityCardMetrics.paddingHorizontal)
            .padding(.vertical, ActivityCardMetrics.settledPaddingVertical)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            "\(summary), \(worstStatus == .failed ? "failed" : "completed"), expands"
        )
        .accessibilityAddTraits(.isButton)
    }

    private var settledSummaryText: Text {
        let separator = " \u{00B7} "
        guard let range = summary.range(of: separator) else {
            return Text(summary).fontWeight(.semibold).foregroundColor(OpenCodeColors.textStrong)
        }
        let head = String(summary[..<range.lowerBound])
        let tail = String(summary[range.lowerBound...])
        return Text(head).fontWeight(.semibold).foregroundColor(OpenCodeColors.textStrong)
            + Text(tail).foregroundColor(OpenCodeColors.textWeak)
    }

    // MARK: - Lines

    @ViewBuilder
    private func lineRow(_ line: OpenCodeActivityLine) -> some View {
        let row = ActivityCardLineRow(line: line)
        if line.isActionable {
            Button {
                if line.kind == .childSession {
                    onChildSessionTap(line.id)
                } else {
                    onLineTap(line.id)
                }
            } label: {
                row
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(.isButton)
        } else {
            row
        }
    }
}

private enum ActivityCardMetrics {
    static let cornerRadius: CGFloat = 14
    static let paddingHorizontal: CGFloat = 12
    static let paddingVertical: CGFloat = 10
    static let settledPaddingVertical: CGFloat = 9
    static let lineVerticalPadding: CGFloat = 5
    static let lineSpacing: CGFloat = 8
    static let spinnerSize: CGFloat = 12
    static let iconSize: CGFloat = 16
    static let dotSize: CGFloat = 8
    static let chevronSize: CGFloat = 14
    static let footerTopMargin: CGFloat = 4
    static let footerTopPadding: CGFloat = 7
}

/// One line: a spinner (running) or status dot (done), the family icon, the
/// title, then a diff stat, "failed" / "stopped" or live elapsed.
private struct ActivityCardLineRow: View {
    let line: OpenCodeActivityLine

    private var isRunning: Bool { line.status.isInFlight }

    private var titleText: String {
        guard line.kind == .childSession, let detail = line.detail, !detail.isEmpty else { return line.title }
        return "\(line.title) \u{00B7} \(detail)"
    }

    private var titleColor: Color {
        if line.kind == .childSession { return OpenCodeColors.info }
        return isRunning ? OpenCodeColors.textStrong : OpenCodeColors.textWeak
    }

    var body: some View {
        HStack(spacing: ActivityCardMetrics.lineSpacing) {
            if isRunning {
                ActivityCardSpinner()
            } else {
                ActivityCardDot(status: line.status)
                    .frame(width: ActivityCardMetrics.spinnerSize)
            }
            OpenCodeIconImage(icon: line.icon.icon, size: ActivityCardMetrics.iconSize)
                .foregroundStyle(line.kind == .childSession ? OpenCodeColors.info : OpenCodeColors.iconWeakBase)
                .frame(width: ActivityCardMetrics.iconSize)
            Text(titleText)
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(titleColor)
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .leading)
            if !isRunning, (line.additions ?? 0) > 0 || (line.deletions ?? 0) > 0 {
                OpenCodeDiffStat(
                    additions: (line.additions ?? 0) > 0 ? line.additions : nil,
                    deletions: (line.deletions ?? 0) > 0 ? line.deletions : nil
                )
                .fixedSize()
            }
            if line.status == .failed {
                Text("failed")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.error)
                    .fixedSize()
            } else if line.status == .interrupted {
                Text("stopped")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeaker)
                    .fixedSize()
            }
            if isRunning, let startedAtMs = line.startedAtMs {
                ActivityCardElapsed(startedAtMs: startedAtMs, endedAtMs: line.endedAtMs)
            }
        }
        .padding(.vertical, ActivityCardMetrics.lineVerticalPadding)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLabel)
    }

    private var accessibilityLabel: String {
        let elapsed = isRunning
            ? line.startedAtMs.map {
                OpenCodeStepRailElapsed.format(startedAtMs: $0, endMs: OpenCodeStepRailElapsed.nowMs(Date()))
            }
            : nil
        let status: String =
            switch line.status {
            case .pending: "pending"
            case .running: "running"
            case .completed: "completed"
            case .failed: "failed"
            case .interrupted: "stopped"
            }
        return [titleText, status, elapsed].compactMap { $0 }.joined(separator: ", ")
    }
}

/// Monospaced `m:ss` that ticks every second until `endedAtMs`.
private struct ActivityCardElapsed: View {
    let startedAtMs: Int64
    let endedAtMs: Int64?

    var body: some View {
        if let endedAtMs {
            label(OpenCodeStepRailElapsed.format(startedAtMs: startedAtMs, endMs: endedAtMs))
        } else {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                label(OpenCodeStepRailElapsed.format(
                    startedAtMs: startedAtMs,
                    endMs: OpenCodeStepRailElapsed.nowMs(context.date)
                ))
            }
        }
    }

    private func label(_ text: String) -> some View {
        Text(text)
            .font(OpenCodeTypography.caption.monospacedDigit())
            .foregroundStyle(OpenCodeColors.textWeaker)
            .fixedSize()
    }
}

/// "Working" with a sweeping highlight, the same clock-driven treatment as a
/// running Tool Chip, so a re-created card never restarts it. Static under
/// Reduce Motion.
private struct ActivityCardWorkingLabel: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if reduceMotion {
            text.foregroundStyle(OpenCodeColors.textWeak)
        } else {
            TimelineView(.animation) { context in
                text.foregroundStyle(Self.shimmer(at: context.date))
            }
        }
    }

    private var text: some View {
        Text("Working")
            .font(OpenCodeTypography.caption.weight(.semibold))
            .lineLimit(1)
    }

    private static let cycle = 1.6
    private static let halfWidth = 0.3

    private static func shimmer(at date: Date) -> LinearGradient {
        let phase = date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: cycle) / cycle
        let center = -halfWidth + phase * (1 + 2 * halfWidth)
        return LinearGradient(
            colors: [OpenCodeColors.textWeak, OpenCodeColors.textStrong, OpenCodeColors.textWeak],
            startPoint: UnitPoint(x: center - halfWidth, y: 0.5),
            endPoint: UnitPoint(x: center + halfWidth, y: 0.5)
        )
    }
}

/// 12pt spinner; under Reduce Motion a static warning dot.
private struct ActivityCardSpinner: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            if reduceMotion {
                Circle()
                    .fill(OpenCodeColors.warning)
                    .frame(width: ActivityCardMetrics.dotSize, height: ActivityCardMetrics.dotSize)
            } else {
                ProgressView()
                    .controlSize(.mini)
                    .tint(OpenCodeColors.warning)
            }
        }
        .frame(width: ActivityCardMetrics.spinnerSize, height: ActivityCardMetrics.spinnerSize)
        .accessibilityHidden(true)
    }
}

private struct ActivityCardDot: View {
    let status: OpenCodeActivityLineStatus

    var body: some View {
        Circle()
            .fill(fill)
            .frame(width: ActivityCardMetrics.dotSize, height: ActivityCardMetrics.dotSize)
            .accessibilityHidden(true)
    }

    private var fill: Color {
        switch status {
        case .completed: OpenCodeColors.success
        case .failed: OpenCodeColors.error
        case .running, .pending: OpenCodeColors.warning
        case .interrupted: OpenCodeColors.textWeaker
        }
    }
}

private struct ActivityCardChevron: View {
    let isExpanded: Bool

    var body: some View {
        OpenCodeIconImage(icon: .chevronRight, size: ActivityCardMetrics.chevronSize)
            .rotationEffect(.degrees(isExpanded ? -90 : 0))
            .foregroundStyle(OpenCodeColors.textWeaker)
            .accessibilityHidden(true)
    }
}

#if DEBUG
#Preview("Activity Card") {
    let now = Int64(Date().timeIntervalSince1970 * 1000)
    let done = [
        OpenCodeActivityLine(id: "1", icon: .read, title: "SessionChatView.swift", status: .completed),
        OpenCodeActivityLine(id: "2", icon: .edit, title: "TimelineBlocks.swift", status: .completed, additions: 18, deletions: 4),
        OpenCodeActivityLine(id: "3", icon: .command, title: "Ran swift test", status: .failed),
    ]
    ScrollView {
        VStack(alignment: .leading, spacing: 16) {
            OpenCodeActivityCard(
                state: .live,
                startedAtMs: now - 64_000,
                runningLines: [
                    OpenCodeActivityLine(id: "4", icon: .search, title: "Searching repoStrip", status: .running, startedAtMs: now - 4_000),
                    OpenCodeActivityLine(
                        id: "5",
                        kind: .childSession,
                        icon: .agent,
                        title: "Explore",
                        detail: "Find timeline callers",
                        status: .running,
                        startedAtMs: now - 40_000
                    ),
                ],
                doneLines: done,
                summary: "3 done \u{00B7} 1 read \u{00B7} 1 edit \u{00B7} 1 command"
            )
            OpenCodeActivityCard(
                state: .settled,
                doneLines: done,
                summary: "3 steps \u{00B7} 1 read \u{00B7} 1 edit \u{00B7} 1 command",
                worstStatus: .failed
            )
        }
        .padding()
    }
    .openCodeTheme()
}
#endif
