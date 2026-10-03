import SwiftUI

/// What a Tool Chip stands for. Variant-first: the chip decides icon, typography
/// and colour from this plus `status`; callers never pass raw styling.
enum OpenCodeToolChipVariant: Equatable, Sendable {
    /// A file the agent read, searched, edited or wrote. Diff counts are edit/write only.
    case file(label: String, additions: Int? = nil, deletions: Int? = nil)
    /// A shell / execute call, labelled by its first words.
    case command(label: String)
    /// A child session, labelled by agent name with an optional one-line brief.
    case agent(name: String, brief: String? = nil)
    /// A skill invocation (`/name`).
    case skill(name: String)
    /// One chip for a paragraph's explore tools: `Explored · 4 reads · 2 greps`
    /// (or `Exploring · …` while running). Completed shows no check mark.
    case explore(label: String)
    /// Truncation affordance: `+N more`.
    case more(count: Int)
}

enum OpenCodeToolChipStatus: String, CaseIterable, Sendable {
    case pending
    case running
    case completed
    case error
    /// Stopped by the user (Stop, Deny, Dismiss): neutral, not a failure.
    case interrupted
}

struct OpenCodeToolChip: View {
    let variant: OpenCodeToolChipVariant
    let status: OpenCodeToolChipStatus
    let trailingText: String?
    let action: (() -> Void)?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Set once the chip has been running for `runningRevealDelay`; until then
    /// a running chip looks idle, so commands that finish quickly never flash.
    @State private var runningRevealed = false

    init(
        variant: OpenCodeToolChipVariant,
        status: OpenCodeToolChipStatus = .completed,
        trailingText: String? = nil,
        action: (() -> Void)? = nil
    ) {
        switch variant {
        case .file(_, let additions, let deletions):
            precondition(additions.map { $0 >= 0 } ?? true, "Tool Chip additions must be nonnegative")
            precondition(deletions.map { $0 >= 0 } ?? true, "Tool Chip deletions must be nonnegative")
        case .more(let count):
            precondition(count > 0, "Tool Chip more count must be positive")
        case .explore(let label):
            precondition(!label.isEmpty, "Tool Chip explore label must not be empty")
        case .command, .agent, .skill:
            break
        }
        self.variant = variant
        self.status = status
        self.trailingText = trailingText
        self.action = action
    }

