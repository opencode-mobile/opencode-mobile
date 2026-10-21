import SwiftUI

/// Status of one step on a Step Rail. Drives the dot on the rail and the
/// title colour; callers never pass raw styling.
enum OpenCodeStepRailStatus: String, CaseIterable, Sendable {
    case pending
    case running
    case completed
    case failed
    /// Stopped by the user (Stop, Deny, Dismiss): neutral, not a failure.
    case interrupted
}

/// One row of a Step Rail. The caller folds settled steps before passing them
/// in; a fold row is an item of its own.
enum OpenCodeStepRailItem: Equatable, Sendable, Identifiable {
    /// A tool or thought: one line with an optional detail, diff stat and live elapsed.
    case step(
        id: String,
        title: String,
        detail: String? = nil,
        status: OpenCodeStepRailStatus,
        additions: Int? = nil,
        deletions: Int? = nil,
        startedAtMs: Int64? = nil,
        endedAtMs: Int64? = nil,
        isBranch: Bool = false,
        isActionable: Bool = true
    )
    /// A child session: a nested card on the rail with agent name, elapsed and detail line.
    case childSession(
        id: String,
        agent: String,
        description: String? = nil,
        status: OpenCodeStepRailStatus,
        startedAtMs: Int64? = nil,
        endedAtMs: Int64? = nil,
        detail: String? = nil,
        isActionable: Bool = true
    )
    /// "N earlier steps"; `status` tints the dot when a folded step failed.
    case fold(count: Int, status: OpenCodeStepRailStatus = .completed)
    /// The trailing "Thinking…" step of a live rail with nothing running.
    case thinking

    var id: String {
        switch self {
        case .step(let id, _, _, _, _, _, _, _, _, _): id
        case .childSession(let id, _, _, _, _, _, _, _): id
        case .fold: "fold"
        case .thinking: "thinking"
        }
    }
}

/// Step Rail (#779): an agent's work as one short line per step on a vertical
/// rail with live status. Running steps tick their elapsed time every second;
/// a running step right after another running step branches off the rail.
struct OpenCodeStepRail: View {
    let steps: [OpenCodeStepRailItem]
    let isLive: Bool
    let thinking: Bool
    let onStepTap: (String) -> Void
    let onFoldTap: () -> Void
    let onChildSessionTap: (String) -> Void

    init(
        steps: [OpenCodeStepRailItem],
        isLive: Bool = false,
        thinking: Bool = false,
        onStepTap: @escaping (String) -> Void = { _ in },
        onFoldTap: @escaping () -> Void = {},
        onChildSessionTap: @escaping (String) -> Void = { _ in }
    ) {
        self.steps = steps
        self.isLive = isLive
        self.thinking = thinking
        self.onStepTap = onStepTap
        self.onFoldTap = onFoldTap
        self.onChildSessionTap = onChildSessionTap
    }

    /// `thinking` adds the trailing Thinking row unless the caller already did.
    private var rows: [OpenCodeStepRailItem] {
        guard thinking, !steps.contains(.thinking) else { return steps }
        return steps + [.thinking]
    }

    var body: some View {
        let rows = rows
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(rows.enumerated()), id: \.element.id) { index, item in
                StepRailRow(
                    item: item,
                    isFirst: index == 0,
                    isLast: index == rows.count - 1,
                    onStepTap: onStepTap,
                    onFoldTap: onFoldTap,
                    onChildSessionTap: onChildSessionTap
                )
            }
        }
        .padding(.leading, StepRailMetrics.leadingInset)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Live `m:ss` for a step, from its start to its end or now.
enum OpenCodeStepRailElapsed {
    static func format(startedAtMs: Int64, endMs: Int64) -> String {
        let totalSeconds = max(0, endMs - startedAtMs) / 1000
        let seconds = totalSeconds % 60
        return "\(totalSeconds / 60):\(seconds < 10 ? "0" : "")\(seconds)"
    }

    static func nowMs(_ date: Date) -> Int64 {
        Int64(date.timeIntervalSince1970 * 1000)
    }
}

