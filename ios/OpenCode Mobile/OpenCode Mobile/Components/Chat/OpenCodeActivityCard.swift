import SwiftUI

/// Live: "Working", the summary, running child sessions and the latest running
/// tool. Settled: one "N steps · Read, Edit" line with the worst status, then
/// the child sessions the run used.
enum OpenCodeActivityCardState: Equatable, Sendable {
    /// `startedAtMs`: the run's first start, for the header's elapsed time.
    case live(startedAtMs: Int64?)
    /// `stepCount`: every step of the run; `worstStatus` tints the leading dot.
    case settled(worstStatus: OpenCodeActivityLineStatus, stepCount: Int)
}

/// The family glyph in front of a tool; callers never pass a raw icon.
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

/// A child session on an Activity Card: running on a live card, any status on
/// a settled one.
struct OpenCodeActivityAgentRow: Equatable, Sendable, Identifiable {
    let id: String
    let agentName: String
    /// Live: what the child is doing now, or its description. Settled: its
    /// result line, or its description.
    let action: String?
    /// Live only: the child's start, for its elapsed time.
    let startedAtMs: Int64?
}

/// The live card's footer tool. Running: its running title, "+N" and elapsed.
/// Not `running`: a tool that just finished, in its settled form (settled
/// title only, no "+N", no elapsed). `stepID` keys the band's crossfade.
struct OpenCodeActivityCurrentRow: Equatable, Sendable {
    let icon: OpenCodeActivityLineIcon
    let title: String
    /// Other tools running at the same time ("+N").
    let extraRunning: Int
    let startedAtMs: Int64?
    var running: Bool = true
    var stepID: String?
}

/// Activity Card (#779): one card per run of an agent's steps. Live, a
/// shimmering "Working" header with elapsed time, a summary line, one row per
/// running child session, and a footer band with the latest running tool that
/// opens the live activity sheet. Settled, one "N steps · Read, Edit" line that
/// opens the same sheet, then the child sessions the run used, each pushing its
/// session. Nothing expands inline.
struct OpenCodeActivityCard: View {
    let state: OpenCodeActivityCardState
    /// Live: "6 steps · 3 reads · 1 search". Settled: the tool names, "Read, Edit, Agent".
    let summary: String
    /// Live: running child sessions. Settled: every child session of the run.
    let agents: [OpenCodeActivityAgentRow]
    /// Live only: the latest running tool, or nil while nothing but agents runs.
    let current: OpenCodeActivityCurrentRow?
    /// Live only, when `current` is nil: "Waiting for agents" / "Thinking…".
    let idleText: String?
    let onAgentTap: (String) -> Void
    let onOpen: () -> Void

    init(
        state: OpenCodeActivityCardState,
        summary: String,
        agents: [OpenCodeActivityAgentRow] = [],
        current: OpenCodeActivityCurrentRow? = nil,
        idleText: String? = nil,
        onAgentTap: @escaping (String) -> Void = { _ in },
        onOpen: @escaping () -> Void = {}
    ) {
        self.state = state
        self.summary = summary
        self.agents = agents
        self.current = current
        self.idleText = idleText
        self.onAgentTap = onAgentTap
        self.onOpen = onOpen
    }

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            switch state {
            case .live(let startedAtMs):
                liveCard(startedAtMs: startedAtMs)
            case .settled(let worstStatus, let stepCount):
                VStack(alignment: .leading, spacing: 0) {
                    settledLine(worstStatus: worstStatus, stepCount: stepCount)
                    if !agents.isEmpty {
                        agentSection
                    }
                }
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

    // MARK: - Live

    private func liveCard(startedAtMs: Int64?) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(action: onOpen) {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        OpenCodeRunningShimmerText(text: "Working", font: OpenCodeTypography.bodySecondary.weight(.semibold))
                        Spacer(minLength: 0)
                        if let startedAtMs {
                            OpenCodeLiveElapsedText(startedAtMs: startedAtMs)
                        }
                    }
                    Text(summary)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textWeak)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
                .padding(.top, ActivityCardMetrics.paddingVertical)
                .padding(.bottom, ActivityCardMetrics.summaryBottomPadding)
                .padding(.horizontal, ActivityCardMetrics.paddingHorizontal)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(headerAccessibilityLabel(startedAtMs: startedAtMs))
            .accessibilityHint("Opens activity")
            .accessibilityAddTraits(.isButton)
            .openCodeKeyboardFocusable(.insetRoundedRectangle(cornerRadius: ActivityCardMetrics.cornerRadius), activate: onOpen)

