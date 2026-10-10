import CoreGraphics

/// Screens that render as a split view on regular width. Each keeps its own
/// persisted leading-pane width and collapsed state.
enum OpenCodeSplitPaneScreen: String, CaseIterable, Sendable {
    case home
    case settings
}

/// Leading-pane geometry for an `OpenCodeSplitPane`.
struct OpenCodeSplitPaneState: Equatable, Sendable {
    /// Last open width. Kept while collapsed so reopening restores it.
    var leadingWidth: CGFloat
    var isCollapsed: Bool

    static let initial = OpenCodeSplitPaneState(
        leadingWidth: OpenCodeSplitPaneLayout.defaultLeadingWidth,
        isCollapsed: false
    )
}

/// Pure sizing rules for the split-pane divider and handle.
enum OpenCodeSplitPaneLayout {
    static let minLeadingWidth: CGFloat = 280
    static let maxLeadingWidth: CGFloat = 480
    static let defaultLeadingWidth: CGFloat = 360
    /// Releasing an open pane narrower than this snaps it closed.
    static let collapseThreshold: CGFloat = 220
    /// Dragging a collapsed pane wider than this reopens it.
    static let reopenThreshold: CGFloat = 120

    static let dividerWidth: CGFloat = 1
    static let handleGripWidth: CGFloat = 6
    static let handleGripHeight: CGFloat = 40
    static let handleHitWidth: CGFloat = 28
    static let handleHitHeight: CGFloat = 72
    /// Drags shorter than this count as a tap on the handle.
    static let tapSlop: CGFloat = 4

    static func clamp(width: CGFloat) -> CGFloat {
        guard width.isFinite else { return defaultLeadingWidth }
        return min(maxLeadingWidth, max(minLeadingWidth, width))
    }

    /// Width shown while a drag is in flight. Not clamped below so the user sees
    /// the pane shrinking toward the snap point.
    static func liveWidth(proposedWidth: CGFloat) -> CGFloat {
        guard proposedWidth.isFinite else { return defaultLeadingWidth }
        return min(maxLeadingWidth, max(0, proposedWidth))
    }

    /// Resolves the pane state when a divider drag ends.
    /// - Parameters:
    ///   - previous: State before the drag began. Its width is the remembered open width.
    ///   - proposedWidth: Leading width under the finger at release.
    static func resolveDragEnd(
        previous: OpenCodeSplitPaneState,
        proposedWidth: CGFloat
    ) -> OpenCodeSplitPaneState {
        let remembered = clamp(width: previous.leadingWidth)
        if previous.isCollapsed {
            if proposedWidth > reopenThreshold {
                return OpenCodeSplitPaneState(leadingWidth: remembered, isCollapsed: false)
            }
            return OpenCodeSplitPaneState(leadingWidth: remembered, isCollapsed: true)
        }
        if proposedWidth < collapseThreshold {
            return OpenCodeSplitPaneState(leadingWidth: remembered, isCollapsed: true)
        }
        return OpenCodeSplitPaneState(leadingWidth: clamp(width: proposedWidth), isCollapsed: false)
    }

    /// Convenience overload matching the drag start flag.
    static func resolveDragEnd(
        startedCollapsed: Bool,
        rememberedWidth: CGFloat = defaultLeadingWidth,
        proposedWidth: CGFloat
    ) -> OpenCodeSplitPaneState {
        resolveDragEnd(
            previous: OpenCodeSplitPaneState(leadingWidth: rememberedWidth, isCollapsed: startedCollapsed),
            proposedWidth: proposedWidth
        )
    }

    /// Leading-pane width change for a horizontal drag translation measured in
    /// global (unmirrored) coordinates. Right-to-left layouts put the leading
    /// pane on the right, so dragging left widens it.
    static func leadingWidthDelta(translationWidth: CGFloat, isRightToLeft: Bool) -> CGFloat {
        isRightToLeft ? -translationWidth : translationWidth
    }

    static func toggled(_ state: OpenCodeSplitPaneState) -> OpenCodeSplitPaneState {
        OpenCodeSplitPaneState(leadingWidth: clamp(width: state.leadingWidth), isCollapsed: !state.isCollapsed)
    }
}
