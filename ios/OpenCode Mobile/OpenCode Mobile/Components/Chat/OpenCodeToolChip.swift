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
    /// Truncation affordance: `+N more`.
    case more(count: Int)
}

enum OpenCodeToolChipStatus: String, CaseIterable, Sendable {
    case pending
    case running
    case completed
    case error
}

struct OpenCodeToolChip: View {
    let variant: OpenCodeToolChipVariant
    let status: OpenCodeToolChipStatus
    let trailingText: String?
    let action: (() -> Void)?

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
    }

    private var chipContent: some View {
        HStack(spacing: Self.contentSpacing) {
            if let icon = leadingIcon {
                OpenCodeIconImage(icon: icon, size: Self.iconSize)
                    .foregroundStyle(iconColor)
                    .fixedSize()
            }

            // Label shrinks before anything else so a long path or command
            // truncates instead of pushing the status glyph off the chip; the
            // chip itself may take the whole row (see OpenCodeChipFlowLayout).
            Text(label)
                .font(labelFont)
                .foregroundStyle(labelColor)
                .lineLimit(1)
                .truncationMode(labelTruncationMode)
                .layoutPriority(1)

            if case .agent(_, let brief?) = variant, !brief.isEmpty {
                Text(brief)
                    .font(Self.briefFont)
                    .foregroundStyle(OpenCodeColors.textWeak)
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

    // MARK: - Content

    private var label: String {
        switch variant {
        case .file(let label, _, _):
            label
        case .command(let label):
            label
        case .agent(let name, _):
            name
        case .skill(let name):
            name.hasPrefix("/") ? name : "/\(name)"
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
            .subdirectoryArrowRight
        case .skill:
            .list
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
        case .running?:
            ProgressView()
                .controlSize(.mini)
                .tint(OpenCodeColors.warning)
                .frame(width: Self.glyphSize, height: Self.glyphSize)
        case .pending?, nil:
            EmptyView()
        }
    }

    // MARK: - Styling

    private var labelFont: Font {
        switch variant {
        case .file, .command, .skill:
            Self.monoLabelFont
        case .agent, .more:
            Self.systemLabelFont
        }
    }

    private var labelColor: Color {
        switch (variant, effectiveStatus) {
        case (.more, _):
            OpenCodeColors.textBase
        case (_, .error?):
            OpenCodeColors.error
        case (.agent, _):
            OpenCodeColors.info
        case (.skill, _):
            OpenCodeColors.textInteractiveBase
        case (_, .pending?):
            OpenCodeColors.textBase
        default:
            OpenCodeColors.textStrong
        }
    }

    private var iconColor: Color {
        switch (variant, effectiveStatus) {
        case (_, .error?):
            OpenCodeColors.error
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
        case (_, .running?):
            OpenCodeColors.warning.opacity(Self.runningBackgroundOpacity)
        case (.agent, _):
            OpenCodeColors.info.opacity(Self.agentBackgroundOpacity)
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
        case (_, .running?):
            OpenCodeColors.warning.opacity(Self.stateBorderOpacity)
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
            parts.append("Agent \(name)")
            if let brief, !brief.isEmpty { parts.append(brief) }
        case .skill(let name):
            parts.append("Skill \(name)")
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

    // MARK: - Metrics

    private static let cornerRadius: CGFloat = 8
    private static let paddingHorizontal: CGFloat = 10
    private static let paddingBeforeIcon: CGFloat = 8
    private static let paddingVertical: CGFloat = 6
    private static let contentSpacing: CGFloat = 6
    private static let iconSize: CGFloat = 12
    private static let glyphSize: CGFloat = 11
    private static let minTapTarget: CGFloat = 44
    private static let runningBackgroundOpacity = 0.12
    private static let agentBackgroundOpacity = 0.14
    private static let stateBorderOpacity = 0.4
    private static let agentBorderOpacity = 0.35
    private static let monoLabelFont = Font.system(size: 13, weight: .regular, design: .monospaced)
    private static let systemLabelFont = Font.system(size: 13, weight: .regular)
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
            OpenCodeToolChip(variant: .command(label: "swift build"), status: .pending)
            OpenCodeToolChip(variant: .agent(name: "Explore", brief: "Find the session list"), trailingText: "1m 4s")
            OpenCodeToolChip(variant: .agent(name: "Build", brief: "Android half"), status: .running)
            OpenCodeToolChip(variant: .agent(name: "Review"), status: .error)
            OpenCodeToolChip(variant: .skill(name: "feature"))
            OpenCodeToolChip(variant: .more(count: 3), action: {})
        }
        .padding()
    }
    .openCodeTheme()
}
#endif