            if !agents.isEmpty {
                agentSection
            }
            footer
        }
    }

    private func headerAccessibilityLabel(startedAtMs: Int64?) -> String {
        var parts = ["Working", summary]
        if let startedAtMs {
            parts.append(OpenCodeStepRailElapsed.format(
                startedAtMs: startedAtMs,
                endMs: OpenCodeStepRailElapsed.nowMs(Date())
            ))
        }
        return parts.joined(separator: ", ")
    }

    private var agentSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            OpenCodeHairline()
            OpenCodeActivitySectionLabel(count: agents.count)
                .padding(.top, 6)
                .padding(.bottom, 2)
                .padding(.horizontal, ActivityCardMetrics.paddingHorizontal)
            ForEach(Array(agents.enumerated()), id: \.element.id) { index, agent in
                if index > 0 {
                    OpenCodeHairline()
                        .padding(.horizontal, ActivityCardMetrics.paddingHorizontal)
                }
                Button { onAgentTap(agent.id) } label: {
                    ActivityCardAgentRow(agent: agent)
                        .padding(.horizontal, ActivityCardMetrics.paddingHorizontal)
                }
                .buttonStyle(.plain)
                .openCodeKeyboardFocusable(.insetRoundedRectangle(cornerRadius: OpenCodeRadius.small, horizontalInset: 4), activate: { onAgentTap(agent.id) })
            }
        }
    }

    /// A new tool, a tool turning settled, or new idle text crossfades; "+N" and elapsed change in place.
    private var footerKey: String {
        guard let current else { return "idle:" + (idleText ?? "") }
        return "tool:\(current.stepID ?? current.title):\(current.running)"
    }

    private var footer: some View {
        Button(action: onOpen) {
            ZStack {
                footerContent
                    .id(footerKey)
                    .transition(.opacity)
            }
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: footerKey)
            .padding(.horizontal, ActivityCardMetrics.paddingHorizontal)
            .padding(.top, ActivityCardMetrics.footerPaddingTop)
            .padding(.bottom, ActivityCardMetrics.footerPaddingBottom)
            .frame(maxWidth: .infinity, minHeight: ActivityCardMetrics.tapRowMinHeight, alignment: .leading)
            .background(OpenCodeColors.surfaceRaisedBaseHover)
            .clipShape(
                UnevenRoundedRectangle(
                    bottomLeadingRadius: ActivityCardMetrics.cornerRadius - 1,
                    bottomTrailingRadius: ActivityCardMetrics.cornerRadius - 1,
                    style: .continuous
                )
            )
            .padding([.horizontal, .bottom], 1)
            .padding(.top, ActivityCardMetrics.footerTopMargin)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(footerAccessibilityLabel)
        .accessibilityHint("Opens activity")
        .accessibilityAddTraits(.isButton)
        .openCodeKeyboardFocusable(.insetRoundedRectangle(cornerRadius: ActivityCardMetrics.cornerRadius), activate: onOpen)
    }

    private var footerContent: some View {
        HStack(spacing: 8) {
            if let current {
                OpenCodeIconImage(icon: current.icon.icon, size: ActivityCardMetrics.iconSize)
                    .foregroundStyle(OpenCodeColors.iconWeakBase)
                    .frame(width: ActivityCardMetrics.iconSize)
                    .accessibilityHidden(true)
                Text(current.title)
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if current.running, current.extraRunning > 0 {
                    Text("+\(current.extraRunning)")
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textWeak)
                        .fixedSize()
                }
                if current.running, let startedAtMs = current.startedAtMs {
                    OpenCodeLiveElapsedText(startedAtMs: startedAtMs)
                }
            } else {
                Text(idleText ?? "Thinking\u{2026}")
                    .font(OpenCodeTypography.bodySecondary)
                    .italic()
                    .foregroundStyle(OpenCodeColors.textWeaker)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            OpenCodeActivityChevron()
        }
    }

    private var footerAccessibilityLabel: String {
        guard let current else { return idleText ?? "Thinking" }
        var parts = [current.title]
        guard current.running else { return current.title }
        if current.extraRunning > 0 { parts.append("\(current.extraRunning) more running") }
        if let startedAtMs = current.startedAtMs {
            parts.append(OpenCodeStepRailElapsed.format(
                startedAtMs: startedAtMs,
                endMs: OpenCodeStepRailElapsed.nowMs(Date())
            ))
        }
        return parts.joined(separator: ", ")
    }

    // MARK: - Settled

    /// Dot, the count semibold, "steps" muted, then the tool names muted.
    private func settledLine(worstStatus: OpenCodeActivityLineStatus, stepCount: Int) -> some View {
        Button(action: onOpen) {
            HStack(spacing: 8) {
                OpenCodeActivityStatusDot(status: worstStatus)
                settledText(stepCount: stepCount)
                    .font(OpenCodeTypography.bodySecondary)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)
                OpenCodeActivityChevron()
            }
            .padding(.horizontal, ActivityCardMetrics.paddingHorizontal)
            .padding(.vertical, ActivityCardMetrics.settledPaddingVertical)
            .frame(maxWidth: .infinity, minHeight: ActivityCardMetrics.tapRowMinHeight, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            [Self.stepsLabel(stepCount), summary, worstStatus == .failed ? "failed" : "completed"]
                .filter { !$0.isEmpty }
                .joined(separator: ", ")
        )
        .accessibilityHint("Opens activity")
        .accessibilityAddTraits(.isButton)
        .openCodeKeyboardFocusable(.insetRoundedRectangle(cornerRadius: ActivityCardMetrics.cornerRadius), activate: onOpen)
    }

    private func settledText(stepCount: Int) -> Text {
        let head = Text("\(stepCount)").fontWeight(.semibold).foregroundColor(OpenCodeColors.textStrong)
            + Text(stepCount == 1 ? " step" : " steps").foregroundColor(OpenCodeColors.textWeak)
        guard !summary.isEmpty else { return head }
        return head + Text(" \u{00B7} \(summary)").foregroundColor(OpenCodeColors.textWeak)
    }

    /// "1 step" / "3 steps": the settled line and the settled sheet header.
    static func stepsLabel(_ count: Int) -> String {
        count == 1 ? "1 step" : "\(count) steps"
    }
}

