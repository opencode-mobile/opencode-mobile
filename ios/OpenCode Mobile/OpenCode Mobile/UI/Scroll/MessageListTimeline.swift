import SwiftUI

enum MessageListTimelineDefaults {
    static let pinnedTolerance: CGFloat = 8
    static let autoFollowTolerance: CGFloat = 32
    static let revealThresholdMin: CGFloat = 120
    static let messageSpacing: CGFloat = 8
    static let scrollToEndPillBottomPadding: CGFloat = 12
    static let horizontalContentPadding: CGFloat = 16
    static let topContentPadding: CGFloat = 12
    static let bottomContentPadding: CGFloat = 12
    static let programmaticAnimatedSettleMilliseconds = 250
    static let programmaticImmediateSettleMilliseconds = 50
    static let programmaticPinRetryLimit = 3
    /// Hold unanimated layout after the first pin while estimated heights settle.
    static let initialLoadSettlingMilliseconds = 400
}

struct MessageListScrollToEndOverlay: View {
    let visible: Bool
    let onTap: () -> Void

    var body: some View {
        if visible {
            OpenCodeActionPill(variant: .scrollToEnd, action: onTap)
                .padding(.bottom, MessageListTimelineDefaults.scrollToEndPillBottomPadding)
                .transition(.opacity.combined(with: .move(edge: .bottom)))
        }
    }
}
