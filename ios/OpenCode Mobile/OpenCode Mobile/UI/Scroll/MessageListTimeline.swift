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
    /// Layout estimate seed when no per-row cache / heuristic exists.
    /// Higher than a single text line so estimate→real corrections are smaller.
    static let estimatedRowHeight: CGFloat = 120
    static let programmaticAnimatedSettleMilliseconds = 250
    static let programmaticImmediateSettleMilliseconds = 50
    static let programmaticPinRetryLimit = 3
    /// Hold unanimated layout after the first pin while estimated heights settle.
    static let initialLoadSettlingMilliseconds = 400
}

/// Absolute Y offset for the true timeline bottom (OPE-67 pin math).
enum MessageListTimelineBottomOffset {
    static func y(
        contentHeight: CGFloat,
        viewportHeight: CGFloat,
        adjustedTopInset: CGFloat,
        adjustedBottomInset: CGFloat
    ) -> CGFloat {
        max(
            -adjustedTopInset,
            contentHeight - viewportHeight + adjustedBottomInset
        )
    }
}

/// Optional per-item seed for compositional-layout estimated height.
protocol MessageListTimelineHeightEstimating {
    var timelineRowHeightEstimate: CGFloat { get }
}

enum MessageListTimelineRowHeightEstimate {
    enum Style {
        case outgoing
        case assistant
        case compact
        /// One caption line (timeline notices).
        case line
    }

    static func fallback(characterCount: Int, style: Style) -> CGFloat {
        switch style {
        case .compact:
            return 64
        case .line:
            return 24
        case .outgoing:
            return min(240, max(72, 56 + CGFloat(characterCount) * 0.32))
        case .assistant:
            return min(640, max(MessageListTimelineDefaults.estimatedRowHeight, 72 + CGFloat(characterCount) * 0.4))
        }
    }
}

/// Last-known self-sized row heights for estimate seeding (host-owned).
@MainActor
final class MessageListTimelineRowHeightCache {
    private var heights: [String: CGFloat] = [:]

    func height(for id: String) -> CGFloat? {
        heights[id]
    }

    func setHeight(_ height: CGFloat, for id: String) {
        guard height.isFinite, height > 0.5 else { return }
        heights[id] = height
    }

    func retainOnly(ids: Set<String>) {
        heights = heights.filter { ids.contains($0.key) }
    }
}

struct MessageListScrollToEndOverlay: View {
    let visible: Bool
    var applyBottomPadding: Bool = true
    let onTap: () -> Void

    var body: some View {
        if visible {
            OpenCodeActionPill(variant: .scrollToEnd, action: onTap)
                .padding(
                    .bottom,
                    applyBottomPadding ? MessageListTimelineDefaults.scrollToEndPillBottomPadding : 0
                )
                .transition(.opacity.combined(with: .move(edge: .bottom)))
        }
    }
}