private enum ActivityCardMetrics {
    /// Same radius as the tool call card so settled lines and cards match.
    static let cornerRadius: CGFloat = 8
    static let paddingHorizontal: CGFloat = 12
    static let paddingVertical: CGFloat = 10
    static let summaryBottomPadding: CGFloat = 8
    static let settledPaddingVertical: CGFloat = 11
    /// Footer band and settled line are tap targets; keep them at least this tall.
    static let tapRowMinHeight: CGFloat = 44
    static let iconSize: CGFloat = 16
    static let footerTopMargin: CGFloat = 0
    static let footerPaddingTop: CGFloat = 12
    static let footerPaddingBottom: CGFloat = 13
}

/// Letter tile, agent name in the agent tint, action or result muted, elapsed
/// while live, chevron.
private struct ActivityCardAgentRow: View {
    let agent: OpenCodeActivityAgentRow

    var body: some View {
        HStack(spacing: 8) {
            OpenCodeActivityAgentTile(agentName: agent.agentName)
            Text(agent.agentName)
                .font(OpenCodeTypography.bodySecondary.weight(.semibold))
                .foregroundStyle(OpenCodeColors.info)
                .lineLimit(1)
                .fixedSize()
            Text(agent.action ?? "")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textWeak)
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let startedAtMs = agent.startedAtMs {
                OpenCodeLiveElapsedText(startedAtMs: startedAtMs)
            }
            OpenCodeActivityChevron()
        }
        .padding(.vertical, 9)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            [agent.agentName, agent.action].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: ", ")
        )
        .accessibilityHint("Opens the child session")
        .accessibilityAddTraits(.isButton)
    }
}

// MARK: - Shared pieces (card and activity sheet)

/// "AGENT" / "2 AGENTS": uppercase, tracked, in the weak text tone.
struct OpenCodeActivitySectionLabel: View {
    let text: String

    init(text: String) {
        self.text = text
    }

    /// The Agent section's label for `count` child sessions.
    init(count: Int) {
        self.text = count == 1 ? "Agent" : "\(count) agents"
    }

    var body: some View {
        Text(text.uppercased())
            .font(.system(size: 10.5, weight: .medium))
            .tracking(0.8)
            .foregroundStyle(OpenCodeColors.textWeak)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
    }
}

/// 20pt rounded tile with the agent's first letter in the agent tint.
struct OpenCodeActivityAgentTile: View {
    let agentName: String