private enum StepRailMetrics {
    static let leadingInset: CGFloat = 6
    static let gutterWidth: CGFloat = 16
    static let branchIndent: CGFloat = 14
    static let lineX: CGFloat = 4
    static let lineWidth: CGFloat = 1.5
    static let dotSize: CGFloat = 8
    static let rowVerticalPadding: CGFloat = 4
    static let rowMinHeight: CGFloat = 26
    static let contentSpacing: CGFloat = 8
    static let childCornerRadius: CGFloat = 10
    static let childPaddingHorizontal: CGFloat = 10
    static let childPaddingVertical: CGFloat = 6
    static let childTintOpacity: Double = 0.12
    static let childBorderOpacity: Double = 0.3
    static let chevronSize: CGFloat = 14
}

private struct StepRailRow: View {
    let item: OpenCodeStepRailItem
    let isFirst: Bool
    let isLast: Bool
    let onStepTap: (String) -> Void
    let onFoldTap: () -> Void
    let onChildSessionTap: (String) -> Void

    private var isBranch: Bool {
        if case .step(_, _, _, _, _, _, _, _, let isBranch, _) = item { return isBranch }
        return false
    }

    private var gutterWidth: CGFloat {
        StepRailMetrics.gutterWidth + (isBranch ? StepRailMetrics.branchIndent : 0)
    }

    private var action: (() -> Void)? {
        switch item {
        case .step(let id, _, _, _, _, _, _, _, _, let actionable):
            actionable ? { onStepTap(id) } : nil
        case .childSession(let id, _, _, _, _, _, _, let actionable):
            actionable ? { onChildSessionTap(id) } : nil
        case .fold:
            onFoldTap
        case .thinking:
            nil
        }
    }

