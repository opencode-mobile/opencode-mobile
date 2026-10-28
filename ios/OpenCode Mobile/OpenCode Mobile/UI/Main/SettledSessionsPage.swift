import SwiftUI

struct SettledSessionsPage: View {
    let sessions: [SessionListSession]
    let onSessionSelected: (SessionListSession) -> Void
    let onSessionLongPress: (SessionListSession) -> Void
    let onSettleGesture: (SessionListSession, HomeSettleGesture) -> Void
    var onRefresh: (() async -> Void)? = nil

    @State private var visibleCount = HomeSettleFeed.settledPageSize

    var body: some View {
        page.modifier(SettledPageRefresh(onRefresh: onRefresh))
    }

    @ViewBuilder
    private var page: some View {
        let visible = Array(sessions.prefix(visibleCount))
        if sessions.isEmpty {
            Text("Nothing settled in this project.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textWeak)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(24)
        } else {
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(Array(visible.enumerated()), id: \.element.id) { index, session in
                        SettleSwipeRow(gestures: session.settleGestures) { gesture in
                            onSettleGesture(session, gesture)
                        } content: {
                            OpenCodeListItem(
                                title: session.title,
                                subtitle: session.subtitle.isEmpty ? nil : compactHomePath(
                                    session.subtitle,
                                    hideUserDirectoryPrefix: HomeDisplayPreferences.shared.hideUserDirectoryPrefix
                                ),
                                caption: session.caption.isEmpty ? nil : session.caption,
                                startDecorator: .letter(session.letter, session.letterAccent),
                                action: { onSessionSelected(session) },
                                onLongPress: { onSessionLongPress(session) }
                            )
                        }
                        if index < visible.count - 1 {
                            Divider()
                                .overlay(OpenCodeColors.borderWeakerBase)
                                .padding(.leading, 60)
                        }
                    }
                    if sessions.count > visible.count {
                        OpenCodeButton(variant: .compactText(title: "Show more")) {
                            visibleCount += HomeSettleFeed.settledPageSize
                        }
                        .padding(16)
                    }
                }
            }
            .background(OpenCodeColors.backgroundBase)
        }
    }
}

private struct SettledPageRefresh: ViewModifier {
    let onRefresh: (() async -> Void)?

    @ViewBuilder
    func body(content: Content) -> some View {
        if let onRefresh {
            content.refreshable { await onRefresh() }
        } else {
            content
        }
    }
}