    var body: some View {
        Group {
            if let action {
                Button(action: action) {
                    chipContent
                }
                .buttonStyle(.plain)
            } else {
                chipContent
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityAddTraits(action == nil ? [] : .isButton)
        .task(id: isRunning) {
            guard isRunning else {
                runningRevealed = false
                return
            }
            try? await Task.sleep(for: Self.runningRevealDelay)
            guard !Task.isCancelled else { return }
            withAnimation(.easeOut(duration: Self.runningFadeDuration)) {
                runningRevealed = true
            }
        }
    }

    private var chipContent: some View {
        HStack(spacing: Self.contentSpacing) {
            if let icon = leadingIcon {
                if case .agent = variant {
                    // Agents get a tinted tile so the chip reads as "someone
                    // else did this" without tinting the whole chip.
                    leadingIconImage(icon)
                        .frame(width: Self.agentTileSize, height: Self.agentTileSize)
                        .background(
                            OpenCodeColors.info.opacity(Self.agentTileOpacity),
                            in: RoundedRectangle(cornerRadius: Self.agentTileCornerRadius)
                        )
                } else {
                    leadingIconImage(icon)
                }
            }

            // Label shrinks before anything else so a long path or command
            // truncates instead of pushing the status glyph off the chip; the
            // chip itself may take the whole row (see OpenCodeChipFlowLayout).
            labelView
                .layoutPriority(1)

            if case .agent(_, let brief?) = variant, !brief.isEmpty {
                Text(brief)
                    .font(Self.briefFont)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    // Gives way first, so the agent name stays whole.
                    .layoutPriority(-1)
            }

            if let diffCounts {
                diffCounts
                    .font(Self.diffFont)
                    .lineLimit(1)
                    .fixedSize()
            }

            statusGlyph

            if let trailingText, !trailingText.isEmpty, !isMore {
                Text(trailingText)
                    .font(Self.trailingFont.monospacedDigit())
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .lineLimit(1)
                    .fixedSize()
            }
        }
        .padding(.leading, leadingIcon == nil ? Self.paddingHorizontal : Self.paddingBeforeIcon)
        .padding(.trailing, Self.paddingHorizontal)
        .padding(.vertical, Self.paddingVertical)
        // Never shorter than the other pills (branch, review changes, action pill).
        .frame(minHeight: Self.minHeight)
        .background {
            RoundedRectangle(cornerRadius: Self.cornerRadius)
                .fill(backgroundColor)
        }
        .overlay {
            RoundedRectangle(cornerRadius: Self.cornerRadius)
                .strokeBorder(
                    borderColor,
                    style: StrokeStyle(lineWidth: 1, dash: isPending ? [3, 2] : [])
                )
        }
        // 44pt tap target without growing the visual chip or overlapping neighbours.
        .contentShape(ToolChipHitShape(minHeight: Self.minTapTarget))
    }

    // MARK: - Running treatment

    private var isRunning: Bool {
        effectiveStatus == .running
    }

    /// Running and past the reveal delay: the label shimmers toward the
    /// warning colour and the leading icon breathes in step. The chip's fill
    /// and outline stay as they are.
    private var showsRunningTreatment: Bool {
        isRunning && runningRevealed
    }

    private var animatesRunningTreatment: Bool {
        showsRunningTreatment && !reduceMotion
    }

    private var labelText: Text {
        Text(label)
            .font(labelFont)
    }

    @ViewBuilder
    private var labelView: some View {
        if animatesRunningTreatment {
            TimelineView(.animation) { context in
                labelText
                    .foregroundStyle(Self.runningShimmer(phase: Self.runningPhase(at: context.date)))
                    .lineLimit(1)
                    .truncationMode(labelTruncationMode)
            }
        } else {
            labelText
                .foregroundStyle(labelColor)
                .lineLimit(1)
                .truncationMode(labelTruncationMode)
        }
    }

    @ViewBuilder
    private func leadingIconImage(_ icon: OpenCodeIcon) -> some View {
        if animatesRunningTreatment {
            TimelineView(.animation) { context in
                OpenCodeIconImage(icon: icon, size: Self.iconSize)
                    .foregroundStyle(iconColor)
                    .opacity(Self.runningIconOpacity(phase: Self.runningPhase(at: context.date)))
                    .fixedSize()
            }
        } else {
            OpenCodeIconImage(icon: icon, size: Self.iconSize)
                .foregroundStyle(iconColor)
                .fixedSize()
        }
    }

    /// 0…1 through one running cycle; every running chip shares the clock so
    /// neighbouring chips sweep in step.
    private static func runningPhase(at date: Date) -> Double {
        date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: runningCycle) / runningCycle
    }

    /// A warning-coloured band, `2 × shimmerHalfWidth` label widths wide,
    /// sweeping left to right across `textBase` text.
    private static func runningShimmer(phase: Double) -> LinearGradient {
        let center = -shimmerHalfWidth + phase * (1 + 2 * shimmerHalfWidth)
        return LinearGradient(
            colors: [OpenCodeColors.textBase, OpenCodeColors.warning, OpenCodeColors.textBase],
            startPoint: UnitPoint(x: center - shimmerHalfWidth, y: 0.5),
            endPoint: UnitPoint(x: center + shimmerHalfWidth, y: 0.5)
        )
    }

    /// Full opacity at the start of the cycle, dimmest halfway through.
    private static func runningIconOpacity(phase: Double) -> Double {
        let mid = (1 + runningIconMinOpacity) / 2
        return mid + (1 - mid) * cos(2 * .pi * phase)
    }

    // MARK: - Content

    private var label: String {
        switch variant {
        case .file(let label, _, _):
            label
        case .command(let label):
            label
        case .agent(let name, _):
            Self.displayAgentName(name)
        case .skill(let name):
            name.hasPrefix("/") ? name : "/\(name)"
        case .explore(let label):
            label
        case .more(let count):
            "+\(count) more"
        }
    }

    /// File paths keep their extension visible; commands read left to right.
    private var labelTruncationMode: Text.TruncationMode {
        if case .file = variant { return .middle }
        return .tail
    }

    private var isMore: Bool {
        if case .more = variant { return true }
        return false
    }

    private var isPending: Bool {
        !isMore && status == .pending
    }

    private var effectiveStatus: OpenCodeToolChipStatus? {
        isMore ? nil : status
    }

    private var leadingIcon: OpenCodeIcon? {
        switch variant {
        case .file(_, let additions, let deletions):
            additions != nil || deletions != nil ? .edit : .description
        case .command:
            .terminal
        case .agent:
            .callSplit
        case .skill:
            .list
        case .explore:
            .search
        case .more:
            nil
        }
    }

    private var diffCounts: Text? {
        guard case .file(_, let additions, let deletions) = variant else { return nil }
        var parts: [Text] = []
        if let additions, additions > 0 {
            parts.append(Text("+\(additions)").foregroundColor(OpenCodeColors.diffAdd))
        }
        if let deletions, deletions > 0 {
            if !parts.isEmpty {
                parts.append(Text(" "))
            }
            parts.append(Text("−\(deletions)").foregroundColor(OpenCodeColors.diffDelete))
        }
        guard !parts.isEmpty else { return nil }
        return parts.reduce(Text("")) { $0 + $1 }
    }

    @ViewBuilder
    private var statusGlyph: some View {
        switch effectiveStatus {
        case .completed?:
            if case .command = variant {
                OpenCodeIconImage(icon: .check, size: Self.glyphSize)
                    .foregroundStyle(OpenCodeColors.success)
                    .fixedSize()
            } else if case .agent = variant {
                OpenCodeIconImage(icon: .check, size: Self.glyphSize)
                    .foregroundStyle(OpenCodeColors.success)
                    .fixedSize()
            }
        case .error?:
            OpenCodeIconImage(icon: .close, size: Self.glyphSize)
                .foregroundStyle(OpenCodeColors.error)
                .fixedSize()
        case .interrupted?:
            OpenCodeIconImage(icon: .stopCircle, size: Self.glyphSize)
                .foregroundStyle(OpenCodeColors.textWeak)
                .fixedSize()
        case .running?, .pending?, nil:
            EmptyView()
        }
    }

    // MARK: - Styling

    private var labelFont: Font {
        switch variant {
        case .file, .command, .skill:
            Self.monoLabelFont
        case .agent:
            Self.agentLabelFont
        case .more, .explore:
            Self.systemLabelFont
        }
    }

    private var labelColor: Color {
        switch (variant, effectiveStatus) {
        case (.more, _):
            OpenCodeColors.textBase
        case (_, .error?):
            OpenCodeColors.error
        case (.skill, _):
            OpenCodeColors.textInteractiveBase
        case (_, .pending?), (_, .interrupted?):
            OpenCodeColors.textBase
        default:
            OpenCodeColors.textStrong
        }
    }

    private var iconColor: Color {
        switch (variant, effectiveStatus) {
        case (_, .error?):
            OpenCodeColors.error
        case (_, .running?) where showsRunningTreatment:
            OpenCodeColors.warning
        case (.agent, _):
            OpenCodeColors.info
        case (.skill, _):
            OpenCodeColors.textInteractiveBase
        default:
            OpenCodeColors.iconWeakBase
        }
    }

    private var backgroundColor: Color {
        switch (variant, effectiveStatus) {
        case (_, .error?):
            OpenCodeColors.surfaceCriticalBase
        case (.skill, _):
            OpenCodeColors.surfaceInteractiveWeak
        default:
            OpenCodeColors.surfaceRaisedBase
        }
    }

    private var borderColor: Color {
        switch (variant, effectiveStatus) {
        case (_, .error?):
            OpenCodeColors.error.opacity(Self.stateBorderOpacity)
        case (.agent, _):
            OpenCodeColors.info.opacity(Self.agentBorderOpacity)
        default:
            OpenCodeColors.borderWeakBase
        }
    }

    // MARK: - Accessibility

    private var accessibilityLabel: String {
        var parts: [String] = []
        switch variant {
        case .file(let label, let additions, let deletions):
            parts.append("File \(label)")
            if let additions, additions > 0 { parts.append("+\(additions)") }
            if let deletions, deletions > 0 { parts.append("−\(deletions)") }
        case .command(let label):
            parts.append("Command \(label)")
        case .agent(let name, let brief):
            parts.append("Agent \(Self.displayAgentName(name))")
            if let brief, !brief.isEmpty { parts.append(brief) }
        case .skill(let name):
            parts.append("Skill \(name)")
        case .explore(let label):
            parts.append(label)
        case .more(let count):
            parts.append("\(count) more")
        }
        if let effectiveStatus {
            parts.append(effectiveStatus.rawValue)
        }
        if let trailingText, !trailingText.isEmpty, !isMore {
            parts.append(trailingText)
        }
        return parts.joined(separator: ", ")
    }

    /// Server agent names are lowercase identifiers (`explore`, `build`);
    /// the chip shows them as a name.
    static func displayAgentName(_ name: String) -> String {
        guard let first = name.first else { return name }
        return first.uppercased() + name.dropFirst()
    }

    // MARK: - Metrics

    private static let cornerRadius: CGFloat = 8
    private static let paddingHorizontal: CGFloat = 10
    private static let paddingBeforeIcon: CGFloat = 8
    private static let paddingVertical: CGFloat = 6
    private static let contentSpacing: CGFloat = 6
    private static let iconSize: CGFloat = 12
    private static let glyphSize: CGFloat = 11
    private static let minHeight: CGFloat = 36
    private static let minTapTarget: CGFloat = 44
    private static let runningRevealDelay: Duration = .milliseconds(250)
    private static let runningFadeDuration = 0.25
    private static let runningCycle = 1.3
    private static let shimmerHalfWidth = 0.3
    private static let runningIconMinOpacity = 0.35
    private static let agentTileOpacity = 0.14
    private static let agentTileSize: CGFloat = 18
    private static let agentTileCornerRadius: CGFloat = 5
    private static let stateBorderOpacity = 0.4
    private static let agentBorderOpacity = 0.35
    private static let monoLabelFont = Font.system(size: 13, weight: .regular, design: .monospaced)
    private static let systemLabelFont = Font.system(size: 13, weight: .regular)
    private static let agentLabelFont = Font.system(size: 13, weight: .semibold)
    private static let briefFont = Font.system(size: 12, weight: .regular)
    private static let diffFont = Font.system(size: 12, weight: .semibold)
    private static let trailingFont = Font.system(size: 12, weight: .regular)
}

/// Hit region that grows vertically to `minHeight` around the chip's bounds
/// while keeping its horizontal extent, so neighbouring chips never overlap.
private struct ToolChipHitShape: Shape {
    let minHeight: CGFloat

