import Factory
import SwiftUI

struct SessionContextView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: SessionContextViewModel

    init(sessionID: String, worktree: String, viewModel: SessionContextViewModel? = nil) {
        _viewModel = State(initialValue: viewModel ?? Container.makeSessionContextViewModel(sessionID: sessionID, worktree: worktree))
    }

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "Session Context",
                leading: .back(action: { dismiss() }),
                showDivider: true
            )
            content
        }
        .background(OpenCodeColors.backgroundPrimary)
        .toolbar(.hidden, for: .navigationBar)
        .task { await viewModel.load() }
    }

    @ViewBuilder
    private var content: some View {
        switch viewModel.state {
        case .loading:
            Spacer()
            ProgressView()
            Spacer()
        case let .error(message):
            VStack(spacing: 12) {
                Text(message)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textSecondary)
                    .multilineTextAlignment(.center)
                OpenCodeButton(variant: .fullWidthText(title: "Retry")) {
                    Task { await viewModel.load() }
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        case let .loaded(displayState):
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    statsGrid(displayState.stats)
                    if !displayState.breakdown.isEmpty {
                        breakdown(displayState.breakdown)
                    }
                    rawMessages(displayState.rawMessages)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 18)
            }
            .refreshable { await viewModel.refresh() }
            .overlay {
                if viewModel.isRefreshing {
                    ProgressView()
                }
            }
        }
    }

    private func statsGrid(_ stats: [SessionContextStatItem]) -> some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], alignment: .leading, spacing: 20) {
            ForEach(stats) { stat in
                VStack(alignment: .leading, spacing: 6) {
                    Text(stat.label)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textSecondary)
                    Text(stat.value)
                        .font(OpenCodeTypography.body)
                        .foregroundStyle(OpenCodeColors.textPrimary)
                        .lineLimit(2)
                        .minimumScaleFactor(0.85)
                }
            }
        }
    }

    private func breakdown(_ items: [SessionContextBreakdownItem]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Context Breakdown")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textSecondary)
            GeometryReader { proxy in
                HStack(spacing: 0) {
                    ForEach(items) { item in
                        Rectangle()
                            .fill(color(for: item.kind))
                            .frame(width: max(1, proxy.size.width * item.widthPercent / 100))
                    }
                }
                .clipShape(Capsule())
            }
            .frame(height: 8)
            FlowLayout(spacing: 12, lineSpacing: 6) {
                ForEach(items) { item in
                    HStack(spacing: 6) {
                        Circle()
                            .fill(color(for: item.kind))
                            .frame(width: 8, height: 8)
                        Text("\(label(for: item.kind)) \(item.displayPercent, specifier: "%.1f")%")
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.textSecondary)
                    }
                }
            }
            Text("Approximate breakdown of input tokens. Other includes tool definitions and overhead.")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textSecondary)
        }
    }

    private func rawMessages(_ messages: [SessionContextRawMessageRow]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Raw messages")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textSecondary)
            VStack(spacing: 0) {
                ForEach(messages) { message in
                    HStack(spacing: 8) {
                        Text(message.role.rawValue)
                            .font(OpenCodeTypography.body)
                            .foregroundStyle(OpenCodeColors.textPrimary)
                        Text("• \(message.id)")
                            .font(OpenCodeTypography.body)
                            .foregroundStyle(OpenCodeColors.textSecondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Spacer(minLength: 8)
                        Text(rawTime(message.createdAtEpochMs))
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.textSecondary)
                            .lineLimit(1)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    if message.id != messages.last?.id {
                        Divider().background(OpenCodeColors.borderSubtle)
                    }
                }
            }
            .background(OpenCodeColors.surfacePrimary, in: RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(OpenCodeColors.borderSubtle, lineWidth: 1)
            )
        }
    }

    private func rawTime(_ epochMs: Int64?) -> String {
        guard let epochMs else { return "—" }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(epochMs) / 1000))
    }

    private func label(for kind: SessionContextBreakdownItem.Kind) -> String {
        switch kind {
        case .system: "System"
        case .user: "User"
        case .assistant: "Assistant"
        case .tool: "Tool Calls"
        case .other: "Other"
        }
    }

    private func color(for kind: SessionContextBreakdownItem.Kind) -> Color {
        switch kind {
        case .system: OpenCodeColors.syntaxInfo
        case .user: OpenCodeColors.syntaxSuccess
        case .assistant: OpenCodeColors.syntaxProperty
        case .tool: OpenCodeColors.syntaxWarning
        case .other: OpenCodeColors.syntaxComment
        }
    }
}

private struct FlowLayout: Layout {
    let spacing: CGFloat
    let lineSpacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        layout(in: proposal.width ?? 0, subviews: subviews).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let result = layout(in: bounds.width, subviews: subviews)
        for (index, origin) in result.origins.enumerated() {
            subviews[index].place(at: CGPoint(x: bounds.minX + origin.x, y: bounds.minY + origin.y), proposal: .unspecified)
        }
    }

    private func layout(in width: CGFloat, subviews: Subviews) -> (size: CGSize, origins: [CGPoint]) {
        var origins: [CGPoint] = []
        var cursor = CGPoint.zero
        var lineHeight: CGFloat = 0
        let maxWidth = max(width, 1)
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if cursor.x > 0, cursor.x + size.width > maxWidth {
                cursor.x = 0
                cursor.y += lineHeight + lineSpacing
                lineHeight = 0
            }
            origins.append(cursor)
            cursor.x += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
        return (CGSize(width: maxWidth, height: cursor.y + lineHeight), origins)
    }
}
