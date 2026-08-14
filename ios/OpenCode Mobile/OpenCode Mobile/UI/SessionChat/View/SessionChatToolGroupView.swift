import SwiftUI

struct SessionChatToolGroupTarget: Hashable {
    let blockID: String
    let title: String
    let subtitle: String?
    let members: [SessionChatToolCardBlock]
    let messageID: String

    func hash(into hasher: inout Hasher) {
        hasher.combine(blockID)
    }

    static func == (lhs: SessionChatToolGroupTarget, rhs: SessionChatToolGroupTarget) -> Bool {
        lhs.blockID == rhs.blockID
            && lhs.title == rhs.title
            && lhs.subtitle == rhs.subtitle
            && lhs.members == rhs.members
            && lhs.messageID == rhs.messageID
    }
}

struct SessionChatToolGroupView: View {
    let target: SessionChatToolGroupTarget
    let supportsSessionMessageDiff: Bool
    let onNavigateBack: () -> Void
    let onToolDetailTap: (SessionChatToolDetailTarget) -> Void
    let onChildSessionTap: (String, String) -> Void
    let onChangesReviewTap: (String, String?) -> Void

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: target.title,
                subtitle: target.subtitle,
                leading: .back(consumesInteractivePop: true, action: {
                    ProductAnalytics.logUserAction(
                        screenId: ProductAnalytics.Screen.sessionToolGroup,
                        actionId: ProductAnalytics.Action.sessionToolGroupBack
                    )
                    onNavigateBack()
                }),
                showDivider: true
            )
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(target.members.enumerated()), id: \.offset) { _, card in
                        OpenCodeToolCallCard(
                            status: card.status.toolGroupCardStatus,
                            title: card.title,
                            subtitle: card.subtitle,
                            additions: card.additions,
                            deletions: card.deletions,
                            errorText: card.errorText,
                            action: toolCardAction(for: card)
                        )
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .toolbar(.hidden, for: .navigationBar)
        .navigationBarBackButtonHidden(true)
        .task {
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.sessionToolGroup,
                presentation: ProductAnalytics.Presentation.route
            )
        }
    }

    private func toolCardAction(for card: SessionChatToolCardBlock) -> (() -> Void)? {
        if let childSessionId = card.childSessionId {
            return { onChildSessionTap(childSessionId, card.title) }
        }
        if card.opensChangesReview, supportsSessionMessageDiff {
            return { onChangesReviewTap(target.messageID, card.changesInitialFilePath) }
        }
        if let detail = card.detailText {
            return {
                onToolDetailTap(
                    SessionChatToolDetailTarget(
                        title: card.title,
                        body: detail
                    )
                )
            }
        }
        return nil
    }
}

private extension ToolStatus {
    var toolGroupCardStatus: OpenCodeToolCallCardStatus {
        switch self {
        case .pending: .pending
        case .running: .running
        case .completed: .completed
        case .error: .error
        }
    }
}
