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
    @Environment(\.dismiss) private var dismiss
    let target: SessionChatToolGroupTarget
    let worktree: String
    let parentSessionID: String
    let supportsSessionMessageDiff: Bool
    let makeChangesTarget: (String, String?) -> ChangesNavigationTarget?

    @State private var changesTarget: ChangesNavigationTarget?
    @State private var childSessionTarget: ChildSessionNavigationTarget?
    @State private var toolDetailTarget: SessionChatToolDetailTarget?

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: target.title,
                subtitle: target.subtitle,
                leading: .back(action: {
                    ProductAnalytics.logUserAction(
                        screenId: ProductAnalytics.Screen.sessionToolGroup,
                        actionId: ProductAnalytics.Action.sessionToolGroupBack
                    )
                    dismiss()
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
            .openCodeReadableColumn()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .toolbar(.hidden, for: .navigationBar)
        .navigationBarBackButtonHidden(true)
        .sessionChatToolDetailSheet(target: $toolDetailTarget)
        .navigationDestination(item: $changesTarget) { destination in
            ChangesView(
                sessionID: destination.sessionID,
                worktree: destination.worktree,
                messageID: destination.messageID,
                initialSource: destination.initialSource,
                initialFilePath: destination.initialFilePath
            )
        }
        .navigationDestination(item: $childSessionTarget) { destination in
            SessionChatView(session: destination.sessionListItem)
        }
        .task {
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.sessionToolGroup,
                presentation: ProductAnalytics.Presentation.route
            )
        }
    }

    private func toolCardAction(for card: SessionChatToolCardBlock) -> (() -> Void)? {
        if let childSessionId = card.childSessionId {
            return {
                ProductAnalytics.logUserAction(
                    screenId: ProductAnalytics.Screen.sessionChat,
                    actionId: ProductAnalytics.Action.chatOpenChildSession
                )
                childSessionTarget = ChildSessionNavigationTarget(
                    sessionID: childSessionId,
                    sessionTitle: card.title,
                    worktree: worktree,
                    optimisticNavigationInstanceID: nil,
                    parentID: parentSessionID
                )
            }
        }
        if card.opensChangesReview, supportsSessionMessageDiff {
            return {
                ProductAnalytics.logUserAction(
                    screenId: ProductAnalytics.Screen.sessionChat,
                    actionId: ProductAnalytics.Action.chatOpenChangesFromTool
                )
                changesTarget = makeChangesTarget(target.messageID, card.changesInitialFilePath)
            }
        }
        if let detail = card.detailText {
            return {
                ProductAnalytics.logUserAction(
                    screenId: ProductAnalytics.Screen.sessionChat,
                    actionId: ProductAnalytics.Action.chatOpenToolDetail
                )
                ProductAnalytics.logScreenView(
                    screenId: ProductAnalytics.Screen.sessionToolDetail,
                    presentation: ProductAnalytics.Presentation.sheet,
                    trigger: ProductAnalytics.Trigger.present
                )
                toolDetailTarget = SessionChatToolDetailTarget(
                    title: card.title,
                    body: detail
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