    var body: some View {
        Group {
            if let action {
                Button(action: action) { rowContent }
                    .buttonStyle(.plain)
            } else {
                rowContent
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(action == nil ? [] : .isButton)
    }

    private var rowContent: some View {
        content
            .padding(.leading, gutterWidth)
            .padding(.vertical, StepRailMetrics.rowVerticalPadding)
            .frame(maxWidth: .infinity, minHeight: StepRailMetrics.rowMinHeight, alignment: .leading)
            .background(alignment: .leading) {
                StepRailGutter(
                    dot: dot,
                    isFirst: isFirst,
                    isLast: isLast,
                    isBranch: isBranch
                )
                .frame(width: gutterWidth)
                .accessibilityHidden(true)
            }
            .contentShape(Rectangle())
    }

    private var dot: StepRailDot.Tone {
        switch item {
        case .step(_, _, _, let status, _, _, _, _, _, _),
             .childSession(_, _, _, let status, _, _, _, _):
            StepRailDot.Tone(status)
        case .fold(_, let status):
            status == .failed ? .failed : .weakest
        case .thinking:
            .running
        }
    }

    @ViewBuilder
    private var content: some View {
        switch item {
        case let .step(_, title, detail, status, additions, deletions, startedAtMs, endedAtMs, _, _):
            StepRailStepLine(
                title: title,
                detail: detail,
                status: status,
                additions: additions,
                deletions: deletions,
                startedAtMs: startedAtMs,
                endedAtMs: endedAtMs
            )
        case let .childSession(_, agent, description, status, startedAtMs, endedAtMs, detail, actionable):
            StepRailChildSessionCard(
                agent: agent,
                line: detail ?? description,
                status: status,
                startedAtMs: startedAtMs,
                endedAtMs: endedAtMs,
                showsChevron: actionable
            )
        case .fold(let count, _):
            HStack(spacing: 4) {
                Text(count == 1 ? "1 earlier step" : "\(count) earlier steps")
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textWeaker)
                    .lineLimit(1)
                OpenCodeIconImage(icon: .chevronRight, size: StepRailMetrics.chevronSize)
                    .foregroundStyle(OpenCodeColors.textWeaker)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(count == 1 ? "1 earlier step, expands" : "\(count) earlier steps, expands")
        case .thinking:
            Text("Thinking\u{2026}")
                .font(OpenCodeTypography.bodySecondary)
                .italic()
                .foregroundStyle(OpenCodeColors.textWeak)
                .accessibilityLabel("Thinking")
        }
    }
}

/// The rail line through one row (half height at the ends), a connector for a
/// branch row, and the row's status dot on the line or at the connector's end.
private struct StepRailGutter: View {
    let dot: StepRailDot.Tone
    let isFirst: Bool
    let isLast: Bool
    let isBranch: Bool

    var body: some View {
        GeometryReader { geometry in
            let midY = geometry.size.height / 2
            let dotX = isBranch ? StepRailMetrics.lineX + StepRailMetrics.branchIndent : StepRailMetrics.lineX
            ZStack(alignment: .topLeading) {
                Path { path in
                    path.move(to: CGPoint(x: StepRailMetrics.lineX, y: isFirst ? midY : 0))
                    path.addLine(to: CGPoint(x: StepRailMetrics.lineX, y: isLast ? midY : geometry.size.height))
                    if isBranch {
                        path.move(to: CGPoint(x: StepRailMetrics.lineX, y: midY))
                        path.addLine(to: CGPoint(x: dotX, y: midY))
                    }
                }
                .stroke(OpenCodeColors.borderWeakBase, lineWidth: StepRailMetrics.lineWidth)

                StepRailDot(tone: dot)
                    .position(x: dotX, y: midY)
            }
        }
    }
}

private struct StepRailDot: View {
    enum Tone: Equatable {
        case pending
        case running
        case completed
        case failed
        case interrupted
        case weakest

        init(_ status: OpenCodeStepRailStatus) {
            switch status {
            case .pending: self = .pending
            case .running: self = .running
            case .completed: self = .completed
            case .failed: self = .failed
            case .interrupted: self = .interrupted
            }
        }
    }

    let tone: Tone
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var pulse = false

    var body: some View {
        Group {
            if tone == .pending {
                Circle()
                    .strokeBorder(OpenCodeColors.textWeaker, style: StrokeStyle(lineWidth: 1, dash: [2, 1.5]))
                    .background(Circle().fill(OpenCodeColors.backgroundBase))
            } else {
                Circle().fill(fill)
            }
        }
        .frame(width: StepRailMetrics.dotSize, height: StepRailMetrics.dotSize)
        .opacity(tone == .running && pulse ? 0.35 : 1)
        .animation(
            tone == .running && !reduceMotion
                ? .easeInOut(duration: 0.9).repeatForever(autoreverses: true)
                : nil,
            value: pulse
        )
        .onAppear { pulse = tone == .running && !reduceMotion }
        .onChange(of: tone) { _, next in pulse = next == .running && !reduceMotion }
    }

    private var fill: Color {
        switch tone {
        case .running: OpenCodeColors.warning
        case .completed: OpenCodeColors.success
        case .failed: OpenCodeColors.error
        case .interrupted, .weakest, .pending: OpenCodeColors.textWeaker
        }
    }
}

private struct StepRailStepLine: View {
    let title: String
    let detail: String?
    let status: OpenCodeStepRailStatus
    let additions: Int?
    let deletions: Int?
    let startedAtMs: Int64?
    let endedAtMs: Int64?

    private var isRunning: Bool {
        status == .running || status == .pending
    }

    var body: some View {
        if isRunning, let startedAtMs, endedAtMs == nil {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                line(elapsed: OpenCodeStepRailElapsed.format(
                    startedAtMs: startedAtMs,
                    endMs: OpenCodeStepRailElapsed.nowMs(context.date)
                ))
            }
        } else {
            line(elapsed: nil)
        }
    }

    private func line(elapsed: String?) -> some View {
        HStack(spacing: StepRailMetrics.contentSpacing) {
            HStack(spacing: 6) {
                Text(title)
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(isRunning ? OpenCodeColors.textStrong : OpenCodeColors.textWeak)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .layoutPriority(1)
                if let detail, !detail.isEmpty {
                    Text(detail)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textWeaker)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if let diff = diffText {
                diff
                    .font(OpenCodeTypography.caption.monospacedDigit())
                    .lineLimit(1)
                    .fixedSize()
            }
            if status == .failed {
                Text("failed")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.error)
                    .fixedSize()
            } else if status == .interrupted {
                Text("stopped")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeaker)
                    .fixedSize()
            }
            if let elapsed {
                Text(elapsed)
                    .font(OpenCodeTypography.caption.monospacedDigit())
                    .foregroundStyle(OpenCodeColors.textWeaker)
                    .fixedSize()
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            stepRailAccessibilityLabel(title: title, detail: detail, status: status, elapsed: elapsed)
        )
    }

    private var diffText: Text? {
        var parts: [Text] = []
        if let additions, additions > 0 {
            parts.append(Text("+\(additions)").foregroundColor(OpenCodeColors.diffAdd))
        }
        if let deletions, deletions > 0 {
            if !parts.isEmpty { parts.append(Text(" ")) }
            parts.append(Text("\u{2212}\(deletions)").foregroundColor(OpenCodeColors.diffDelete))
        }
        guard !parts.isEmpty else { return nil }
        return parts.reduce(Text("")) { $0 + $1 }
    }
}

private struct StepRailChildSessionCard: View {
    let agent: String
    let line: String?
    let status: OpenCodeStepRailStatus
    let startedAtMs: Int64?
    let endedAtMs: Int64?
    let showsChevron: Bool

