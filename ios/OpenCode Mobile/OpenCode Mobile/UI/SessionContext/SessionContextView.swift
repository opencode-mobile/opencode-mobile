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
                leading: .back(action: {
                    logAction(ProductAnalytics.Action.sessionContextBack)
                    dismiss()
                }),
                showDivider: true
            )
            content
                .openCodeReadableColumn()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(OpenCodeColors.backgroundBase)
        .toolbar(.hidden, for: .navigationBar)
        .task {
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.sessionContext,
                presentation: ProductAnalytics.Presentation.route
            )
            await viewModel.load()
        }
    }

    private func logAction(_ actionId: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionContext,
            actionId: actionId
        )
    }

    @ViewBuilder
    private var content: some View {
        switch viewModel.state {
        case .loading:
            OpenCodeStatusStack(
                title: "Loading session context",
                subtitle: "Fetching context…"
            )
        case let .error(message):
            OpenCodeEmptyState(
                title: "Unable to load session context",
                message: message,
                primaryAction: OpenCodeEmptyStateAction(title: "Retry") {
                    logAction(ProductAnalytics.Action.sessionContextRetry)
                    Task { await viewModel.load() }
                }
            )
        case let .loaded(displayState):
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    if let refreshErrorMessage = viewModel.refreshErrorMessage {
                        Text(refreshErrorMessage)
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.textBase)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    statsGrid(displayState.stats)
                    if !displayState.breakdown.isEmpty {
                        breakdown(displayState.breakdown)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 18)
            }
            .refreshable {
                logAction(ProductAnalytics.Action.sessionContextRefresh)
                await viewModel.refresh()
            }
            .overlay {
                if viewModel.isRefreshing {
                    OpenCodeStatusStack(spinner: "Refreshing session context")
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
                        .foregroundStyle(OpenCodeColors.textBase)
                    Text(stat.value)
                        .font(OpenCodeTypography.body)
                        .foregroundStyle(OpenCodeColors.textStrong)
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
                .foregroundStyle(OpenCodeColors.textBase)
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
                        RoundedRectangle(cornerRadius: 2)
                            .fill(color(for: item.kind))
                            .frame(width: 8, height: 8)
                        Text("\(label(for: item.kind)) \(item.displayPercent, specifier: "%.1f")%")
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.textBase)
                    }
                }
            }
            Text("Approximate breakdown of input tokens. Other includes tool definitions and overhead.")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
        }
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