    func path(in rect: CGRect) -> Path {
        let extra = max(0, (minHeight - rect.height) / 2)
        return Path(rect.insetBy(dx: 0, dy: -extra))
    }
}

#if DEBUG
#Preview("Tool Chip") {
    ScrollView {
        VStack(alignment: .leading, spacing: 12) {
            OpenCodeToolChip(variant: .file(label: "SessionChatView.swift"))
            OpenCodeToolChip(variant: .file(label: "OpenCodeListItem.swift", additions: 12, deletions: 3))
            OpenCodeToolChip(variant: .command(label: "xcodebuild test"), status: .completed)
            OpenCodeToolChip(variant: .command(label: "xcodebuild test"), status: .running, trailingText: "12s")
            OpenCodeToolChip(variant: .command(label: "npm test"), status: .error)
            OpenCodeToolChip(variant: .command(label: "./deploy.sh"), status: .interrupted)
            OpenCodeToolChip(variant: .command(label: "swift build"), status: .pending)
            OpenCodeToolChip(variant: .agent(name: "Explore", brief: "Find the session list"), trailingText: "1m 4s")
            OpenCodeToolChip(variant: .agent(name: "Build", brief: "Android half"), status: .running)
            OpenCodeToolChip(variant: .agent(name: "Review"), status: .error)
            OpenCodeToolChip(variant: .skill(name: "feature"))
            OpenCodeToolChip(variant: .explore(label: "Explored \u{00B7} 4 reads \u{00B7} 2 greps"), action: {})
            OpenCodeToolChip(variant: .explore(label: "Exploring \u{00B7} 3 reads"), status: .running, action: {})
            OpenCodeToolChip(variant: .more(count: 3), action: {})
        }
        .padding()
    }
    .openCodeTheme()
}
#endif