    var body: some View {
        Text(agentName.first.map { String($0).uppercased() } ?? "?")
            .font(.system(size: 11, weight: .bold))
            .foregroundStyle(OpenCodeColors.info)
            .frame(width: 20, height: 20)
            .background(
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(OpenCodeColors.info.opacity(0.14))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .strokeBorder(OpenCodeColors.info.opacity(0.35), lineWidth: 1)
            )
            .accessibilityHidden(true)
    }
}

/// 1pt divider in the weak border tone.
struct OpenCodeHairline: View {
    var body: some View {
        Rectangle()
            .fill(OpenCodeColors.borderWeakBase)
            .frame(height: 1)
            .accessibilityHidden(true)
    }
}

/// Status dot; `pulsing` breathes while running (static under Reduce Motion).
struct OpenCodeActivityStatusDot: View {
    let status: OpenCodeActivityLineStatus
    var size: CGFloat = 8
    var pulsing = false

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            if pulsing, status.isInFlight, !reduceMotion {
                TimelineView(.animation) { context in
                    dot.opacity(Self.pulseOpacity(at: context.date))
                }
            } else {
                dot
            }
        }
        .accessibilityHidden(true)
    }

    private var dot: some View {
        Circle()
            .fill(fill)
            .frame(width: size, height: size)
    }

    private var fill: Color {
        switch status {
        case .completed: OpenCodeColors.success
        case .failed: OpenCodeColors.error
        case .running, .pending: OpenCodeColors.warning
        case .interrupted: OpenCodeColors.textWeaker
        }
    }

    private static let cycle = 1.2

    private static func pulseOpacity(at date: Date) -> Double {
        let phase = date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: cycle) / cycle
        return 0.4 + 0.6 * (0.5 + 0.5 * cos(phase * 2 * .pi))
    }
}

struct OpenCodeActivityChevron: View {
    var body: some View {
        OpenCodeIconImage(icon: .chevronRight, size: 14)
            .foregroundStyle(OpenCodeColors.textWeaker)
            .accessibilityHidden(true)
    }
}

/// Monospaced `m:ss` since `startedAtMs` that ticks every second, or a fixed
/// span when `endedAtMs` is known.
struct OpenCodeLiveElapsedText: View {
    let startedAtMs: Int64
    var endedAtMs: Int64?

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
            .accessibilityHidden(true)
    }
}

/// Text with a sweeping highlight, the same clock-driven treatment as a running
/// Tool Chip, so a re-created view never restarts it. Static under Reduce Motion.
struct OpenCodeRunningShimmerText: View {
    let text: String
    let font: Font

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if reduceMotion {
            label.foregroundStyle(OpenCodeColors.textStrong)
        } else {
            TimelineView(.animation) { context in
                label.foregroundStyle(Self.shimmer(at: context.date))
            }
        }
    }

    private var label: some View {
        Text(text)
            .font(font)
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

#if DEBUG
#Preview("Activity Card") {
    let now = Int64(Date().timeIntervalSince1970 * 1000)
    ScrollView {
        VStack(alignment: .leading, spacing: 16) {
            OpenCodeActivityCard(
                state: .live(startedAtMs: now - 64_000),
                summary: "6 steps \u{00B7} 3 reads \u{00B7} 1 search \u{00B7} 1 agent \u{00B7} 1 edit",
                agents: [
                    OpenCodeActivityAgentRow(id: "c1", agentName: "Explore", action: "Searching \"branch\"", startedAtMs: now - 40_000),
                ],
                current: OpenCodeActivityCurrentRow(
                    icon: .edit,
                    title: "Editing SessionChatRepoStrip.swift",
                    extraRunning: 1,
                    startedAtMs: now - 4_000
                )
            )
            OpenCodeActivityCard(
                state: .settled(worstStatus: .failed, stepCount: 7),
                summary: "Read, Grep, Edit, Shell, Agent"
            )
            OpenCodeActivityCard(
                state: .settled(worstStatus: .completed, stepCount: 3),
                summary: "Agent, Thought",
                agents: [
                    OpenCodeActivityAgentRow(id: "c1", agentName: "Explore", action: "Found 3 call sites", startedAtMs: nil),
                    OpenCodeActivityAgentRow(id: "c2", agentName: "General", action: "Audit the parser", startedAtMs: nil),
                ]
            )
        }
        .padding()
    }
    .openCodeTheme()
}
#endif