    var body: some View {
        if status == .running || status == .pending, let startedAtMs, endedAtMs == nil {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                card(elapsed: OpenCodeStepRailElapsed.format(
                    startedAtMs: startedAtMs,
                    endMs: OpenCodeStepRailElapsed.nowMs(context.date)
                ))
            }
        } else if let startedAtMs, let endedAtMs {
            card(elapsed: OpenCodeStepRailElapsed.format(startedAtMs: startedAtMs, endMs: endedAtMs))
        } else {
            card(elapsed: nil)
        }
    }

    private func card(elapsed: String?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                Text(agent)
                    .font(OpenCodeTypography.bodySecondary.weight(.semibold))
                    .foregroundStyle(OpenCodeColors.info)
                    .lineLimit(1)
                Spacer(minLength: 0)
                if let elapsed {
                    Text(elapsed)
                        .font(OpenCodeTypography.caption.monospacedDigit())
                        .foregroundStyle(OpenCodeColors.textWeaker)
                        .fixedSize()
                }
                if showsChevron {
                    OpenCodeIconImage(icon: .chevronRight, size: StepRailMetrics.chevronSize)
                        .foregroundStyle(OpenCodeColors.iconStrongBase)
                }
            }
            if let line, !line.isEmpty {
                Text(line)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(status == .failed ? OpenCodeColors.error : OpenCodeColors.textWeak)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
        }
        .padding(.horizontal, StepRailMetrics.childPaddingHorizontal)
        .padding(.vertical, StepRailMetrics.childPaddingVertical)
        .background(
            RoundedRectangle(cornerRadius: StepRailMetrics.childCornerRadius, style: .continuous)
                .fill(OpenCodeColors.info.opacity(StepRailMetrics.childTintOpacity))
                .overlay(
                    RoundedRectangle(cornerRadius: StepRailMetrics.childCornerRadius, style: .continuous)
                        .strokeBorder(OpenCodeColors.info.opacity(StepRailMetrics.childBorderOpacity), lineWidth: 1)
                )
        )
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            stepRailAccessibilityLabel(title: agent, detail: line, status: status, elapsed: elapsed)
        )
    }
}

/// Title, detail, status word and elapsed time, comma-separated.
func stepRailAccessibilityLabel(
    title: String,
    detail: String?,
    status: OpenCodeStepRailStatus,
    elapsed: String?
) -> String {
    let statusWord: String =
        switch status {
        case .pending: "pending"
        case .running: "running"
        case .completed: "completed"
        case .failed: "failed"
        case .interrupted: "stopped"
        }
    return [title, detail, statusWord, elapsed]
        .compactMap { $0 }
        .filter { !$0.isEmpty }
        .joined(separator: ", ")
}

#if DEBUG
#Preview("Step Rail") {
    let now = Int64(Date().timeIntervalSince1970 * 1000)
    ScrollView {
        VStack(alignment: .leading, spacing: 24) {
            OpenCodeStepRail(
                steps: [
                    .step(id: "1", title: "SessionChatView.swift", status: .completed),
                    .step(id: "2", title: "Running npm test", status: .running, startedAtMs: now - 12_000),
                    .step(id: "3", title: "Reading Package.swift", status: .running, startedAtMs: now - 4_000, isBranch: true),
                    .childSession(
                        id: "4",
                        agent: "explore",
                        description: "Find timeline callers",
                        status: .running,
                        startedAtMs: now - 64_000,
                        detail: "Find timeline callers · 6 tools so far"
                    ),
                ],
                isLive: true
            )
            OpenCodeStepRail(
                steps: [
                    .fold(count: 3, status: .failed),
                    .step(id: "5", title: "TimelineBlocks.swift", status: .completed, additions: 18, deletions: 4),
                    .step(id: "6", title: "Ran swift test", detail: "2 failures", status: .failed),
                    .step(id: "7", title: "Thought · 2.1s", status: .completed),
                ],
                isLive: true,
                thinking: true
            )
        }
        .padding()
    }
    .openCodeTheme()
}
#endif
